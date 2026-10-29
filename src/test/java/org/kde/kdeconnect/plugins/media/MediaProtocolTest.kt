// SPDX-License-Identifier: GPL-2.0-or-later
package org.kde.kdeconnect.plugins.media

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.kde.kdeconnect.NetworkPacket

class MediaProtocolTest {
    private fun audio() = NetworkPacket(MediaProtocol.AUDIO).apply {
        this["version"] = 1
        this["session"] = "local-consent-session"
        this["format"] = "s16le"
        this["rate"] = 48000
        this["channels"] = 1
        this["data"] = Base64.getEncoder().encodeToString(ByteArray(1920))
    }

    @Test fun acceptsNegotiatedAudio() {
        assertEquals(1920, MediaProtocol.decode(audio(), "local-consent-session")!!.size)
    }

    @Test fun rejectsPreviousSessionAndUnknownVersion() {
        assertNull(MediaProtocol.decode(audio(), "previous-session"))
        assertNull(MediaProtocol.decode(audio().apply { this["version"] = 2 }, "local-consent-session"))
    }

    @Test fun rejectsFormatChangesAndTruncatedAudio() {
        assertNull(MediaProtocol.decode(audio().apply { this["rate"] = 44100 }, "local-consent-session"))
        assertNull(MediaProtocol.decode(audio().apply { this["channels"] = 2 }, "local-consent-session"))
        assertNull(MediaProtocol.decode(audio().apply { this["data"] = "AAAA" }, "local-consent-session"))
    }

    @Test fun rejectsInvalidAndOversizedDataBeforeDecoding() {
        for (data in listOf("#".repeat(2560), "A".repeat(2564), "AAAA\n", "====")) {
            assertNull(MediaProtocol.decode(audio().apply { this["data"] = data }, "local-consent-session"))
        }
    }

    @Test fun rejectsNonJpegVideo() {
        val packet = audio().let { NetworkPacket(MediaProtocol.VIDEO).apply {
            this["version"] = 1
            this["session"] = "local-consent-session"
            this["format"] = "jpeg"
            this["data"] = "AAAA"
        } }
        assertNull(MediaProtocol.decode(packet, "local-consent-session"))
    }
}
