package com.bitchat.android.rtc

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.bitchat.android.util.AppConstants
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages CameraX for video capture.
 * Provides frames through an ImageAnalysis.Analyzer.
 */
class Camera(
    private val context: Context,
    private val width: Int = AppConstants.VideoCall.DEFAULT_WIDTH,
    private val height: Int = AppConstants.VideoCall.DEFAULT_HEIGHT,
    private val targetFps: Int = AppConstants.VideoCall.DEFAULT_FRAME_RATE
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var lastDeliveredTsNs: Long = 0L

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        onFrame: (ImageProxy) -> Unit
    ) {
        // Ensure we have a live executor (it may have been shutdown in stopCamera).
        if (cameraExecutor.isShutdown) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(width, height),
                        // Prefer higher resolutions for device compatibility; the encoder will downscale
                        // to (width,height) before feeding MediaCodec.
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            // Analysis use case for processing frames
            imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
                // Enforce target FPS at the source. Always close frames we skip.
                val minIntervalNs = if (targetFps <= 0) 0L else 1_000_000_000L / targetFps
                val tsNs = imageProxy.imageInfo.timestamp
                val shouldDeliver = (minIntervalNs == 0L) || (lastDeliveredTsNs == 0L) || ((tsNs - lastDeliveredTsNs) >= minIntervalNs)

                if (!shouldDeliver) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                lastDeliveredTsNs = tsNs
                onFrame(imageProxy)
            }

            val cameraSelectorCandidates = listOf(
                CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL).build(),
                CameraSelector.DEFAULT_FRONT_CAMERA,
                CameraSelector.DEFAULT_BACK_CAMERA,
                CameraSelector.Builder().build()
            )

            var bound = false
            cameraProvider?.unbindAll()
            for (selector in cameraSelectorCandidates) {
                val hasCamera = runCatching { cameraProvider?.hasCamera(selector) == true }.getOrDefault(false)
                if (!hasCamera && selector != cameraSelectorCandidates.last()) {
                    Log.w(TAG, "Camera selector not available, trying fallback")
                    continue
                }
                try {
                    cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        imageAnalysis
                    )
                    bound = true
                    Log.d(TAG, "Camera bound with selector: $selector")
                    break
                } catch (exc: Exception) {
                    Log.w(TAG, "Use case binding failed for selector: $selector", exc)
                }
            }

            if (!bound) {
                Log.e(TAG, "Use case binding failed: no available camera selector")
            }

        }, ContextCompat.getMainExecutor(context))
    }

    fun stopCamera() {
        // Stop frame delivery first.
        try {
            imageAnalysis?.clearAnalyzer()
        } catch (_: Exception) {
        }

        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }

        imageAnalysis = null
        cameraProvider = null
        lastDeliveredTsNs = 0L

        // Shutdown analyzer thread.
        try {
            cameraExecutor.shutdown()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "Camera"
    }
}
