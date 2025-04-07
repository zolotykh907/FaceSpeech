package com.google.mediapipe.examples.facelandmarker

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class YoloHelper(
    val context: Context,
    val yoloListener: YoloListener? = null
) {

    private var interpreter: Interpreter? = null
    private val inputSize = 256 // Измените на 640, если ваша модель YOLO11 ожидает 640x640

    init {
        setupYoloModel()
    }

    fun setupYoloModel() {
        try {
            val tfliteModel = loadModelFile(context, "best_float32.tflite")
            interpreter = Interpreter(tfliteModel)
            Log.d(TAG, "YOLO model successfully initialized")
        } catch (e: Exception) {
            yoloListener?.onError("YOLO model failed to initialize: ${e.message}")
            Log.e(TAG, "Failed to load YOLO model", e)
        }
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val intValues = IntArray(inputSize * inputSize)
        scaledBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)
        var pixel = 0
        for (i in intValues.indices) {
            val value = intValues[pixel++]
            inputBuffer.putFloat(((value shr 16) and 0xFF) / 255.0f) // R
            inputBuffer.putFloat(((value shr 8) and 0xFF) / 255.0f)  // G
            inputBuffer.putFloat((value and 0xFF) / 255.0f)         // B
        }
        inputBuffer.rewind()
        return inputBuffer
    }

    fun runInference(bitmap: Bitmap): List<YoloHelper.YoloResult> {
        Log.d(TAG, "Starting YOLO inference with bitmap: ${bitmap.width}x${bitmap.height}")
        val startTime = SystemClock.uptimeMillis()
        val input = preprocessBitmap(bitmap)
        Log.d(TAG, "Input buffer prepared, capacity: ${input.capacity()}")
        val output = Array(1) { Array(5) { FloatArray(1344) } } // Убедитесь, что размер соответствует вашей модели
        interpreter?.run(input, output) ?: run {
            yoloListener?.onError("YOLO interpreter not initialized")
            Log.e(TAG, "YOLO interpreter is null")
            return emptyList()
        }
        Log.d(TAG, "YOLO inference completed, output dimensions: ${output.size}, ${output[0].size}, ${output[0][0].size}")
        val results = processYoloOutput(output)
        val inferenceTime = SystemClock.uptimeMillis() - startTime
        Log.d("TONGUE", "YOLO found ${results.size} objects in ${inferenceTime}ms")
        yoloListener?.onResults(YoloHelper.YoloResultBundle(results, inferenceTime, bitmap.height, bitmap.width))
        return results
    }

    private fun processYoloOutput(output: Array<Array<FloatArray>>): List<YoloResult> {
        val results = mutableListOf<YoloResult>()
        val numDetections = output[0][0].size // Динамически определяем размер
        val outputData = output[0] // [5, numDetections]

        Log.d(TAG, "Processing YOLO output: ${output.size}x${output[0].size}x$numDetections")

        for (i in 0 until numDetections) {
            val confidence = outputData[4][i]
            if (confidence > 0.25) { // Порог уверенности
                val xCenter = outputData[0][i]
                val yCenter = outputData[1][i]
                val width = outputData[2][i]
                val height = outputData[3][i]
                val xMin = xCenter - width / 2
                val yMin = yCenter - height / 2
                val xMax = xCenter + width / 2
                val yMax = yCenter + height / 2
                results.add(YoloResult(xMin, yMin, xMax, yMax, confidence))
                Log.d(TAG, "Detected object: confidence=$confidence, box=[$xMin, $yMin, $xMax, $yMax]")
            }
        }
        return results
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    data class YoloResult(
        val xMin: Float,
        val yMin: Float,
        val xMax: Float,
        val yMax: Float,
        val confidence: Float
    )

    data class YoloResultBundle(
        val results: List<YoloResult>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int
    )

    interface YoloListener {
        fun onError(error: String)
        fun onResults(resultBundle: YoloResultBundle)
    }

    companion object {
        const val TAG = "YoloHelper"
    }
}