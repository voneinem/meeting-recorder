package app.meetingrecorder.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX preview and still capture for the record screen. The camera is bound to the lifecycle of
 * the screen that shows it and released as soon as that screen is hidden, so it never stays open in
 * the background (Principle VI). It is also the [PhotoCamera] used by [PhotoTaker].
 */
class CameraController(private val context: Context) : PhotoCamera {
    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()

    private var provider: ProcessCameraProvider? = null

    /** Binds preview and capture to [owner]. Calling it again rebinds. */
    fun bind(owner: LifecycleOwner, previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val cameraProvider = runCatching { future.get() }.getOrNull() ?: return@addListener
                provider = cameraProvider
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                runCatching {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun unbind() {
        provider?.unbindAll()
        provider = null
    }

    override suspend fun capture(output: OutputStream) = suspendCancellableCoroutine { continuation ->
        val options = ImageCapture.OutputFileOptions.Builder(output).build()
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) = continuation.resume(Unit)

                override fun onError(exception: ImageCaptureException) = continuation.resumeWithException(exception)
            },
        )
    }
}
