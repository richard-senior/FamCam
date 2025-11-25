package com.yolo.detector

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class QuitActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Stop foreground service
        DetectionService.stop(this)
        
        // Bring MainActivity to front and quit from there
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("QUIT_APP", true)
        }
        startActivity(intent)
        finish()
    }
}
