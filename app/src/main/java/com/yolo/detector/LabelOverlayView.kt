package com.yolo.detector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class LabelOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        style = Paint.Style.FILL
    }

    private var boxes = listOf<BoundingBox>()
    private var onBoxClickListener: ((BoundingBox) -> Unit)? = null

    fun setBoxes(boxes: List<BoundingBox>) {
        this.boxes = boxes
        invalidate()
    }

    fun setOnBoxClickListener(listener: (BoundingBox) -> Unit) {
        this.onBoxClickListener = listener
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (boxes.isEmpty()) return

        // Calculate actual image bounds within the view (fitCenter scaling)
        // Images are 640x640, but displayed with fitCenter
        val imageSize = 640f
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        
        // Calculate scale to fit (maintaining aspect ratio)
        val scale = minOf(viewWidth / imageSize, viewHeight / imageSize)
        
        // Calculate actual displayed image size
        val displayedWidth = imageSize * scale
        val displayedHeight = imageSize * scale
        
        // Calculate offset to center the image
        val offsetX = (viewWidth - displayedWidth) / 2f
        val offsetY = (viewHeight - displayedHeight) / 2f

        boxes.forEach { box ->
            // Scale coordinates from 640x640 to displayed size
            val left = box.x1 * scale + offsetX
            val top = box.y1 * scale + offsetY
            val right = box.x2 * scale + offsetX
            val bottom = box.y2 * scale + offsetY

            // Draw box
            paint.color = getColorForClass(box.cls)
            canvas.drawRect(left, top, right, bottom, paint)

            // Draw label background
            val label = "${box.clsName} (${(box.cnf * 100).toInt()}%)"
            val textBounds = android.graphics.Rect()
            textPaint.getTextBounds(label, 0, label.length, textBounds)
            
            val bgPaint = Paint().apply {
                color = android.graphics.Color.BLACK
                alpha = 180
                style = Paint.Style.FILL
            }
            canvas.drawRect(
                left, 
                top - textBounds.height() - 20, 
                left + textBounds.width() + 20, 
                top, 
                bgPaint
            )
            
            // Draw label text
            canvas.drawText(label, left + 10, top - 10, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val touchX = event.x
            val touchY = event.y

            // Calculate actual image bounds (same as onDraw)
            val imageSize = 640f
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            
            val scale = minOf(viewWidth / imageSize, viewHeight / imageSize)
            val displayedWidth = imageSize * scale
            val displayedHeight = imageSize * scale
            val offsetX = (viewWidth - displayedWidth) / 2f
            val offsetY = (viewHeight - displayedHeight) / 2f

            // Find touched box
            boxes.forEach { box ->
                val left = box.x1 * scale + offsetX
                val top = box.y1 * scale + offsetY
                val right = box.x2 * scale + offsetX
                val bottom = box.y2 * scale + offsetY

                if (touchX >= left && touchX <= right && touchY >= top && touchY <= bottom) {
                    onBoxClickListener?.invoke(box)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getColorForClass(cls: Int): Int {
        val colors = listOf(
            Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW,
            Color.CYAN, Color.MAGENTA, Color.WHITE
        )
        return colors[cls % colors.size]
    }
}
