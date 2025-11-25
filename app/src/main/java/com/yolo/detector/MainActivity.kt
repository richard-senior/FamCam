package com.yolo.detector

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ExifInterface
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var darkOverlay: android.view.View
    private lateinit var btnPower: Button
    private lateinit var btnLabel: Button
    private lateinit var btnSettings: Button
    private lateinit var btnQuit: Button
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var detector: Detector
    private lateinit var yuvConverter: YuvToRgbConverter
    private lateinit var shutterSound: MediaActionSound
    
    private var isPowerModeEnabled = false
    private var originalBrightness = -1f
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraSelector: CameraSelector? = null
    private var camera: Camera? = null
    
    private var isInForeground = true  // App starts in foreground
    private var currentBitmap: Bitmap? = null
    private var currentBoxes: List<BoundingBox> = emptyList()
    
    // Cached detection (last hit)
    private var cachedBitmap: Bitmap? = null
    private var cachedBoxes: List<BoundingBox> = emptyList()
    
    // Configuration loaded from settings
    private var maxImagesPerPeriod = 64  // 8 per hour for 8 hours
    private var periodHours = 8
    private var confidenceThreshold = 0.7f
    private var frameRate = 3
    private var savePath = "YOLODetector"
    
    private var lastAutoSaveTime = 0L
    private var lastProcessTime = 0L
    private val minSaveIntervalMs: Long
        get() = if (DEBUG_MODE) 10000L else (periodHours * 3600 * 1000L) / maxImagesPerPeriod
    private val minProcessIntervalMs: Long
        get() = 1000L / frameRate

    private fun quitApp() {
        finishAffinity()
        android.os.Handler(mainLooper).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 300)
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val PREFS_NAME = "YoloDetectorPrefs"
        private const val KEY_LAST_AUTO_SAVE = "lastAutoSaveTime"
        
        // Debug mode: set to true for testing (saves every 10 seconds)
        private const val DEBUG_MODE = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check if launched from quit shortcut
        if (intent.getBooleanExtra("QUIT_APP", false)) {
            quitApp()
            return
        }
        
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        darkOverlay = findViewById(R.id.darkOverlay)
        btnPower = findViewById(R.id.btnPower)
        btnLabel = findViewById(R.id.btnLabel)
        btnSettings = findViewById(R.id.btnSettings)
        btnQuit = findViewById(R.id.btnQuit)

        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Initialize shutter sound
        shutterSound = MediaActionSound()
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        
        // Initialize YUV converter
        yuvConverter = YuvToRgbConverter(this)
        
        // Load settings
        loadSettings()
        
        // Load last auto-save time
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        lastAutoSaveTime = prefs.getLong(KEY_LAST_AUTO_SAVE, 0L)

        // Initialize TFLite detector
        detector = YoloDetector(
            context = this,
            modelPath = "yolov8n_float32.tflite",
            labelPath = "labels.txt",
            confidenceThreshold = if (DEBUG_MODE) 0.7f else confidenceThreshold
        )
        
        android.util.Log.d("YOLO", "=== YOLO Detector Started ===")
        android.util.Log.d("YOLO", "Debug Mode: $DEBUG_MODE")
        android.util.Log.d("YOLO", "Confidence: ${if (DEBUG_MODE) 70 else (confidenceThreshold * 100).toInt()}%")
        android.util.Log.d("YOLO", "Save interval: ${minSaveIntervalMs / 1000}s")
        android.util.Log.d("YOLO", "Frame rate: ${frameRate} FPS")

        btnPower.setOnClickListener {
            togglePowerMode()
        }
        
        btnLabel.setOnClickListener {
            startActivity(Intent(this, LabelingActivity::class.java))
        }
        
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        btnQuit.setOnClickListener {
            quitApp()
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }
    
    
    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        maxImagesPerPeriod = prefs.getInt(SettingsActivity.KEY_MAX_IMAGES, SettingsActivity.DEFAULT_MAX_IMAGES)
        periodHours = prefs.getInt(SettingsActivity.KEY_PERIOD_HOURS, SettingsActivity.DEFAULT_PERIOD_HOURS)
        confidenceThreshold = prefs.getInt(SettingsActivity.KEY_CONFIDENCE, SettingsActivity.DEFAULT_CONFIDENCE) / 100f
        frameRate = prefs.getInt(SettingsActivity.KEY_FRAME_RATE, SettingsActivity.DEFAULT_FRAME_RATE)
        savePath = prefs.getString(SettingsActivity.KEY_SAVE_PATH, SettingsActivity.DEFAULT_SAVE_PATH) ?: SettingsActivity.DEFAULT_SAVE_PATH
    }
    
    override fun onResume() {
        super.onResume()
        isInForeground = true
        loadSettings()  // Reload settings when returning to activity
        startCamera()
    }
    
    override fun onPause() {
        super.onPause()
        isInForeground = false
        cameraProvider?.unbindAll()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            preview = Preview.Builder()
                .build()

            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            bindCamera()

        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val selector = cameraSelector ?: return
        val analyzer = imageAnalyzer ?: return
        
        android.util.Log.d("YOLO", "Binding camera. Foreground: $isInForeground")
        
        try {
            provider.unbindAll()
            
            if (isInForeground) {
                // Bind preview and analysis when in foreground
                preview?.setSurfaceProvider(previewView.surfaceProvider)
                camera = provider.bindToLifecycle(this, selector, preview, analyzer)
                android.util.Log.d("YOLO", "Bound with preview")
            } else {
                // Bind only analysis when in background (no preview)
                camera = provider.bindToLifecycle(this, selector, analyzer)
                android.util.Log.d("YOLO", "Bound without preview")
            }
        } catch (e: Exception) {
            android.util.Log.e("YOLO", "Camera binding error", e)
            e.printStackTrace()
        }
    }

    private fun processImage(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        
        // Frame rate limiting
        if (currentTime - lastProcessTime < minProcessIntervalMs) {
            imageProxy.close()
            return
        }
        lastProcessTime = currentTime
        
        if (DEBUG_MODE) {
            android.util.Log.d("YOLO", "Processing frame...")
        }
        
        val bitmap = imageProxyToBitmap(imageProxy)
        currentBitmap = bitmap
        
        val allBoxes = detector.detect(bitmap)
        
        if (DEBUG_MODE && allBoxes != null) {
            android.util.Log.d("YOLO", "Raw detections: ${allBoxes.map { "${it.clsName}(${(it.cnf*100).toInt()}%)" }}")
        }
        
        val boxes = allBoxes?.filter { 
            it.clsName in listOf("person", "cat", "dog")
        } ?: emptyList()
        currentBoxes = boxes
        
        if (DEBUG_MODE) {
            android.util.Log.d("YOLO", "Detection complete. Found ${boxes.size} objects")
        }
        
        // Cache hit: store bitmap and boxes
        if (boxes.isNotEmpty()) {
            if (DEBUG_MODE) {
                android.util.Log.d("YOLO", "HIT: Detected ${boxes.size} objects: ${boxes.map { "${it.clsName}(${(it.cnf*100).toInt()}%)" }}")
            }
            
            // Recycle old cached bitmap
            cachedBitmap?.recycle()
            
            // Cache this hit
            cachedBitmap = bitmap.copy(bitmap.config, false)
            cachedBoxes = boxes
        }
        
        // Auto-save check: save cached hit if interval elapsed
        if (currentTime - lastAutoSaveTime >= minSaveIntervalMs) {
            if (cachedBitmap != null && cachedBoxes.isNotEmpty()) {
                saveImageWithLabels(cachedBitmap!!, cachedBoxes)
                lastAutoSaveTime = currentTime
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_AUTO_SAVE, lastAutoSaveTime)
                    .apply()
                
                // Clear cache after saving
                cachedBitmap?.recycle()
                cachedBitmap = null
                cachedBoxes = emptyList()
            }
        }
        
        // Only update UI if in foreground
        if (isInForeground) {
            runOnUiThread {
                overlayView.setResults(boxes)
            }
        }
        
        imageProxy.close()
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val rotation = imageProxy.imageInfo.rotationDegrees
        
        if (DEBUG_MODE) {
            android.util.Log.d("YOLO", "Image: ${imageProxy.width}x${imageProxy.height}, rotation: $rotation")
        }
        
        // Convert ImageProxy to Bitmap using YuvToRgbConverter
        val bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        yuvConverter.yuvToRgb(imageProxy, bitmap)
        
        // Rotate if needed
        if (rotation != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotation.toFloat())
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        
        return bitmap
    }
    
    private fun provideCapturefeedback() {
        // Play shutter sound
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
    }

    private fun saveImageWithLabels(bitmap: Bitmap, boxes: List<BoundingBox>) {
        try {
            // Provide capture feedback
            provideCapturefeedback()
            
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "${timeStamp}.jpg"
            
            // Resize to model input size (640x640)
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 640, 640, true)
            
            // Build YOLO labels string
            val labelContent = buildString {
                boxes.forEach { box ->
                    val classId = when(box.clsName) {
                        "person" -> 0
                        "cat" -> 15
                        "dog" -> 16
                        else -> -1
                    }
                    if (classId >= 0) {
                        appendLine("$classId ${box.cx} ${box.cy} ${box.w} ${box.h}")
                    }
                }
            }
            
            // Save image to temp file
            val tempImageFile = File(cacheDir, fileName)
            FileOutputStream(tempImageFile).use { fos ->
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            }
            resizedBitmap.recycle()
            
            // Embed YOLO labels in EXIF ImageDescription field
            val exif = ExifInterface(tempImageFile.absolutePath)
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, labelContent.trim())
            exif.saveAttributes()
            
            // Save to MediaStore
            val imageUri = saveImageToMediaStore(fileName, tempImageFile)
            tempImageFile.delete()
            
            if (imageUri == null) {
                throw IOException("Failed to save image via MediaStore")
            }
            
            android.util.Log.d("YOLO", "Auto-saved: $fileName (640x640) with ${boxes.size} detections (labels in EXIF)")
            
        } catch (e: Exception) {
            android.util.Log.e("YOLO", "FATAL: Failed to save files", e)
            e.printStackTrace()
            
            // Show error and exit
            runOnUiThread {
                Toast.makeText(this, "FATAL ERROR: Cannot save files. App will exit.", Toast.LENGTH_LONG).show()
            }
            
            // Exit app after short delay
            android.os.Handler(mainLooper).postDelayed({
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 2000)
        }
    }
    
    private fun saveImageToMediaStore(fileName: String, sourceFile: File): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/FamCam/images")
        }
        
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { outputStream ->
                sourceFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        return uri
    }

    private fun togglePowerMode() {
        isPowerModeEnabled = !isPowerModeEnabled
        
        if (isPowerModeEnabled) {
            // Enable power mode: keep screen on and dim
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            
            // Save original brightness
            originalBrightness = window.attributes.screenBrightness
            
            // Set minimum brightness
            val layoutParams = window.attributes
            layoutParams.screenBrightness = 0.0f
            window.attributes = layoutParams
            
            // Show dark overlay
            darkOverlay.visibility = android.view.View.VISIBLE
            
            btnPower.text = "⚡"
            Toast.makeText(this, "Power mode enabled - screen will stay on", Toast.LENGTH_SHORT).show()
        } else {
            // Disable power mode: remove keep screen on and restore brightness
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            
            // Restore brightness (auto)
            val layoutParams = window.attributes
            layoutParams.screenBrightness = if (originalBrightness >= 0) originalBrightness else -1f
            window.attributes = layoutParams
            
            // Hide dark overlay
            darkOverlay.visibility = android.view.View.GONE
            
            btnPower.text = "⚡"
            Toast.makeText(this, "Power mode disabled", Toast.LENGTH_SHORT).show()
        }
        
        // Clear cache after manual save
        if (cachedBitmap != null) {
            cachedBitmap?.recycle()
            cachedBitmap = null
            cachedBoxes = emptyList()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Clean up cached bitmap
        cachedBitmap?.recycle()
        cachedBitmap = null
        
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        if (::detector.isInitialized) {
            detector.close()
        }
        if (::yuvConverter.isInitialized) {
            yuvConverter.close()
        }
        if (::shutterSound.isInitialized) {
            shutterSound.release()
        }
    }
}
