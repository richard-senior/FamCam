package com.yolo.detector

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var etMaxImages: EditText
    private lateinit var etPeriodHours: EditText
    private lateinit var etConfidence: EditText
    private lateinit var etFrameRate: EditText
    private lateinit var etSavePath: EditText
    private lateinit var etClasses: EditText
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button

    companion object {
        const val PREFS_NAME = "YoloDetectorPrefs"
        const val KEY_MAX_IMAGES = "maxImagesPerPeriod"
        const val KEY_PERIOD_HOURS = "periodHours"
        const val KEY_CONFIDENCE = "confidenceThreshold"
        const val KEY_FRAME_RATE = "frameRate"
        const val KEY_SAVE_PATH = "savePath"
        const val KEY_CLASSES = "classes"
        
        // Defaults
        const val DEFAULT_MAX_IMAGES = 64  // 8 per hour for 8 hours
        const val DEFAULT_PERIOD_HOURS = 8
        const val DEFAULT_CONFIDENCE = 70
        const val DEFAULT_FRAME_RATE = 3
        const val DEFAULT_SAVE_PATH = "FamCam"
        const val DEFAULT_CLASSES = "person,cat,dog,richard,donna,ragnar,heath,amber,lena"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etMaxImages = findViewById(R.id.etMaxImages)
        etPeriodHours = findViewById(R.id.etPeriodHours)
        etConfidence = findViewById(R.id.etConfidence)
        etFrameRate = findViewById(R.id.etFrameRate)
        etSavePath = findViewById(R.id.etSavePath)
        etClasses = findViewById(R.id.etClasses)
        btnSave = findViewById(R.id.btnSave)
        btnCancel = findViewById(R.id.btnCancel)

        loadSettings()

        btnSave.setOnClickListener {
            saveSettings()
        }

        btnCancel.setOnClickListener {
            finish()
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        
        etMaxImages.setText(prefs.getInt(KEY_MAX_IMAGES, DEFAULT_MAX_IMAGES).toString())
        etPeriodHours.setText(prefs.getInt(KEY_PERIOD_HOURS, DEFAULT_PERIOD_HOURS).toString())
        etConfidence.setText(prefs.getInt(KEY_CONFIDENCE, DEFAULT_CONFIDENCE).toString())
        etFrameRate.setText(prefs.getInt(KEY_FRAME_RATE, DEFAULT_FRAME_RATE).toString())
        etSavePath.setText(prefs.getString(KEY_SAVE_PATH, DEFAULT_SAVE_PATH))
        etClasses.setText(prefs.getString(KEY_CLASSES, DEFAULT_CLASSES))
    }

    private fun saveSettings() {
        try {
            val maxImages = etMaxImages.text.toString().toIntOrNull() ?: DEFAULT_MAX_IMAGES
            val periodHours = etPeriodHours.text.toString().toIntOrNull() ?: DEFAULT_PERIOD_HOURS
            val confidence = etConfidence.text.toString().toIntOrNull() ?: DEFAULT_CONFIDENCE
            val frameRate = etFrameRate.text.toString().toIntOrNull() ?: DEFAULT_FRAME_RATE
            
            if (maxImages <= 0 || periodHours <= 0 || confidence < 0 || confidence > 100 || frameRate <= 0) {
                Toast.makeText(this, "Invalid values", Toast.LENGTH_SHORT).show()
                return
            }

            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().apply {
                putInt(KEY_MAX_IMAGES, maxImages)
                putInt(KEY_PERIOD_HOURS, periodHours)
                putInt(KEY_CONFIDENCE, confidence)
                putInt(KEY_FRAME_RATE, frameRate)
                putString(KEY_SAVE_PATH, etSavePath.text.toString())
                putString(KEY_CLASSES, etClasses.text.toString())
                apply()
            }

            Toast.makeText(this, "Settings saved. Restart app to apply.", Toast.LENGTH_LONG).show()
            finish()
            
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
