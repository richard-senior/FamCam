package com.yolo.detector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    
    private var boxes = listOf<BoundingBox>()
    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        style = Paint.Style.FILL
    }
    private val textBgPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    fun setResults(boxes: List<BoundingBox>) {
        this.boxes = boxes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        for (box in boxes) {
            val left = box.x1 * width
            val top = box.y1 * height
            val right = box.x2 * width
            val bottom = box.y2 * height
            
            canvas.drawRect(left, top, right, bottom, boxPaint)
            
            val label = "${box.clsName} ${(box.cnf * 100).toInt()}%"
            val textBounds = android.graphics.Rect()
            textPaint.getTextBounds(label, 0, label.length, textBounds)
            
            canvas.drawRect(
                left,
                top - textBounds.height() - 8f,
                left + textBounds.width() + 8f,
                top,
                textBgPaint
            )
            canvas.drawText(label, left + 4f, top - 4f, textPaint)
        }
    }
}
