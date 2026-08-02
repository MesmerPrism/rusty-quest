package io.github.mesmerprism.rustyquest.spatial_video_control

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.Closeable

/** Advertises only non-secret connection metadata for native Bonjour clients. */
internal class LocalControlNsdAdvertiser(
    context: Context,
    private val onStatus: (String) -> Unit,
) : Closeable {
  private val manager = context.getSystemService(NsdManager::class.java)
  private var listener: NsdManager.RegistrationListener? = null

  fun start(port: Int, accessMode: ManifoldAuthorityPort.AccessMode) {
    close()
    val registration =
        object : NsdManager.RegistrationListener {
          override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            val name = serviceInfo.serviceName
            Log.i(TAG, "status=registered serviceName=$name serviceType=$SERVICE_TYPE port=$port")
            onStatus("mdns_registered:$name")
          }

          override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "status=registration-failed errorCode=$errorCode")
            listener = null
            onStatus("mdns_registration_failed:$errorCode")
          }

          override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "status=unregistered serviceName=${serviceInfo.serviceName}")
          }

          override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "status=unregistration-failed errorCode=$errorCode")
          }
        }
    val info =
        NsdServiceInfo().apply {
          serviceName = SERVICE_NAME
          serviceType = SERVICE_TYPE
          this.port = port
          setAttribute("protocol", TrustedLocalControlPolicy.PROTOCOL)
          setAttribute("access", accessMode.protocolName())
          setAttribute("path", "/")
          setAttribute("confidentiality", "none")
        }
    listener = registration
    runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
        .onFailure { error ->
          listener = null
          Log.w(TAG, "status=registration-exception", error)
          onStatus("mdns_registration_failed:${error.javaClass.simpleName}")
        }
  }

  override fun close() {
    val current = listener ?: return
    listener = null
    runCatching { manager.unregisterService(current) }
  }

  private companion object {
    const val TAG = "RustyQuestControlNsd"
    const val SERVICE_NAME = "Rusty Quest Video Control"
    const val SERVICE_TYPE = "_rustyquest-control._tcp."
  }
}
