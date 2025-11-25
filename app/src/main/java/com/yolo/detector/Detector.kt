package com.yolo.detector

import android.graphics.Bitmap

interface Detector {
    fun detect(bitmap: Bitmap): List<BoundingBox>
    fun close()
}
