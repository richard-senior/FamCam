package com.yolo.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.renderscript.*
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

class YuvToRgbConverter(context: Context) {
    private val rs = RenderScript.create(context)
    private val scriptYuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
    
    private var pixelCount: Int = -1
    private lateinit var yuvBuffer: ByteBuffer
    private lateinit var inputAllocation: Allocation
    private lateinit var outputAllocation: Allocation

    @Synchronized
    fun yuvToRgb(image: ImageProxy, output: Bitmap) {
        if (image.format != ImageFormat.YUV_420_888) {
            throw IllegalArgumentException("Invalid image format")
        }

        val imageWidth = image.width
        val imageHeight = image.height

        if (pixelCount != imageWidth * imageHeight) {
            pixelCount = imageWidth * imageHeight
            yuvBuffer = ByteBuffer.allocateDirect(pixelCount * 3 / 2)
            
            inputAllocation = Allocation.createSized(rs, Element.U8(rs), yuvBuffer.capacity())
            outputAllocation = Allocation.createFromBitmap(rs, output)
        }

        yuvBuffer.rewind()
        imageToByteBuffer(image, yuvBuffer)

        inputAllocation.copyFrom(yuvBuffer.array())
        scriptYuvToRgb.setInput(inputAllocation)
        scriptYuvToRgb.forEach(outputAllocation)
        outputAllocation.copyTo(output)
    }

    private fun imageToByteBuffer(image: ImageProxy, buffer: ByteBuffer) {
        val imageWidth = image.width
        val imageHeight = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        var pos = 0

        // Y plane
        for (row in 0 until imageHeight) {
            val yPos = row * yRowStride
            yBuffer.position(yPos)
            yBuffer.get(buffer.array(), pos, imageWidth)
            pos += imageWidth
        }

        // U and V planes
        val uvHeight = imageHeight / 2
        val uvWidth = imageWidth / 2

        for (row in 0 until uvHeight) {
            val uvPos = row * uvRowStride
            for (col in 0 until uvWidth) {
                val bufferIndex = uvPos + (col * uvPixelStride)
                buffer.array()[pos++] = vBuffer.get(bufferIndex)
                buffer.array()[pos++] = uBuffer.get(bufferIndex)
            }
        }
    }
    
    fun close() {
        if (::inputAllocation.isInitialized) {
            inputAllocation.destroy()
        }
        if (::outputAllocation.isInitialized) {
            outputAllocation.destroy()
        }
        scriptYuvToRgb.destroy()
        rs.destroy()
    }
}
