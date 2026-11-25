package com.example.myreceipts

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanReceiptActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var flashEnabled = false

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                Toast.makeText(this, "Для съёмки квитанции нужен доступ к камере", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private val selectImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importSelectedImage(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_receipt)
        cameraExecutor = Executors.newSingleThreadExecutor()
        previewView = findViewById(R.id.previewView)

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnShutter).setOnClickListener { capturePhoto() }
        findViewById<ImageButton>(R.id.btnGallery).setOnClickListener { selectImage.launch("image/*") }
        findViewById<ImageButton>(R.id.btnFlash).setOnClickListener { toggleFlash() }
        findViewById<android.view.View>(R.id.btnManual).setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
                imageCapture = capture
                findViewById<ImageButton>(R.id.btnFlash).isEnabled =
                    camera?.cameraInfo?.hasFlashUnit() == true
            } catch (error: Exception) {
                Toast.makeText(this, "Не удалось запустить камеру: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val capture = imageCapture
        if (capture == null) {
            Toast.makeText(this, "Камера ещё не готова", Toast.LENGTH_SHORT).show()
            return
        }

        val scansDirectory = File(filesDir, SCANS_DIRECTORY)
        if (!scansDirectory.exists() && !scansDirectory.mkdirs()) {
            Toast.makeText(this, "Не удалось создать папку для квитанций", Toast.LENGTH_LONG).show()
            return
        }
        val photo = File(scansDirectory, "receipt_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(photo).build()
        capture.takePicture(
            options,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    runOnUiThread {
                        Toast.makeText(this@ScanReceiptActivity, "Фото сохранено", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }

                override fun onError(error: ImageCaptureException) {
                    runOnUiThread {
                        Toast.makeText(
                            this@ScanReceiptActivity,
                            "Не удалось сохранить фото: ${error.localizedMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )
    }

    private fun toggleFlash() {
        val activeCamera = camera ?: return
        if (!activeCamera.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "Вспышка недоступна на этом устройстве", Toast.LENGTH_SHORT).show()
            return
        }
        flashEnabled = !flashEnabled
        activeCamera.cameraControl.enableTorch(flashEnabled)
        findViewById<ImageButton>(R.id.btnFlash).isSelected = flashEnabled
    }

    private fun importSelectedImage(uri: Uri) {
        val scansDirectory = File(filesDir, SCANS_DIRECTORY)
        if (!scansDirectory.exists() && !scansDirectory.mkdirs()) {
            Toast.makeText(this, "Не удалось создать папку для квитанций", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val extension = contentResolver.getImageExtension(uri)
            val photo = File(scansDirectory, "receipt_${System.currentTimeMillis()}.$extension")
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Не удалось прочитать выбранное изображение" }
                FileOutputStream(photo).use(input::copyTo)
            }
            Toast.makeText(this, "Фото добавлено", Toast.LENGTH_SHORT).show()
            finish()
        } catch (error: Exception) {
            Toast.makeText(this, "Не удалось загрузить фото: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun ContentResolver.getImageExtension(uri: Uri): String {
        val mimeType = getType(uri)
        val mimeExtension = mimeType?.substringAfter('/', "")?.takeIf { it.isNotBlank() }
        if (mimeExtension != null) return mimeExtension

        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)
                val extension = name?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
                if (extension != null) return extension
            }
        }
        return "jpg"
    }

    private companion object {
        const val SCANS_DIRECTORY = "receipt_scans"
    }
}
