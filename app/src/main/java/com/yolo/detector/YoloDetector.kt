package com.yolo.detector

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.max
import kotlin.math.min

class YoloDetector(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String,
    private val confidenceThreshold: Float = 0.3f,
    private val iouThreshold: Float = 0.5f
) : Detector {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numChannel = 0
    private var numElements = 0
    private val labels = mutableListOf<String>()
    
    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(0f, 255f))
        .add(CastOp(DataType.FLOAT32))
        .build()

    init {
        setupInterpreter()
        loadLabels()
    }

    private fun setupInterpreter() {
        val model = FileUtil.loadMappedFile(context, modelPath)
        
        // Try GPU (Tensor chip on Pixel 6a) first
        try {
            gpuDelegate = GpuDelegate()
            val options = Interpreter.Options().apply {
                addDelegate(gpuDelegate)
                numThreads = 4
            }
            interpreter = Interpreter(model, options)
            android.util.Log.d("YOLO", "✓ GPU/Tensor acceleration enabled")
        } catch (e: Exception) {
            android.util.Log.w("YOLO", "GPU delegate failed, falling back to CPU: ${e.message}")
            gpuDelegate?.close()
            gpuDelegate = null
            
            // Fallback to CPU
            val options = Interpreter.Options().apply {
                numThreads = 4
            }
            interpreter = Interpreter(model, options)
            android.util.Log.d("YOLO", "✓ CPU inference enabled")
        }
        
        val inputShape = interpreter!!.getInputTensor(0).shape()
        val outputShape = interpreter!!.getOutputTensor(0).shape()
        
        tensorWidth = inputShape[1]
        tensorHeight = inputShape[2]
        numChannel = outputShape[1]
        numElements = outputShape[2]
    }

    private fun loadLabels() {
        try {
            val reader = BufferedReader(InputStreamReader(context.assets.open(labelPath)))
            var line = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                labels.add(line)
                line = reader.readLine()
            }
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun detect(bitmap: Bitmap): List<BoundingBox> {
        val resized = Bitmap.createScaledBitmap(bitmap, tensorWidth, tensorHeight, false)
        val tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(resized)
        val processedImage = imageProcessor.process(tensorImage)
        
        val output = TensorBuffer.createFixedSize(
            intArrayOf(1, numChannel, numElements),
            DataType.FLOAT32
        )
        
        interpreter?.run(processedImage.buffer, output.buffer)
        
        return bestBox(output.floatArray) ?: emptyList()
    }

    private fun bestBox(array: FloatArray): List<BoundingBox>? {
        val boundingBoxes = mutableListOf<BoundingBox>()
        var globalMaxConf = 0f

        for (c in 0 until numElements) {
            var maxConf = -1.0f
            var maxIdx = -1
            var j = 4
            var arrayIdx = c + numElements * j
            
            while (j < numChannel) {
                if (array[arrayIdx] > maxConf) {
                    maxConf = array[arrayIdx]
                    maxIdx = j - 4
                }
                j++
                arrayIdx += numElements
            }
            
            if (maxConf > globalMaxConf) globalMaxConf = maxConf

            if (maxConf > confidenceThreshold) {
                val clsName = if (maxIdx < labels.size) labels[maxIdx] else "Unknown"
                val cx = array[c] / tensorWidth
                val cy = array[c + numElements] / tensorHeight
                val w = array[c + numElements * 2] / tensorWidth
                val h = array[c + numElements * 3] / tensorHeight
                val x1 = cx - (w / 2f)
                val y1 = cy - (h / 2f)
                val x2 = cx + (w / 2f)
                val y2 = cy + (h / 2f)

                boundingBoxes.add(
                    BoundingBox(
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        cx = cx, cy = cy, w = w, h = h,
                        cnf = maxConf, cls = maxIdx, clsName = clsName
                    )
                )
            }
        }

        if (boundingBoxes.isEmpty()) {
            return null
        }
        return applyNMS(boundingBoxes)
    }

    private fun applyNMS(boxes: List<BoundingBox>): List<BoundingBox> {
        val sortedBoxes = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selectedBoxes = mutableListOf<BoundingBox>()

        while (sortedBoxes.isNotEmpty()) {
            val first = sortedBoxes.first()
            selectedBoxes.add(first)
            sortedBoxes.remove(first)

            val iterator = sortedBoxes.iterator()
            while (iterator.hasNext()) {
                val nextBox = iterator.next()
                val iou = calculateIoU(first, nextBox)
                if (iou >= iouThreshold) {
                    iterator.remove()
                }
            }
        }

        return selectedBoxes
    }

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = max(box1.x1, box2.x1)
        val y1 = max(box1.y1, box2.y1)
        val x2 = min(box1.x2, box2.x2)
        val y2 = min(box1.y2, box2.y2)
        val intersectionArea = max(0f, x2 - x1) * max(0f, y2 - y1)
        val box1Area = box1.w * box1.h
        val box2Area = box2.w * box2.h
        return intersectionArea / (box1Area + box2Area - intersectionArea)
    }

    override fun close() {
        interpreter?.close()
        gpuDelegate?.close()
    }
}
