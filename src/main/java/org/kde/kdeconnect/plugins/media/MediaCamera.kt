// SPDX-License-Identifier: GPL-2.0-or-later
package org.kde.kdeconnect.plugins.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal class MediaCamera(private val context: Context, private val front: Boolean,
    private val frame: (ByteArray) -> Unit, private val error: (String) -> Unit) : AutoCloseable {
    private val thread = HandlerThread("ZenLink camera").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var closed = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var orientation = 0
    private var opening = false
    private val finished = AtomicBoolean(false)
    private var afterClose: (() -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            val manager = context.getSystemService(CameraManager::class.java)
            val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
                ?: throw IllegalStateException("The selected camera is unavailable")
            val properties = manager.getCameraCharacteristics(id)
            val size = properties.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(ImageFormat.JPEG).minBy { it.width.toLong() * it.height }
            val sensor = properties.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val rotation = when (context.getSystemService(WindowManager::class.java).defaultDisplay.rotation) {
                Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
            }
            orientation = (sensor + if (front) rotation else -rotation + 360) % 360
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).also { output ->
                output.setOnImageAvailableListener({ source ->
                    val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        if (closed) return@setOnImageAvailableListener
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        encode(bytes)
                    } catch (e: Exception) { if (!closed) error(e.message ?: "Camera frame failed") }
                    finally { image.close() }
                    handler.postDelayed({ capture() }, 200)
                }, handler)
            }
            opening = true
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    opening = false
                    if (closed) { device.close(); return }
                    camera = device
                    device.createCaptureSession(listOf(reader!!.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(configured: CameraCaptureSession) {
                            if (closed) { configured.close(); return }
                            session = configured
                            capture()
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) { error("Camera stream could not be configured") }
                    }, handler)
                }
                override fun onDisconnected(device: CameraDevice) { opening = false; device.close(); if (!closed) error("Camera disconnected") }
                override fun onError(device: CameraDevice, code: Int) { opening = false; device.close(); if (!closed) error("Camera error $code") }
                override fun onClosed(device: CameraDevice) { finishClose() }
            }, handler)
        } catch (e: Exception) { opening = false; error(e.message ?: "Camera could not be opened") }
    }

    private fun capture() {
        if (closed) return
        try {
            val request = camera?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE) ?: return
            request.addTarget(reader!!.surface)
            request.set(CaptureRequest.JPEG_ORIENTATION, 0)
            session?.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    if (!closed) error("Camera capture failed (${failure.reason})")
                }
            }, handler)
        } catch (e: Exception) { if (!closed) error(e.message ?: "Camera capture failed") }
    }

    private fun encode(bytes: ByteArray) {
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        val ratio = minOf(1f, 320f / maxOf(original.width, original.height))
        val matrix = Matrix().apply { postScale(ratio, ratio); postRotate(orientation.toFloat()) }
        val scaled = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 60, output)
        if (output.size() <= MediaProtocol.MAX_JPEG) frame(output.toByteArray())
        if (scaled !== original) scaled.recycle()
        original.recycle()
    }

    override fun close() {
        closed = true
        handler.post {
            session?.close()
            camera?.close()
            if (camera == null && !opening) finishClose()
        }
    }

    fun closeThen(action: () -> Unit) {
        if (finished.get()) { action(); return }
        afterClose = action
        close()
    }

    private fun finishClose() {
        if (!finished.compareAndSet(false, true)) return
        reader?.close()
        thread.quitSafely()
        afterClose?.invoke()
    }
}
