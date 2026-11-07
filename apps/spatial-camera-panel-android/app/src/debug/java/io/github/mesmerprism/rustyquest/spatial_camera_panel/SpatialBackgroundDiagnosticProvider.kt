package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONObject
import io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialBackgroundDiagnosticRegistry.*

/** Two closed commands, debug-only and explicitly selected by the private resource closure. */
class SpatialBackgroundDiagnosticProvider : ContentProvider() {
  private var runtime: Runtime? = null

  override fun onCreate(): Boolean {
    val app = context?.applicationContext as? Application ?: return false
    if (selected(app)) runtime = Runtime(app).also { app.registerActivityLifecycleCallbacks(it) }
    return true
  }

  override fun call(method: String, argument: String?, extras: Bundle?): Bundle {
    val app = context?.applicationContext ?: closed()
    SpatialBackgroundDiagnosticContract.requireCaller(BuildConfig.DEBUG, Binder.getCallingUid(), selected(app))
    app.enforceCallingPermission(Manifest.permission.DUMP, "background_diagnostic_closed")
    val copy = extras?.let(::Bundle) ?: closed()
    val fields = copy.keySet().associateWith { copy.get(it) }
    val request = SpatialBackgroundDiagnosticContract.parse(method, argument, fields)
    val owner = runtime ?: closed()
    val actor = owner.actor()
    if (!request.expected.matches(actor)) closed()
    if (method == "select" && request.expectedEpoch != actor.epoch) closed()
    return if (method == "observe") owner.observe(request) else owner.select(request)
  }

  private class Runtime(private val app: Application) : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val registry = SpatialBackgroundDiagnosticRegistry(SystemClock::elapsedRealtime, ::freshNonce)
    private val ids = WeakHashMap<SpatialCameraPanelActivity, String>()
    private var current: WeakReference<SpatialCameraPanelActivity>? = null
    private var resumeGeneration = 0L
    private val epoch = freshNonce()
    @Volatile private var actualActor: Actor? = null
    private var apkSize = -1L
    private var apkModified = -1L

    @Synchronized fun actor(): Actor {
      val apk = File(app.applicationInfo.sourceDir)
      actualActor?.let {
        check(apk.length() == apkSize && apk.lastModified() == apkModified) { "installed_apk_changed" }
        return it
      }
      val boot = File("/proc/sys/kernel/random/boot_id").readText().trim()
      require(boot.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
      val stat = File("/proc/self/stat").readText()
      require(stat.length <= 16384)
      val ticks = stat.substringAfterLast(") ").split(' ')[19].toLong().also { require(it > 0L) }
      val digest = MessageDigest.getInstance("SHA-256")
      apkSize = apk.length(); apkModified = apk.lastModified()
      require(apkSize in 1..(512L * 1024 * 1024))
      FileInputStream(apk).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
      }
      check(apk.length() == apkSize && apk.lastModified() == apkModified)
      check(File("/proc/sys/kernel/random/boot_id").readText().trim() == boot)
      return Actor(app.packageName, Process.myPid(), Process.myUid(), ticks, boot, hex(digest.digest()), epoch)
          .also { actualActor = it }
    }

    private fun fence(): Fence? {
      check(Looper.myLooper() === Looper.getMainLooper())
      val activity = current?.get() ?: return null
      val state = activity.backgroundControlObservation()
      return Fence(requireNotNull(actualActor), requireNotNull(ids[activity]), resumeGeneration,
          state.sceneEpoch, state.vrGeneration, state.revision, state.mode,
          state.sceneReady && state.vrReady && !activity.isFinishing && !activity.isDestroyed,
          state.sceneRetired)
    }

    fun observe(request: SpatialBackgroundDiagnosticContract.Request): Bundle {
      val future = CompletableFuture<Observation>()
      val deadline = SystemClock.elapsedRealtime() + WAIT_MS
      if (!main.post {
            if (future.isCancelled || SystemClock.elapsedRealtime() >= deadline) return@post
            try { future.complete(registry.observe(request, fence())) }
            catch (error: Exception) { future.completeExceptionally(error) }
          }) return response(request.nonce, "unavailable", registry.current(), null)
      return try {
        val value = future.get(WAIT_MS, TimeUnit.MILLISECONDS)
        response(request.nonce, if (value.ticket == null) "not_ready" else "observed", value.operation, value)
      } catch (_: TimeoutException) {
        future.cancel(false)
        response(request.nonce, "observation_pending", registry.current(), null)
      } catch (_: InterruptedException) {
        future.cancel(false)
        Thread.currentThread().interrupt()
        response(request.nonce, "observation_interrupted", registry.current(), null)
      }
    }

    fun select(request: SpatialBackgroundDiagnosticContract.Request): Bundle {
      val queued = registry.select(request)
      if (queued.terminal()) return response(request.nonce, "retained", queued, null)
      val future = CompletableFuture<Operation>()
      if (registry.claimDispatch(request.nonce)) {
        if (!main.post {
              try {
                future.complete(registry.execute(request.nonce, ::fence) { mode ->
                  val activity = current?.get() ?: error("resumed_activity_unavailable")
                  activity.selectBackgroundControlMode(
                      if (mode == SpatialBackgroundDiagnosticContract.Mode.BLACK) SpatialBackgroundMode.Black
                      else SpatialBackgroundMode.Video)
                  fence()
                })
              } catch (error: Exception) { future.completeExceptionally(error) }
            }) return response(request.nonce, "dispatch_rejected", registry.timeout(request.nonce), null)
      } else return response(request.nonce, "retained", queued, null)
      // Timeout and interruption cancel only queued work; entered work remains queryable Pending.
      return response(request.nonce, "observed", registry.await(request.nonce, future, WAIT_MS), null)
    }

    override fun onActivityResumed(activity: Activity) {
      if (activity !is SpatialCameraPanelActivity) return
      resumeGeneration = Math.addExact(resumeGeneration, 1)
      ids.getOrPut(activity, ::freshNonce)
      current = WeakReference(activity)
      registry.invalidate()
    }
    override fun onActivityPaused(activity: Activity) = clear(activity)
    override fun onActivityStopped(activity: Activity) = clear(activity)
    override fun onActivityDestroyed(activity: Activity) { clear(activity); ids.remove(activity) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    private fun clear(activity: Activity) {
      if (current?.get() === activity) { current = null; registry.invalidate() }
    }
  }

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor = closed()
  override fun getType(uri: Uri): String = closed()
  override fun insert(uri: Uri, values: ContentValues?): Uri? = closed()
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = closed()
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = closed()
  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = closed()
  override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor = closed()
  override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor = closed()

  companion object {
    private const val WAIT_MS = 1500L
    private fun selected(context: Context): Boolean {
      if (!BuildConfig.DEBUG) return false
      val value = runCatching {
        val id = context.resources.getIdentifier(SpatialBackgroundDiagnosticContract.RESOURCE, "integer", context.packageName)
        if (id == 0) 0 else context.resources.getInteger(id)
      }.getOrDefault(0)
      return SpatialBackgroundDiagnosticContract.selected(true, value)
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun freshNonce(): String = ByteArray(32).also(SecureRandom()::nextBytes).let(::hex)
    private fun fenceJson(fence: Fence?): Any {
      if (fence == null) return JSONObject.NULL
      val actor = fence.actor
      return JSONObject().put("package_id", actor.packageId).put("pid", actor.pid).put("uid", actor.uid)
          .put("start_ticks", actor.startTicks).put("boot_id", actor.bootId).put("apk_sha256", actor.apkSha256)
          .put("diagnostic_process_epoch", actor.epoch).put("activity_epoch", fence.activity)
          .put("resume_generation", fence.resumeGeneration).put("scene_epoch", fence.sceneEpoch)
          .put("vr_generation", fence.vrGeneration).put("background_revision", fence.revision)
          .put("mode", fence.mode).put("ready", fence.ready).put("retired", fence.retired)
    }
    private fun response(challenge: String, status: String, operation: Operation?, observation: Observation?): Bundle {
      val value = JSONObject().put("schema", SpatialBackgroundDiagnosticContract.SCHEMA)
          .put("challenge", challenge).put("status", status).put("scope", "app_background_mode_only")
          .put("render_adoption_proven", false).put("decoder_cleanup_proven", false)
      if (operation != null) value.put("operation", JSONObject().put("nonce", operation.nonce)
          .put("request_sha256", operation.requestSha256).put("phase", operation.phase.name.lowercase())
          .put("reason", operation.reason).put("effective_mode_observed", operation.effective)
          .put("before", fenceJson(operation.before)).put("after", fenceJson(operation.after)))
      if (observation != null) value.put("observation", JSONObject()
          .put("current", fenceJson(observation.fence)).put("ticket", observation.ticket ?: JSONObject.NULL)
          .put("sha256", observation.sha256 ?: JSONObject.NULL).put("expires_at_elapsed_ms", observation.expiresAtMs))
      val exact = value.toString()
      check(exact.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
      return Bundle().apply { putString("schema", SpatialBackgroundDiagnosticContract.SCHEMA); putString("receipt", exact) }
    }
    private fun closed(): Nothing = throw SecurityException("background_diagnostic_closed")
  }
}
