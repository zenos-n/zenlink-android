// SPDX-License-Identifier: GPL-2.0-or-later
package org.kde.kdeconnect.plugins.media

import java.util.Base64
import org.kde.kdeconnect.NetworkPacket

object MediaProtocol {
    const val CONTROL = "zenlink.media.control"
    const val AUDIO = "zenlink.media.audio"
    const val VIDEO = "zenlink.media.video"
    const val RATE = 48000
    const val AUDIO_BYTES = 1920 // 20 ms, mono signed 16-bit little endian
    const val MAX_JPEG = 49152
    private val base64 = Regex("[A-Za-z0-9+/]*={0,2}")

    fun decode(packet: NetworkPacket, session: String): ByteArray? {
        if (packet.getInt("version") != 1 || packet.getString("session") != session) return null
        val limit = when (packet.type) {
            AUDIO -> {
                if (packet.getInt("rate") != RATE || packet.getInt("channels") != 1 ||
                    packet.getString("format") != "s16le") return null
                AUDIO_BYTES
            }
            VIDEO -> {
                if (packet.getString("format") != "jpeg") return null
                MAX_JPEG
            }
            else -> return null
        }
        val encoded = packet.getString("data")
        if (encoded.isEmpty() || encoded.length > ((limit + 2) / 3) * 4 ||
            encoded.length % 4 != 0 || !base64.matches(encoded)) return null
        val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { return null }
        if (bytes.size > limit || (packet.type == AUDIO && bytes.size != AUDIO_BYTES)) return null
        if (packet.type == VIDEO && (bytes.size < 4 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte())) return null
        return bytes
    }
}
