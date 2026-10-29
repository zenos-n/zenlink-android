// SPDX-License-Identifier: GPL-2.0-or-later
package org.kde.kdeconnect.plugins.media

import android.content.Intent
import android.os.SystemClock
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R
import java.util.Base64

@LoadablePlugin
class MediaPlugin : Plugin() {
    override val displayName get() = context.getString(R.string.zenlink_media)
    override val description get() = context.getString(R.string.zenlink_media_description)
    override val minSdk = 26
    override val supportedPacketTypes = arrayOf(MediaProtocol.CONTROL, MediaProtocol.AUDIO, MediaProtocol.VIDEO)
    override val outgoingPacketTypes = supportedPacketTypes
    private var remoteSession = ""
    private var remoteAudio = false
    private var remoteVideo = false
    private var remoteSeen = 0L
    private var sequence = 0L
    private val pending = mutableMapOf<String, Pair<Long, Long>>()

    override fun getUiButtons() = listOf(PluginUiButton(displayName, R.drawable.ic_play_white) { activity ->
        activity.startActivity(Intent(activity, MediaActivity::class.java).putExtra("deviceId", device.deviceId))
    })

    @Synchronized
    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.getInt("version") != 1) return false
        val service = MediaService.instance?.takeIf { it.deviceId == device.deviceId }
        if (np.type == MediaProtocol.CONTROL) {
            val token = np.getString("session")
            when (np.getString("action")) {
                "ready" -> {
                    if (!token.matches(Regex("[a-fA-F0-9-]{36}"))) return false
                    val changed = token != remoteSession
                    if (changed) pending.clear()
                    remoteSession = token
                    remoteAudio = np.getBoolean("audio")
                    remoteVideo = np.getBoolean("video")
                    remoteSeen = SystemClock.elapsedRealtime()
                    service?.desktopStatus = np.getString("message").take(240)
                    if (changed) service?.announce()
                }
                "stop" -> if (token == remoteSession) {
                    remoteSession = ""
                    pending.clear()
                    service?.remoteStopped(np.getString("reason"))
                }
                "ack" -> if (token == remoteSession) {
                    val kind = np.getString("kind")
                    if (pending[kind]?.first == np.getLong("sequence")) pending.remove(kind)
                }
                else -> return false
            }
            return true
        }
        service ?: return false
        val bytes = MediaProtocol.decode(np, service.session) ?: return false
        if (!service.receive(np.type, bytes)) return false
        device.sendPacket(control("ack", service.session).apply {
            this["kind"] = np.type
            this["sequence"] = np.getLong("sequence")
        })
        return true
    }

    @Synchronized
    fun sendFrame(kind: String, bytes: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        if (remoteSession.isEmpty() || now - remoteSeen > 6000 ||
            (kind == MediaProtocol.AUDIO && !remoteAudio) ||
            (kind == MediaProtocol.VIDEO && !remoteVideo)) return
        // One unacknowledged frame per medium bounds latency and the shared transport queue.
        if (pending[kind]?.let { now - it.second < 1000 } == true) return
        if (bytes.size > MediaProtocol.MAX_JPEG) return
        val next = ++sequence
        pending[kind] = next to now
        device.sendPacket(NetworkPacket(kind).apply {
            this["version"] = 1
            this["session"] = remoteSession
            this["sequence"] = next
            this["format"] = if (kind == MediaProtocol.AUDIO) "s16le" else "jpeg"
            this["rate"] = MediaProtocol.RATE
            this["channels"] = 1
            this["data"] = Base64.getEncoder().encodeToString(bytes)
        })
    }

    fun ready(service: MediaService) {
        device.sendPacket(control("ready", service.session).apply {
            this["audio"] = service.receiveAudio
            this["video"] = service.receiveVideo
        })
    }

    fun configure(service: MediaService) {
        device.sendPacket(control("configure", service.session).apply {
            this["desktopAudio"] = service.receiveAudio
            this["desktopVideo"] = service.receiveVideo
            this["phoneAudio"] = service.sendAudio
            this["phoneVideo"] = service.sendVideo
        })
    }

    fun stopped(session: String) { device.sendPacket(control("stop", session)) }

    override fun onDestroy() {
        MediaService.instance?.takeIf { it.deviceId == device.deviceId }?.stopSelf()
    }

    private fun control(action: String, session: String) = NetworkPacket(MediaProtocol.CONTROL).apply {
        this["version"] = 1
        this["action"] = action
        this["session"] = session
    }
}
