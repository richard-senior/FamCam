package com.yolo.detector

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class OnnxDetector(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String,
    private val confidenceThreshold: Float = 0.3f,
    private val iouThreshold: Float = 0.5f
) : Detector {
    private var session: OrtSession? = null
    private val labels = mutableListOf<String>()
    private val inputSize = 320

    init {
        setupSession()
        loadLabels()
    }

    private fun setupSession() {
        val env = OrtEnvironment.getEnvironment()
        val modelBytes = context.assets.open(modelPath).readBytes()
        session = env.createSession(modelBytes)
    }

    private fun loadLabels() {
        val reader = BufferedReader(InputStreamReader(context.assets.open(labelPath)))
        var line = reader.readLine()
        while (line != null && line.isNotEmpty()) {
            labels.add(line)
            line = reader.readLine()
        }
        reader.close()
    }

    override fun detect(bitmap: Bitmap): List<BoundingBox> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, false)
        val inputBuffer = preprocessImage(resized)
        
        val inputName = session!!.inputNames.iterator().next()
        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        val tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), inputBuffer, shape)
        
        val output = session!!.run(mapOf(inputName to tensor))
        @Suppress("UNCHECKED_CAST")
        val outputTensor = output[0].value as Array<*>
        @Suppress("UNCHECKED_CAST")
        val outputArray = (outputTensor[0] as Array<FloatArray>)
        
        output.close()
        tensor.close()
        
        return postProcess(outputArray)
    }

    private fun preprocessImage(bitmap: Bitmap): FloatBuffer {
        val buffer = FloatBuffer.allocate(3 * inputSize * inputSize)
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        
        for (i in pixels.indices) {
            val pixel = pixels[i]
            buffer.put(i, ((pixel shr 16) and 0xFF) / 255f)
            buffer.put(inputSize * inputSize + i, ((pixel shr 8) and 0xFF) / 255f)
            buffer.put(2 * inputSize * inputSize + i, (pixel and 0xFF) / 255f)
        }
        
        return buffer
    }

    private fun postProcess(output: Array<FloatArray>): List<BoundingBox> {
        val boxes = mutableListOf<BoundingBox>()
        val numDetections = output[0].size
        
        for (i in 0 until numDetections) {
            var maxConf = -1f
            var maxIdx = -1
            
            for (j in 4 until output.size) {
                if (output[j][i] > maxConf) {
                    maxConf = output[j][i]
                    maxIdx = j - 4
                }
            }
            
            if (maxConf > confidenceThreshold) {
                val cx = output[0][i] / inputSize
                val cy = output[1][i] / inputSize
                val w = output[2][i] / inputSize
                val h = output[3][i] / inputSize
                val x1 = cx - w / 2f
                val y1 = cy - h / 2f
                val x2 = cx + w / 2f
                val y2 = cy + h / 2f
                
                if (x1 in 0f..1f && y1 in 0f..1f && x2 in 0f..1f && y2 in 0f..1f) {
                    boxes.add(BoundingBox(
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        cx = cx, cy = cy, w = w, h = h,
                        cnf = maxConf,
                        cls = maxIdx,
                        clsName = if (maxIdx < labels.size) labels[maxIdx] else "Unknown"
                    ))
                }
            }
        }
        
        return applyNMS(boxes)
    }

    private fun applyNMS(boxes: List<BoundingBox>): List<BoundingBox> {
        val sorted = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selected = mutableListOf<BoundingBox>()
        
        while (sorted.isNotEmpty()) {
            val first = sorted.removeAt(0)
            selected.add(first)
            sorted.removeAll { calculateIoU(first, it) >= iouThreshold }
        }
        
        return selected
    }

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = max(box1.x1, box2.x1)
        val y1 = max(box1.y1, box2.y1)
        val x2 = min(box1.x2, box2.x2)
        val y2 = min(box1.y2, box2.y2)
        val intersection = max(0f, x2 - x1) * max(0f, y2 - y1)
        val union = box1.w * box1.h + box2.w * box2.h - intersection
        return intersection / union
    }

    override fun close() {
        session?.close()
    }
}
