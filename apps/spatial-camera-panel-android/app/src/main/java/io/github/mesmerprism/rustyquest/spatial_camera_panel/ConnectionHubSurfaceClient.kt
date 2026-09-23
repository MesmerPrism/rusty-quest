
package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer
import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Effect
import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.EffectType
import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Event
import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.OperationKind
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin provider adapter. Android Binder supplies UID/package/signer evidence;
 * this client never supplies or overrides those fields. The shared reducer
 * fences every callback, reply, timer, and cleanup effect to one generation.
 */
internal class ConnectionHubSurfaceClient(
    context: Context,
    private val target: ConnectionHubSurfaceTarget,
) : Closeable {
  private data class AuthorizedSurfaceCommand(
      val requestId: String,
      val surfaceId: String,
      val command: String,
      val args: JSONObject,
      val authorityReceipt: JSONObject,
  )

  private data class AppliedSurfaceCommand(
      val command: String,
      val expectedRevision: Long,
      val stateJson: String,
  )

  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private val workerThread =
      HandlerThread(WORKER_THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
  private val handler = ProviderHandler(workerThread.looper)
  private val callback = Messenger(handler)
  private val random = SecureRandom()
  private var sessionState =
      ConnectionHubAdmissionSessionReducer.initial(positiveGeneration())
  private var broker: Messenger? = null
  private var activeConnection: SessionConnection? = null
  private var admissionRevision = 0L
  private var tokenId = ""
  private var authorizationCorrelationId = ""
  private var registrationId = ""
  private var registrationJson = ""
  private val started = AtomicBoolean(false)
  private var lastPublishedStateJson = ""
  private val deadlines = mutableMapOf<String, Runnable>()
  private val surfaceChangeObserver: () -> Unit = {
    handler.post {
      if (!started.get()) return@post
      reconcileSurfaceAvailability()
      if (sessionState.isRegistered && target.hubSurfaceAvailable()) {
        handler.removeCallbacks(statePublisher)
        handler.post(statePublisher)
      }
    }
  }
  private val statePublisher =
      object : Runnable {
        override fun run() {
          if (!started.get() || !sessionState.isRegistered) return
          if (!target.hubSurfaceAvailable()) {
            reconcileSurfaceAvailability()
            return
          }
          val state = target.hubSurfaceState()
          sendSurfaceStateIfChanged(state)
          connectionHubSurfaceStatePublishDelayMs(surfaceAvailable = true, state)?.let { delayMs ->
            handler.postDelayed(this, delayMs)
          }
        }
      }

  fun start() {
    if (!started.compareAndSet(false, true)) return
    target.setHubSurfaceChangeObserver(surfaceChangeObserver)
    handler.post {
      marker("client_started")
      reconcileSurfaceAvailability()
    }
  }

  /** Runs only on the Hub worker after an owner-change signal or lifecycle transition. */
  private fun reconcileSurfaceAvailability() {
    check(Looper.myLooper() === handler.looper) { "hub-worker-thread-required" }
    if (!started.get()) return
    val shouldPublish = target.hubSurfaceAvailable()
    if (shouldPublish &&
        (sessionState.phase ==
            ConnectionHubAdmissionSessionReducer.Phase.IDLE ||
            sessionState.phase ==
                ConnectionHubAdmissionSessionReducer.Phase.CLOSED)) {
      dispatch(Event.start(SystemClock.uptimeMillis()))
    } else if (!shouldPublish &&
        sessionState.phase != ConnectionHubAdmissionSessionReducer.Phase.IDLE &&
        sessionState.phase != ConnectionHubAdmissionSessionReducer.Phase.CLOSED) {
      handler.removeCallbacks(statePublisher)
      dispatch(Event.close(sessionState.bindingGeneration, SystemClock.uptimeMillis()))
    }
  }

  override fun close() {
    if (!started.compareAndSet(true, false)) return
    target.setHubSurfaceChangeObserver(null)
    handler.post {
      marker("client_stopping")
      handler.removeCallbacks(statePublisher)
      if (sessionState.phase != ConnectionHubAdmissionSessionReducer.Phase.IDLE &&
          sessionState.phase != ConnectionHubAdmissionSessionReducer.Phase.CLOSED) {
        dispatch(Event.close(sessionState.bindingGeneration, SystemClock.uptimeMillis()))
      }
      workerThread.quitSafely()
    }
  }

  private inner class SessionConnection(val generation: Long) : ServiceConnection {
    var binder: IBinder? = null
    val deathRecipient =
        IBinder.DeathRecipient {
          handler.post {
            dispatch(Event.binderDied(generation, SystemClock.uptimeMillis()))
          }
        }

    override fun onServiceConnected(name: ComponentName, value: IBinder) {
      handler.post {
        if (!started.get()) return@post
        binder = value
        broker = Messenger(value)
        dispatch(Event.connected(generation, SystemClock.uptimeMillis()))
      }
    }

    override fun onServiceDisconnected(name: ComponentName) {
      handler.post {
        if (!started.get()) return@post
        broker = null
        dispatch(Event.disconnected(generation, SystemClock.uptimeMillis()))
      }
    }

    override fun onBindingDied(name: ComponentName) {
      handler.post {
        if (!started.get()) return@post
        broker = null
        dispatch(Event.bindingDied(generation, SystemClock.uptimeMillis()))
      }
    }

    override fun onNullBinding(name: ComponentName) {
      handler.post {
        if (!started.get()) return@post
        broker = null
        dispatch(Event.nullBinding(generation, SystemClock.uptimeMillis()))
      }
    }
  }

  private fun dispatch(event: Event) {
    check(Looper.myLooper() === handler.looper) { "hub-worker-thread-required" }
    val wasRegistered = sessionState.isRegistered
    val result = ConnectionHubAdmissionSessionReducer.reduce(sessionState, event)
    sessionState = result.state
    result.effects.forEach(::execute)
    if (!wasRegistered && sessionState.isRegistered) {
      marker("surface_registered")
      handler.removeCallbacks(statePublisher)
      handler.post(statePublisher)
    } else if (wasRegistered && !sessionState.isRegistered) {
      handler.removeCallbacks(statePublisher)
    }
  }

  private fun execute(effect: Effect) {
    when (effect.type) {
      EffectType.BIND_SERVICE -> bind(effect)
      EffectType.LINK_DEATH -> linkDeath(effect)
      EffectType.SEND_RUNTIME_EVIDENCE -> sendRuntimeEvidence(effect)
      EffectType.SEND_ISSUE_TOKEN -> sendIssueToken(effect)
      EffectType.SEND_AUTHORIZE_USE -> sendAuthorizeUse(effect)
      EffectType.SEND_REGISTER_SURFACE -> sendRegisterSurface(effect)
      EffectType.SEND_UNREGISTER_SURFACE -> sendUnregisterSurface(effect)
      EffectType.UNLINK_DEATH -> unlinkDeath(effect)
      EffectType.UNBIND_SERVICE -> unbind(effect)
      EffectType.MARKER -> marker(effect.marker)
    }
  }

  private fun bind(effect: Effect) {
    val connection = SessionConnection(effect.bindingGeneration)
    activeConnection = connection
    val intent =
        Intent().setComponent(ComponentName(BROKER_PACKAGE, BROKER_ADMISSION_SERVICE))
    val accepted =
        runCatching { appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
    dispatch(
        Event.bindReturned(
            effect.bindingGeneration,
            accepted,
            SystemClock.uptimeMillis(),
        )
    )
  }

  private fun linkDeath(effect: Effect) {
    val connection = activeConnection
    if (connection == null || connection.generation != effect.bindingGeneration) {
      dispatch(Event.binderDied(effect.bindingGeneration, SystemClock.uptimeMillis()))
      return
    }
    runCatching { checkNotNull(connection.binder).linkToDeath(connection.deathRecipient, 0) }
        .onSuccess {
          marker("death_linked")
          dispatch(Event.deathLinked(effect.bindingGeneration, SystemClock.uptimeMillis()))
        }
        .onFailure {
          marker("death_link_failed_${it.javaClass.simpleName}")
          dispatch(Event.binderDied(effect.bindingGeneration, SystemClock.uptimeMillis()))
        }
  }

  private fun unlinkDeath(effect: Effect) {
    activeConnection
        ?.takeIf { it.generation == effect.bindingGeneration }
        ?.let { connection ->
          runCatching { connection.binder?.unlinkToDeath(connection.deathRecipient, 0) }
        }
  }

  private fun unbind(effect: Effect) {
    val connection = activeConnection?.takeIf { it.generation == effect.bindingGeneration }
    if (connection != null) {
      runCatching { appContext.unbindService(connection) }
      activeConnection = null
    }
    broker = null
    deadlines.values.forEach(handler::removeCallbacks)
    deadlines.clear()
    registrationId = ""
    registrationJson = ""
    tokenId = ""
    authorizationCorrelationId = ""
    admissionRevision = 0L
    lastPublishedStateJson = ""
  }

  private fun scheduleDeadline(effect: Effect) {
    val runnable =
        Runnable {
          deadlines.remove(effect.correlationId)
          dispatch(
              Event.deadline(
                  effect.bindingGeneration,
                  effect.correlationId,
                  SystemClock.uptimeMillis(),
              )
          )
        }
    deadlines.remove(effect.correlationId)?.let(handler::removeCallbacks)
    deadlines[effect.correlationId] = runnable
    handler.postDelayed(
        runnable,
        (effect.deadlineAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0L),
    )
  }

  private fun operationData(effect: Effect) =
      Bundle().apply {
        putString("correlation_id", effect.correlationId)
        putLong("session_generation", effect.sessionGeneration)
      }

  private fun sendRuntimeEvidence(effect: Effect) {
    sendOperation(MESSAGE_RUNTIME_EVIDENCE, operationData(effect), effect)
  }

  private fun sendIssueToken(effect: Effect) {
    val data =
        operationData(effect).apply {
          putString("request_id", effect.correlationId)
          putLong("expected_authority_revision", admissionRevision)
          putString("capabilities", SURFACE_PROVIDER_CAPABILITY)
          putLong("token_ttl_ms", 30_000L)
        }
    sendOperation(MESSAGE_ISSUE_TOKEN, data, effect)
  }

  private fun sendAuthorizeUse(effect: Effect) {
    val data =
        operationData(effect).apply {
          putString("request_id", effect.correlationId)
          putLong("expected_authority_revision", admissionRevision)
          putString("token_id", tokenId)
          putString("capability_id", SURFACE_PROVIDER_CAPABILITY)
        }
    sendOperation(MESSAGE_AUTHORIZE_USE, data, effect)
  }

  private fun sendRegisterSurface(effect: Effect) {
    if (registrationId != effect.registrationId) {
      registrationId = effect.registrationId
      registrationJson = surfaceRegistration().toString()
    }
    val data =
        operationData(effect).apply {
          putString("registration_id", registrationId)
          putString("registration_fingerprint_sha256", sha256(registrationJson))
          putString("authorization_correlation_id", authorizationCorrelationId)
          putString("surface_registration_json", registrationJson)
        }
    sendOperation(MESSAGE_REGISTER_SURFACE, data, effect)
  }

  private fun sendUnregisterSurface(effect: Effect) {
    val data =
        Bundle().apply {
          putString("correlation_id", "cleanup.s${effect.sessionGeneration}")
          putLong("session_generation", effect.sessionGeneration)
          putString("surface_id", SURFACE_ID)
        }
    sendRaw(MESSAGE_UNREGISTER_SURFACE, data)
  }

  private fun sendOperation(what: Int, data: Bundle, effect: Effect) {
    scheduleDeadline(effect)
    if (!sendRaw(what, data)) {
      dispatch(Event.disconnected(effect.bindingGeneration, SystemClock.uptimeMillis()))
    } else {
      marker(
          "send_${effect.operation.name.lowercase()}_a${effect.attempt}_${sanitize(effect.correlationId)}"
      )
    }
  }

  private inner class ProviderHandler(looper: Looper) : Handler(looper) {
    override fun handleMessage(message: Message) {
      if (message.what == MESSAGE_SURFACE_COMMAND) {
        handleSurfaceCommand(message)
        return
      }
      if (message.what == MESSAGE_RUNTIME_EVIDENCE ||
          message.what == MESSAGE_ISSUE_TOKEN ||
          message.what == MESSAGE_AUTHORIZE_USE ||
          message.what == MESSAGE_REGISTER_SURFACE) {
        handleAdmissionReply(message)
      }
    }
  }

  private fun handleAdmissionReply(message: Message) {
    val correlationId = message.data.getString("correlation_id", "")
    val sessionGeneration = message.data.getLong("session_generation", 0L)
    val pending = sessionState.pending
    if (sessionGeneration != sessionState.sessionGeneration ||
        pending == null || pending.correlationId != correlationId) {
      dispatch(
          Event.reply(
              sessionState.bindingGeneration,
              correlationId,
              false,
              "stale_reply",
              message.data.getString("broker_epoch_id", ""),
              SystemClock.uptimeMillis(),
          )
      )
      return
    }
    deadlines.remove(correlationId)?.let(handler::removeCallbacks)
    val error = message.data.getString("error", "")
    runCatching {
          val response = JSONObject(message.data.getString("response_json", "{}"))
          val applied = error.isEmpty() && responseApplied(pending.kind, response)
          if (applied) retainResponse(pending.kind, response, correlationId)
          dispatch(
              Event.reply(
                  sessionState.bindingGeneration,
                  correlationId,
                  applied,
                  if (error.isEmpty()) responseReason(response) else sanitize(error),
                  message.data.getString("broker_epoch_id", ""),
                  SystemClock.uptimeMillis(),
              )
          )
        }
        .onFailure {
          dispatch(
              Event.reply(
                  sessionState.bindingGeneration,
                  correlationId,
                  false,
                  "response_decode_${it.javaClass.simpleName}",
                  message.data.getString("broker_epoch_id", ""),
                  SystemClock.uptimeMillis(),
              )
          )
        }
  }

  private fun responseApplied(kind: OperationKind, response: JSONObject): Boolean =
      when (kind) {
        OperationKind.RUNTIME_EVIDENCE -> response.has("runtime")
        OperationKind.ISSUE_TOKEN,
        OperationKind.AUTHORIZE_USE ->
            response.optJSONObject("receipt")?.optBoolean("applied") == true
        OperationKind.REGISTER_SURFACE -> response.optBoolean("applied", false)
        OperationKind.NONE -> false
      }

  private fun retainResponse(
      kind: OperationKind,
      response: JSONObject,
      correlationId: String,
  ) {
    when (kind) {
      OperationKind.RUNTIME_EVIDENCE ->
          admissionRevision =
              response
                  .getJSONObject("runtime")
                  .getJSONObject("admission_snapshot")
                  .getLong("authority_revision")
      OperationKind.ISSUE_TOKEN -> {
        val receipt = response.getJSONObject("receipt")
        tokenId = receipt.getJSONObject("token").getString("token_id")
        admissionRevision = receipt.getLong("resulting_authority_revision")
      }
      OperationKind.AUTHORIZE_USE -> {
        authorizationCorrelationId = correlationId
          admissionRevision =
              response.getJSONObject("receipt").getLong("resulting_authority_revision")
      }
      OperationKind.REGISTER_SURFACE,
      OperationKind.NONE -> Unit
    }
  }

  private fun responseReason(response: JSONObject): String =
      response.optJSONObject("receipt")?.optString("rejection_reason", "operation_rejected")
          ?: response.optString("status", "operation_rejected")

  private fun surfaceRegistration() =
      connectionHubSurfaceRegistration(target.hubSurfaceState())

  private fun sendSurfaceStateIfChanged(state: JSONObject, force: Boolean = false) {
    val stateJson = state.toString()
    if (!force && stateJson == lastPublishedStateJson) return
    val data =
        Bundle().apply {
          putString("correlation_id", randomToken("state"))
          putLong("session_generation", sessionState.sessionGeneration)
          putString("surface_id", SURFACE_ID)
          putString("state_json", stateJson)
        }
    if (sendRaw(MESSAGE_UPDATE_SURFACE_STATE, data)) {
      lastPublishedStateJson = stateJson
    } else {
      dispatch(Event.disconnected(sessionState.bindingGeneration, SystemClock.uptimeMillis()))
    }
  }

  private fun handleSurfaceCommand(message: Message) {
    val effectBinding = message.data.getString("effect_binding_json", "")
    val commandMarker = sanitize(message.data.getString("command", "missing"))
    val effectReplyTo = message.replyTo
    val retryPolicy =
        ConnectionHubAdmissionSessionReducer.commandRetryPolicy(
            message.data.getString("command", "")
        )
    marker("command_received_${commandMarker}_retry_${retryPolicy.name.lowercase()}")
    runCatching {
          val requestId = message.data.getString("request_id", "")
          val surfaceId = message.data.getString("surface_id", "")
          val command = message.data.getString("command", "")
          val args = JSONObject(message.data.getString("args_json", "{}"))
          val receipt = JSONObject(message.data.getString("authority_receipt_json", "{}"))
          requireConnectionHubCommandAuthorization(
              requestId,
              surfaceId,
              command,
              args,
              receipt,
          )
          AuthorizedSurfaceCommand(requestId, surfaceId, command, args, receipt)
        }
        .onSuccess { command ->
          mainHandler.post {
            val application =
                runCatching {
                  check(target.hubSurfaceAvailable()) { "surface_unavailable" }
                  val expectedRevision =
                      target.applyHubAuthorizedCommand(
                          command.requestId,
                          command.surfaceId,
                          command.command,
                          command.args,
                          command.authorityReceipt,
                      )
                  AppliedSurfaceCommand(
                      command = command.command,
                      expectedRevision = expectedRevision,
                      stateJson = target.hubSurfaceState().toString(),
                  )
                }
            handler.post {
              if (!started.get()) return@post
              application
                  .onSuccess { applied ->
                    completeSurfaceCommand(effectReplyTo, effectBinding, applied)
                  }
                  .onFailure { error ->
                    rejectSurfaceCommand(
                        effectReplyTo,
                        effectBinding,
                        commandMarker,
                        error,
                    )
                  }
            }
          }
        }
        .onFailure { error ->
          rejectSurfaceCommand(effectReplyTo, effectBinding, commandMarker, error)
        }
  }

  private fun completeSurfaceCommand(
      effectReplyTo: Messenger?,
      effectBinding: String,
      applied: AppliedSurfaceCommand,
  ) {
    val state = JSONObject(applied.stateJson)
    val observed =
        connectionHubCommandEffectObserved(applied.command, applied.expectedRevision, state)
    val ambiguous =
        !observed &&
            ConnectionHubAdmissionSessionReducer.commandRetryPolicy(applied.command) ==
                ConnectionHubAdmissionSessionReducer.CommandRetryPolicy
                    .OUTCOME_UNKNOWN_ON_AMBIGUITY
    marker(
        when {
          observed -> "effect_observed_${sanitize(applied.command)}"
          ambiguous -> "effect_outcome_unknown_${sanitize(applied.command)}"
          else -> "effect_not_observed_${sanitize(applied.command)}"
        }
    )
    val result = Bundle()
    result.putString("effect_binding_json", effectBinding)
    result.putBoolean("provider_applied", observed)
    result.putString(
        "status",
        when {
          observed -> "provider_effect_observed"
          ambiguous -> "provider_effect_outcome_unknown"
          else -> "provider_effect_not_observed"
        },
    )
    result.putString(
        "effect_status",
        when {
          observed -> "observed"
          ambiguous -> "outcome_unknown"
          else -> "rejected"
        },
    )
    result.putString("state_json", applied.stateJson)
    sendEffectResponse(effectReplyTo, result)
    if (observed && sessionState.isRegistered) {
      handler.removeCallbacks(statePublisher)
      sendSurfaceStateIfChanged(state, force = true)
      connectionHubSurfaceStatePublishDelayMs(surfaceAvailable = true, state)?.let { delayMs ->
        handler.postDelayed(statePublisher, delayMs)
      }
    }
  }

  private fun rejectSurfaceCommand(
      effectReplyTo: Messenger?,
      effectBinding: String,
      commandMarker: String,
      error: Throwable,
  ) {
    marker("command_rejected_${error.javaClass.simpleName}_$commandMarker")
    val result = Bundle()
    result.putString("effect_binding_json", effectBinding)
    result.putBoolean("provider_applied", false)
    result.putString("status", "provider_rejected_${error.javaClass.simpleName}")
    result.putString("effect_status", "rejected")
    result.putString("state_json", "{}")
    sendEffectResponse(effectReplyTo, result)
  }

  private fun sendEffectResponse(effectReplyTo: Messenger?, result: Bundle) {
    val response = Message.obtain(null, MESSAGE_SURFACE_COMMAND)
    response.data = result
    val effectStatus = sanitize(result.getString("effect_status", "missing"))
    runCatching { checkNotNull(effectReplyTo).send(response) }
        .onSuccess { marker("effect_response_sent_$effectStatus") }
        .onFailure { marker("effect_response_failed_${it.javaClass.simpleName}_$effectStatus") }
  }

  private fun sendRaw(what: Int, data: Bundle): Boolean {
    val message = Message.obtain(null, what)
    message.data = data
    message.replyTo = callback
    return runCatching { checkNotNull(broker).send(message) }
        .onFailure { marker("send_failed_${it.javaClass.simpleName}") }
        .isSuccess
  }

  private fun randomToken(prefix: String): String {
    val bytes = ByteArray(12).also(random::nextBytes)
    val suffix = bytes.joinToString("") { "%02x".format(it) }
    bytes.fill(0)
    return "$prefix.$suffix"
  }

  private fun positiveGeneration(): Long =
      (random.nextLong() and Long.MAX_VALUE).coerceAtLeast(1L)

  private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val encoded = digest.joinToString("") { "%02x".format(it) }
    digest.fill(0)
    return "sha256:$encoded"
  }

  private fun marker(status: String) {
    Log.i(
        TAG,
        "channel=rusty-connection-hub surfaceId=$SURFACE_ID" +
            " processGeneration=${sessionState.processGeneration}" +
            " bindingGeneration=${sessionState.bindingGeneration}" +
            " sessionGeneration=${sessionState.sessionGeneration}" +
            " brokerEpoch=${sanitize(sessionState.brokerEpochId)}" +
            " phase=${sessionState.phase.name.lowercase()}" +
            " lastPositive=${sanitize(sessionState.lastPositiveStage)}" +
            " terminalReason=${sanitize(sessionState.terminalReason)}" +
            " workerThread=${sanitize(Thread.currentThread().name)}" +
            " status=${sanitize(status)}",
    )
  }

  private fun sanitize(value: String?) =
      (value ?: "missing").replace(Regex("[^A-Za-z0-9_.-]"), "_").take(96)

  private companion object {
    const val TAG = "RqLockedPlaylistHub"
    const val WORKER_THREAD_NAME = "RqLockedPlaylistHub"
    const val BROKER_PACKAGE = "io.github.mesmerprism.rustymanifold.broker"
    const val BROKER_ADMISSION_SERVICE =
        "io.github.mesmerprism.rustymanifold.broker.ConnectionHubAdmissionService"
    const val SURFACE_ID = ConnectionHubLockedPlaylistContract.SURFACE_ID
    const val SURFACE_REGISTRATION_SCHEMA =
        "rusty.quest.connection_hub.surface_registration.v1"
    const val SURFACE_PROVIDER_CAPABILITY =
        "capability.connection_hub.provider.register"
    const val COMMAND_PAUSE = ConnectionHubLockedPlaylistContract.COMMAND_PAUSE
    const val COMMAND_PLAY = ConnectionHubLockedPlaylistContract.COMMAND_RESUME
    const val COMMAND_SELECT_NEXT = ConnectionHubLockedPlaylistContract.COMMAND_NEXT
    const val COMMAND_SELECT_PREVIOUS = ConnectionHubLockedPlaylistContract.COMMAND_PREVIOUS
    const val MESSAGE_ISSUE_TOKEN = 1
    const val MESSAGE_AUTHORIZE_USE = 2
    const val MESSAGE_RUNTIME_EVIDENCE = 6
    const val MESSAGE_REGISTER_SURFACE = 20
    const val MESSAGE_UPDATE_SURFACE_STATE = 21
    const val MESSAGE_UNREGISTER_SURFACE = 22
    const val MESSAGE_SURFACE_COMMAND = 23
    const val STATE_PUBLISH_INTERVAL_MS = 1_000L
  }
}

internal fun connectionHubSurfaceStatePublishDelayMs(
    surfaceAvailable: Boolean,
    state: JSONObject,
): Long? =
    if (surfaceAvailable &&
        state.optBoolean("running", false) &&
        !state.optBoolean("paused", false)) {
      1_000L
    } else {
      null
    }

internal fun connectionHubSurfaceRegistration(state: JSONObject): JSONObject =
    JSONObject()
        .put("\$schema", "rusty.quest.connection_hub.surface_registration.v1")
        .put("schema_version", 1)
        .put("surface_id", ConnectionHubLockedPlaylistContract.SURFACE_ID)
        .put("display_label", ConnectionHubLockedPlaylistContract.DISPLAY_LABEL)
        .put("description", ConnectionHubLockedPlaylistContract.DESCRIPTION)
        .put(
            "commands",
            JSONArray()
                .put(
                    connectionHubCommandDescriptor(
                        ConnectionHubLockedPlaylistContract.COMMAND_NEXT,
                        "Next",
                        ConnectionHubLockedPlaylistContract.CAPABILITY_NEXT,
                    )
                )
                .put(
                    connectionHubCommandDescriptor(
                        ConnectionHubLockedPlaylistContract.COMMAND_PAUSE,
                        "Pause",
                        ConnectionHubLockedPlaylistContract.CAPABILITY_PAUSE,
                    )
                )
                .put(
                    connectionHubCommandDescriptor(
                        ConnectionHubLockedPlaylistContract.COMMAND_PREVIOUS,
                        "Previous",
                        ConnectionHubLockedPlaylistContract.CAPABILITY_PREVIOUS,
                    )
                )
                .put(
                    connectionHubCommandDescriptor(
                        ConnectionHubLockedPlaylistContract.COMMAND_RESUME,
                        "Resume",
                        ConnectionHubLockedPlaylistContract.CAPABILITY_RESUME,
                    )
                ),
        )
        .put(
            "surface_contract_sha256",
            ConnectionHubLockedPlaylistContract.SURFACE_CONTRACT_SHA256,
        )
        .put("state", state)

private fun connectionHubCommandDescriptor(
    id: String,
    label: String,
    capability: String,
): JSONObject =
    JSONObject()
        .put("command", id)
        .put("display_label", label)
        .put("required_controller_capability", capability)
