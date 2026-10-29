// SPDX-License-Identifier: GPL-2.0-or-later
package org.kde.kdeconnect.plugins.media

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect_tp.R

class MediaActivity : AppCompatActivity() {
    private lateinit var deviceId: String
    private lateinit var sendAudio: CheckBox
    private lateinit var sendVideo: CheckBox
    private lateinit var receiveAudio: CheckBox
    private lateinit var receiveVideo: CheckBox
    private lateinit var front: CheckBox
    private lateinit var headset: CheckBox
    private var syncing = false
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var start: Button
    private lateinit var stop: Button
    private val main = Handler(Looper.getMainLooper())
    private var attached: MediaService? = null
    private val refresh = object : Runnable {
        override fun run() {
            val service = MediaService.instance
            if (service?.deviceId == deviceId) {
                if (attached !== service) {
                    attached = service
                    service.frameListener = { preview.setImageBitmap(it) }
                }
                syncing = true
                sendAudio.isChecked = service.sendAudio
                sendVideo.isChecked = service.sendVideo
                receiveAudio.isChecked = service.receiveAudio
                receiveVideo.isChecked = service.receiveVideo
                front.isChecked = service.frontCamera
                headset.isChecked = service.headsetMicrophone
                syncing = false
                status.text = getString(R.string.zenlink_media_active, service.audioPackets, service.videoFrames) +
                    (if (service.sendAudio) "\n${service.microphoneRoute}" else "") +
                    (if (service.desktopStatus.isNotBlank()) "\n${service.desktopStatus}" else "")
            } else {
                attached = null
                status.text = MediaService.lastError ?: getString(R.string.zenlink_media_idle)
            }
            start.isEnabled = service == null
            stop.isEnabled = service?.deviceId == deviceId
            for (view in listOf(sendAudio, sendVideo, receiveAudio, receiveVideo)) view.isEnabled = service == null
            front.isEnabled = service == null || (service.deviceId == deviceId && service.sendVideo)
            headset.isEnabled = service == null || (service.deviceId == deviceId && service.sendAudio)
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra("deviceId") ?: run { finish(); return }
        val device = KdeConnect.getInstance().getDevice(deviceId)
        if (device?.isPaired != true) { finish(); return }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding, padding, padding) }
        val scroll = ScrollView(this).apply { addView(layout) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        layout.addView(Button(this).apply { text = getString(R.string.zenlink_media_back); setOnClickListener { finish() } })
        layout.addView(TextView(this).apply { text = "${getString(R.string.zenlink_media)} · ${device.name}"; textSize = 22f })
        layout.addView(TextView(this).apply { text = getString(R.string.zenlink_media_instructions); textSize = 15f })
        fun option(label: Int) = CheckBox(this).apply { text = getString(label); layout.addView(this) }
        sendAudio = option(R.string.zenlink_media_send_microphone)
        sendVideo = option(R.string.zenlink_media_send_camera)
        front = option(R.string.zenlink_media_front_camera)
        headset = option(R.string.zenlink_media_headset_microphone)
        receiveAudio = option(R.string.zenlink_media_receive_audio)
        receiveVideo = option(R.string.zenlink_media_receive_camera)
        sendAudio.isChecked = true
        sendVideo.isChecked = true
        receiveAudio.isChecked = true
        front.setOnCheckedChangeListener { _, checked ->
            if (!syncing) MediaService.instance?.takeIf { it.deviceId == deviceId }?.switchCamera(checked)
        }
        headset.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            if (checked && Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 902)
            } else switchMicrophone(checked)
        }
        start = Button(this).apply { text = getString(R.string.zenlink_media_start); setOnClickListener { requestStart() } }
        stop = Button(this).apply {
            text = getString(R.string.zenlink_media_stop)
            setOnClickListener { MediaService.instance?.takeIf { it.deviceId == deviceId }?.stopSelf() }
        }
        layout.addView(start)
        layout.addView(stop)
        status = TextView(this).apply { textSize = 15f; accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        layout.addView(status)
        preview = ImageView(this).apply {
            contentDescription = getString(R.string.zenlink_media_preview)
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (260 * resources.displayMetrics.density).toInt())
        }
        layout.addView(preview)
    }

    private fun requestStart() {
        val permissions = mutableListOf<String>()
        if (sendAudio.isChecked) permissions += Manifest.permission.RECORD_AUDIO
        if (sendVideo.isChecked) permissions += Manifest.permission.CAMERA
        if (headset.isChecked && Build.VERSION.SDK_INT >= 31) permissions += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 37) permissions += "android.permission.ACCESS_LOCAL_NETWORK"
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 901) else begin()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 902) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) switchMicrophone(true)
            else { syncing = true; headset.isChecked = false; syncing = false }
            return
        }
        if (requestCode == 901 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) begin()
        else status.text = getString(R.string.zenlink_media_permissions)
    }

    private fun switchMicrophone(useHeadset: Boolean) {
        val service = MediaService.instance?.takeIf { it.deviceId == deviceId } ?: return
        val changed = try { service.setMicrophone(useHeadset) } catch (_: SecurityException) { false }
        if (!changed) {
            Toast.makeText(this, R.string.zenlink_media_headset_unavailable, Toast.LENGTH_LONG).show()
            syncing = true
            headset.isChecked = service.headsetMicrophone
            syncing = false
        }
    }

    private fun begin() {
        if (MediaService.instance != null) return
        val intent = Intent(this, MediaService::class.java).putExtra("deviceId", deviceId)
            .putExtra("sendAudio", sendAudio.isChecked).putExtra("sendVideo", sendVideo.isChecked)
            .putExtra("receiveAudio", receiveAudio.isChecked).putExtra("receiveVideo", receiveVideo.isChecked)
            .putExtra("frontCamera", front.isChecked)
            .putExtra("headsetMicrophone", headset.isChecked)
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onStart() { super.onStart(); main.post(refresh) }
    override fun onStop() {
        main.removeCallbacksAndMessages(null)
        attached?.frameListener = null
        attached = null
        super.onStop()
    }
}
