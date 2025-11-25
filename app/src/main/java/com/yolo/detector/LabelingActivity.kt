package com.yolo.detector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class LabelingActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var labelOverlay: LabelOverlayView
    private lateinit var tvImageInfo: TextView
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnExport: Button
    private lateinit var btnDelete: Button
    private lateinit var btnBack: Button

    private var imageFiles = mutableListOf<File>()
    private var currentIndex = 0
    private var classes = listOf<String>()
    private var currentBitmap: Bitmap? = null
    private var currentBoxes = mutableListOf<BoundingBox>()

    companion object {
        private const val REQUEST_MEDIA_PERMISSION = 100
        private const val REQUEST_DELETE_PERMISSION = 101
        private const val REQUEST_WRITE_PERMISSION = 102
    }
    
    private var pendingDeleteIndex = -1
    private var pendingWriteBox: BoundingBox? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_labeling)

        imageView = findViewById(R.id.imageView)
        labelOverlay = findViewById(R.id.labelOverlay)
        tvImageInfo = findViewById(R.id.tvImageInfo)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnExport = findViewById(R.id.btnExport)
        btnDelete = findViewById(R.id.btnDelete)
        btnBack = findViewById(R.id.btnBack)

        // Load classes from settings
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        val classesStr = prefs.getString(SettingsActivity.KEY_CLASSES, SettingsActivity.DEFAULT_CLASSES) ?: SettingsActivity.DEFAULT_CLASSES
        classes = classesStr.split(",").map { it.trim() }

        btnPrev.setOnClickListener { 
            android.util.Log.d("LabelingActivity", "Prev clicked")
            navigateImage(-1) 
        }
        btnNext.setOnClickListener { 
            android.util.Log.d("LabelingActivity", "Next clicked")
            navigateImage(1) 
        }
        btnExport.setOnClickListener {
            exportDataset()
        }
        btnDelete.setOnClickListener {
            deleteCurrentImage()
        }
        btnBack.setOnClickListener { finish() }

        labelOverlay.setOnBoxClickListener { box ->
            showClassSelectionDialog(box)
        }

        // Check and request permission
        if (checkMediaPermission()) {
            loadImagesAndDisplay()
        } else {
            requestMediaPermission()
        }
    }

    private fun checkMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_MEDIA_IMAGES
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMediaPermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
        ActivityCompat.requestPermissions(this, permissions, REQUEST_MEDIA_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MEDIA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                loadImagesAndDisplay()
            } else {
                Toast.makeText(this, "Permission required to view images", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun loadImagesAndDisplay() {
        loadImages()
        
        android.util.Log.d("LabelingActivity", "Found ${imageFiles.size} images")
        Toast.makeText(this, "Found ${imageFiles.size} images", Toast.LENGTH_SHORT).show()

        if (imageFiles.isNotEmpty()) {
            displayImage(0)
        } else {
            Toast.makeText(this, "No images found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadImages() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        val savePath = prefs.getString(SettingsActivity.KEY_SAVE_PATH, SettingsActivity.DEFAULT_SAVE_PATH) ?: SettingsActivity.DEFAULT_SAVE_PATH
        
        val allFiles = mutableListOf<File>()
        
        // Query MediaStore for images in our directory
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        
        // Look in both root and images subdirectory
        val selection = "(${MediaStore.Images.Media.RELATIVE_PATH} = ? OR ${MediaStore.Images.Media.RELATIVE_PATH} = ?)"
        val selectionArgs = arrayOf(
            "DCIM/$savePath/",
            "DCIM/$savePath/images/"
        )
        
        android.util.Log.d("LabelingActivity", "Querying paths: ${selectionArgs.joinToString()}")
        
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            
            while (cursor.moveToNext()) {
                val filePath = cursor.getString(dataColumn)
                val fileName = cursor.getString(nameColumn)
                
                android.util.Log.d("LabelingActivity", "Found: $fileName at $filePath")
                
                // Exclude trashed files
                if (!fileName.startsWith(".trashed-")) {
                    val file = File(filePath)
                    allFiles.add(file)  // Don't check exists() - trust MediaStore
                }
            }
        }
        
        imageFiles = allFiles.toMutableList()
        android.util.Log.d("LabelingActivity", "Found ${imageFiles.size} total images via MediaStore")
    }

    private fun displayImage(index: Int) {
        if (index < 0 || index >= imageFiles.size) return
        
        currentIndex = index
        val file = imageFiles[index]
        
        android.util.Log.d("LabelingActivity", "Loading image: ${file.name}")
        
        // Load bitmap
        currentBitmap = BitmapFactory.decodeFile(file.absolutePath)
        imageView.setImageBitmap(currentBitmap)
        
        // Load EXIF bounding boxes
        currentBoxes.clear()
        try {
            val exif = ExifInterface(file.absolutePath)
            val boxesStr = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
            android.util.Log.d("LabelingActivity", "EXIF data: $boxesStr")
            if (boxesStr != null && boxesStr.isNotEmpty()) {
                parseBoxes(boxesStr)
            }
        } catch (e: Exception) {
            android.util.Log.e("LabelingActivity", "Error loading EXIF", e)
            e.printStackTrace()
        }
        
        android.util.Log.d("LabelingActivity", "Loaded ${currentBoxes.size} boxes")
        labelOverlay.setBoxes(currentBoxes)
        tvImageInfo.text = "${index + 1} / ${imageFiles.size} - ${file.name}"
        
        btnPrev.isEnabled = index > 0
        btnNext.isEnabled = index < imageFiles.size - 1
    }

    private fun parseBoxes(boxesStr: String) {
        // Format: "classId cx cy w h\nclassId cx cy w h\n..."
        // Coordinates are normalized 0-1, need to convert to pixels (640x640)
        boxesStr.lines().forEach { line ->
            val parts = line.trim().split(" ")
            if (parts.size >= 5) {
                try {
                    val cls = parts[0].toIntOrNull() ?: 0
                    val cx = parts[1].toFloat() * 640f
                    val cy = parts[2].toFloat() * 640f
                    val w = parts[3].toFloat() * 640f
                    val h = parts[4].toFloat() * 640f
                    
                    val x1 = cx - w / 2
                    val y1 = cy - h / 2
                    val x2 = cx + w / 2
                    val y2 = cy + h / 2
                    
                    val box = BoundingBox(
                        x1 = x1,
                        y1 = y1,
                        x2 = x2,
                        y2 = y2,
                        cx = cx,
                        cy = cy,
                        w = w,
                        h = h,
                        cnf = 1.0f,
                        cls = cls,
                        clsName = if (cls < classes.size) classes[cls] else "class_$cls"
                    )
                    currentBoxes.add(box)
                } catch (e: Exception) {
                    android.util.Log.e("LabelingActivity", "Error parsing box: $line", e)
                }
            }
        }
    }

    private fun navigateImage(delta: Int) {
        val newIndex = currentIndex + delta
        if (newIndex in 0 until imageFiles.size) {
            displayImage(newIndex)
        }
    }

    private fun deleteCurrentImage() {
        if (imageFiles.isEmpty()) return
        
        pendingDeleteIndex = currentIndex
        val file = imageFiles[currentIndex]
        
        // Find the URI for this file
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DATA} = ?"
        val selectionArgs = arrayOf(file.absolutePath)
        
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                
                // On Android 11+, request user permission to delete
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val deleteRequest = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                    startIntentSenderForResult(deleteRequest.intentSender, REQUEST_DELETE_PERMISSION, null, 0, 0, 0)
                } else {
                    // On older versions, try direct delete
                    val deleted = contentResolver.delete(uri, null, null) > 0
                    handleDeleteResult(deleted)
                }
            } else {
                Toast.makeText(this, "Image not found in MediaStore", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DELETE_PERMISSION) {
            handleDeleteResult(resultCode == RESULT_OK)
        } else if (requestCode == REQUEST_WRITE_PERMISSION) {
            if (resultCode == RESULT_OK) {
                saveBoxesToExif()
                if (pendingWriteBox != null) {
                    Toast.makeText(this, "Assigned: ${pendingWriteBox!!.clsName}", Toast.LENGTH_SHORT).show()
                    pendingWriteBox = null
                }
            } else {
                Toast.makeText(this, "Write permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun handleDeleteResult(success: Boolean) {
        if (success && pendingDeleteIndex >= 0) {
            Toast.makeText(this, "Image deleted", Toast.LENGTH_SHORT).show()
            imageFiles.removeAt(pendingDeleteIndex)
            
            if (imageFiles.isEmpty()) {
                Toast.makeText(this, "No more images", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                val newIndex = if (pendingDeleteIndex >= imageFiles.size) imageFiles.size - 1 else pendingDeleteIndex
                displayImage(newIndex)
            }
        } else {
            Toast.makeText(this, "Delete cancelled or failed", Toast.LENGTH_SHORT).show()
        }
        pendingDeleteIndex = -1
    }
    
    private fun exportDataset() {
        Toast.makeText(this, "Exporting dataset...", Toast.LENGTH_SHORT).show()
        
        Thread {
            try {
                // Create temp directory in cache
                val exportDir = File(cacheDir, "yolo_export_${System.currentTimeMillis()}")
                exportDir.mkdirs()
                
                val imagesDir = File(exportDir, "images")
                val labelsDir = File(exportDir, "labels")
                imagesDir.mkdirs()
                labelsDir.mkdirs()
                
                // Process each image
                imageFiles.forEach { imageFile ->
                    // Copy image
                    val destImage = File(imagesDir, imageFile.name)
                    imageFile.inputStream().use { input ->
                        destImage.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    // Read EXIF and create label file
                    val exif = ExifInterface(imageFile.absolutePath)
                    val boxesStr = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
                    
                    if (boxesStr != null && boxesStr.isNotEmpty()) {
                        val labelFile = File(labelsDir, imageFile.nameWithoutExtension + ".txt")
                        labelFile.writeText(boxesStr)
                    }
                }
                
                // Create coco.yaml
                val cocoYaml = File(exportDir, "coco.yaml")
                val yamlContent = buildString {
                    appendLine("names:")
                    classes.forEachIndexed { index, className ->
                        appendLine("  $index: $className")
                    }
                    appendLine()
                    appendLine("nc: ${classes.size}")
                    appendLine()
                    appendLine("train: images")
                    appendLine("val: images")
                }
                cocoYaml.writeText(yamlContent)
                
                // Create zip file in Downloads
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val zipFile = File(downloadsDir, "FamCamData.zip")
                
                // Delete existing file if present
                if (zipFile.exists()) {
                    zipFile.delete()
                }
                
                java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zip ->
                    exportDir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val relativePath = file.relativeTo(exportDir).path
                            zip.putNextEntry(java.util.zip.ZipEntry(relativePath))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                
                // Clean up temp directory
                exportDir.deleteRecursively()
                
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Export Complete")
                        .setMessage("Dataset exported to:\n\n${zipFile.absolutePath}")
                        .setPositiveButton("OK", null)
                        .show()
                    android.util.Log.d("LabelingActivity", "Exported to: ${zipFile.absolutePath}")
                }
                
            } catch (e: Exception) {
                android.util.Log.e("LabelingActivity", "Export failed", e)
                runOnUiThread {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showClassSelectionDialog(box: BoundingBox) {
        val classNames = classes.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Select Class")
            .setItems(classNames) { _, which ->
                box.cls = which
                box.clsName = classes[which]
                labelOverlay.invalidate()
                pendingWriteBox = box
                requestWritePermissionAndSave()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun requestWritePermissionAndSave() {
        val file = imageFiles[currentIndex]
        
        // Find the URI for this file
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DATA} = ?"
        val selectionArgs = arrayOf(file.absolutePath)
        
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                
                // On Android 11+, request user permission to write
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val writeRequest = MediaStore.createWriteRequest(contentResolver, listOf(uri))
                    startIntentSenderForResult(writeRequest.intentSender, REQUEST_WRITE_PERMISSION, null, 0, 0, 0)
                } else {
                    // On older versions, try direct save
                    saveBoxesToExif()
                }
            }
        }
    }

    private fun saveBoxesToExif() {
        val file = imageFiles[currentIndex]
        try {
            // Find the URI for this file
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = "${MediaStore.Images.Media.DATA} = ?"
            val selectionArgs = arrayOf(file.absolutePath)
            
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    
                    // Open file descriptor to write EXIF
                    contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                        val exif = ExifInterface(pfd.fileDescriptor)
                        
                        // Save in YOLO format: classId cx cy w h (normalized 0-1)
                        val boxesStr = currentBoxes.joinToString("\n") { box ->
                            val cx = box.cx / 640f
                            val cy = box.cy / 640f
                            val w = box.w / 640f
                            val h = box.h / 640f
                            "${box.cls} $cx $cy $w $h"
                        }
                        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, boxesStr)
                        exif.saveAttributes()
                        android.util.Log.d("LabelingActivity", "Saved EXIF: $boxesStr")
                        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("LabelingActivity", "Error saving EXIF", e)
            Toast.makeText(this, "Error saving: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
