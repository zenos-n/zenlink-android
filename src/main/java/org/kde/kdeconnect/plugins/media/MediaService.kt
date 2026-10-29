// SPDX-License-Identifier: GPL-2.0-or-later
package org.kde.kdeconnect.plugins.media

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect_tp.R
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.io.FileDescriptor
import java.io.PrintWriter

class MediaService : Service() {
    var deviceId = ""
        private set
    val session = UUID.randomUUID().toString()
    var receiveAudio = false
        private set
    var receiveVideo = false
        private set
    var sendAudio = false
        private set
    var sendVideo = false
        private set
    var frontCamera = false
        private set
    var headsetMicrophone = false
        private set
    private var switchingCamera = false
    private var previousAudioMode: Int? = null
    private var previousCommunicationDevice: AudioDeviceInfo? = null
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    val microphoneRoute: String get() = when (recorder?.routedDevice?.type) {
        null -> "Microphone route pending"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone"
        else -> "Headset microphone"
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) {
            if (headsetMicrophone && !runCatching { setMicrophone(true) }.getOrDefault(false)) {
                setMicrophone(false)
                android.widget.Toast.makeText(this@MediaService, "Headset disconnected; using phone microphone", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    @Volatile private var running = false
    @Volatile var audioPackets = 0L
        private set
    @Volatile var videoFrames = 0L
        private set
    @Volatile var cameraFrames = 0L
        private set
    @Volatile var microphonePackets = 0L
        private set
    @Volatile var frameListener: ((Bitmap) -> Unit)? = null
    @Volatile var desktopStatus = ""
    private val main = Handler(Looper.getMainLooper())
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var recordThread: Thread? = null
    private var playThread: Thread? = null
    private var camera: MediaCamera? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val audioQueue = ArrayBlockingQueue<ByteArray>(4)
    private val decoding = AtomicBoolean(false)
    private val decoder = Executors.newSingleThreadExecutor()
    private val plugin get() = KdeConnect.getInstance().getDevicePlugin<MediaPlugin>(deviceId)
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            val device = KdeConnect.getInstance().getDevice(deviceId)
            if (device?.isReachable != true || !device.isPaired) {
                fail("Device disconnected")
                return
            }
            announce()
            main.postDelayed(this, 2000)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        writer.println("ZenLink media active=$running frontCamera=$frontCamera headsetMicrophone=$headsetMicrophone")
        writer.println("sendAudio=$sendAudio sendVideo=$sendVideo receiveAudio=$receiveAudio receiveVideo=$receiveVideo")
        writer.println("microphonePackets=$microphonePackets cameraFrames=$cameraFrames receivedAudioPackets=$audioPackets receivedVideoFrames=$videoFrames")
        writer.println("microphoneRoute=$microphoneRoute")
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        deviceId = intent.getStringExtra("deviceId") ?: ""
        val device = KdeConnect.getInstance().getDevice(deviceId)
        if (device?.isPaired != true || !device.isReachable || plugin == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        sendAudio = intent.getBooleanExtra("sendAudio", false)
        sendVideo = intent.getBooleanExtra("sendVideo", false)
        frontCamera = intent.getBooleanExtra("frontCamera", false)
        receiveAudio = intent.getBooleanExtra("receiveAudio", false)
        receiveVideo = intent.getBooleanExtra("receiveVideo", false)
        try {
            check(!sendAudio || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            check(!sendVideo || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "ZenLink streaming", NotificationManager.IMPORTANCE_LOW))
            val open = Intent(this, MediaActivity::class.java).putExtra("deviceId", deviceId)
            val stop = PendingIntent.getService(this, 0, Intent(this, MediaService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("ZenLink · ${device.name}")
                .setContentText("Streaming active" + if (sendVideo || sendAudio) " · ${if (sendAudio) "microphone " else ""}${if (sendVideo) "camera" else ""}" else "")
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true).addAction(0, "Stop streaming", stop).build()
            if (Build.VERSION.SDK_INT >= 29) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (Build.VERSION.SDK_INT >= 30) {
                    if (sendAudio) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    if (sendVideo) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                startForeground(902, notification, types)
            } else startForeground(902, notification)
            lastError = null
            running = true
            instance = this
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZenLink:media").also { it.acquire() }
            if (receiveAudio) startPlayback()
            if (sendAudio) startMicrophone()
            if (sendAudio && intent.getBooleanExtra("headsetMicrophone", false) && !setMicrophone(true))
                android.widget.Toast.makeText(this, "No headset microphone is connected; using phone microphone", android.widget.Toast.LENGTH_LONG).show()
            if (sendVideo) openCamera()
            audioManager.registerAudioDeviceCallback(deviceCallback, main)
            main.post(heartbeat)
        } catch (e: Exception) { fail(e.message ?: e.javaClass.simpleName) }
        return START_NOT_STICKY
    }

    fun announce() {
        if (running) {
            plugin?.ready(this)
            plugin?.configure(this)
        }
    }

    fun remoteStopped(reason: String) {
        if (reason.isNotBlank()) lastError = reason.take(240)
        stopSelf()
    }

    private fun openCamera() {
        camera = MediaCamera(this, frontCamera,
            { bytes -> cameraFrames++; plugin?.sendFrame(MediaProtocol.VIDEO, bytes) }, ::fail).also { it.start() }
    }

    fun switchCamera(front: Boolean) {
        if (!running || !sendVideo || front == frontCamera) return
        frontCamera = front
        if (switchingCamera) return
        switchingCamera = true
        camera?.closeThen {
            main.post {
                camera = null
                switchingCamera = false
                if (running) openCamera()
            }
        }
    }

    fun setMicrophone(headset: Boolean): Boolean {
        val input = recorder ?: return false
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val externalTypes = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
        val selected = inputs.firstOrNull { if (headset) it.type in externalTypes else it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val communication = if (Build.VERSION.SDK_INT >= 31 && headset)
            audioManager.availableCommunicationDevices.firstOrNull { it.type in externalTypes } else null
        if (selected == null && communication == null) return false
        if (Build.VERSION.SDK_INT >= 31 && communication != null &&
            communication.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)) {
            if (previousAudioMode == null) {
                previousAudioMode = audioManager.mode
                previousCommunicationDevice = audioManager.communicationDevice
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (!audioManager.setCommunicationDevice(communication)) { restoreAudioRoute(); return false }
            player?.setPreferredDevice(communication)
        } else {
            restoreAudioRoute()
            if (Build.VERSION.SDK_INT < 31 && selected?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
        }
        if (!input.setPreferredDevice(selected)) { restoreAudioRoute(); return false }
        headsetMicrophone = headset
        return true
    }

    private fun restoreAudioRoute() {
        if (Build.VERSION.SDK_INT >= 31 && previousAudioMode != null) {
            val previous = previousCommunicationDevice
            if (previous != null && audioManager.availableCommunicationDevices.any { it.id == previous.id })
                audioManager.setCommunicationDevice(previous)
            else audioManager.clearCommunicationDevice()
            audioManager.mode = previousAudioMode!!
            previousAudioMode = null
            previousCommunicationDevice = null
            player?.setPreferredDevice(null)
        } else if (Build.VERSION.SDK_INT < 31 && headsetMicrophone) {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        }
    }

    fun receive(kind: String, bytes: ByteArray): Boolean {
        if (!running) return false
        if (kind == MediaProtocol.AUDIO && receiveAudio) {
            if (!audioQueue.offer(bytes)) { audioQueue.poll(); audioQueue.offer(bytes) }
            audioPackets++
            return true
        }
        if (kind == MediaProtocol.VIDEO && receiveVideo) {
            if (frameListener == null || !decoding.compareAndSet(false, true)) return true
            decoder.execute {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth !in 1..640 || bounds.outHeight !in 1..640) return@execute
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
                    videoFrames++
                    main.post { if (running) frameListener?.invoke(bitmap) }
                } finally { decoding.set(false) }
            }
            return true
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophone() {
        val minimum = AudioRecord.getMinBufferSize(MediaProtocol.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Microphone does not support 48 kHz mono audio" }
        val input = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaProtocol.RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, MediaProtocol.AUDIO_BYTES * 4))
        recorder = input
        check(input.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be opened" }
        check(setMicrophone(false)) { "Phone microphone could not be selected" }
        input.startRecording()
        recordThread = Thread({
            try {
                val buffer = ByteArray(MediaProtocol.AUDIO_BYTES)
                while (running) {
                    var filled = 0
                    while (running && filled < buffer.size) {
                        val count = input.read(buffer, filled, buffer.size - filled)
                        check(count > 0) { "Microphone read failed ($count)" }
                        filled += count
                    }
                    if (running) { microphonePackets++; plugin?.sendFrame(MediaProtocol.AUDIO, buffer) }
                }
            } catch (e: Exception) { if (running) fail(e.message ?: "Microphone failed") }
        }, "ZenLink microphone").also { it.start() }
    }

    private fun startPlayback() {
        val minimum = AudioTrack.getMinBufferSize(MediaProtocol.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Audio output does not support 48 kHz mono audio" }
        val output = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(MediaProtocol.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum, MediaProtocol.AUDIO_BYTES * 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
        player = output
        output.play()
        playThread = Thread({
            try {
                while (running) {
                    val bytes = audioQueue.take()
                    var offset = 0
                    while (running && offset < bytes.size) {
                        val count = output.write(bytes, offset, bytes.size - offset)
                        check(count > 0) { "Audio playback failed ($count)" }
                        offset += count
                    }
                }
            } catch (_: InterruptedException) { /* stopped */ }
            catch (e: Exception) { if (running) fail(e.message ?: "Playback failed") }
        }, "ZenLink playback").also { it.start() }
    }

    private fun fail(message: String) {
        lastError = message
        android.util.Log.e("ZenLinkMedia", message)
        main.post { stopSelf() }
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        frameListener = null
        plugin?.stopped(session)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        camera?.close()
        try { recorder?.stop() } catch (_: IllegalStateException) { }
        recordThread?.join(1000)
        recorder?.release()
        playThread?.interrupt()
        try { player?.stop() } catch (_: IllegalStateException) { }
        playThread?.join(1000)
        restoreAudioRoute()
        player?.release()
        decoder.shutdownNow()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        const val STOP = "org.zenos.zenlink.STOP_MEDIA"
        private const val CHANNEL = "zenlink.media"
        @Volatile var instance: MediaService? = null
            private set
        @Volatile var lastError: String? = null
            private set
    }
}
