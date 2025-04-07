package com.google.mediapipe.examples.facelandmarker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class BestFloat32Model(context: Context) {

    private lateinit var interpreter: Interpreter

    init {
        try {
            val tfliteModel = loadModelFile(context, "best_float32.tflite")
            interpreter = Interpreter(tfliteModel)
            Log.d("YOLO", "Модель успешно загружена!")
        } catch (e: Exception) {
            Log.d("YOLO", "Ошибка загрузки модели", e)
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
        val inputBuffer = ByteBuffer.allocateDirect(1 * 256 * 256 * 3 * 4) // float32 = 4 байта
        inputBuffer.order(ByteOrder.nativeOrder())

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 256, 256, true)

        for (y in 0 until 256) {
            for (x in 0 until 256) {
                val pixel = scaledBitmap.getPixel(x, y)
                val r = Color.red(pixel) / 255.0f
                val g = Color.green(pixel) / 255.0f
                val b = Color.blue(pixel) / 255.0f
                inputBuffer.putFloat(r)
                inputBuffer.putFloat(g)
                inputBuffer.putFloat(b)
            }
        }

        inputBuffer.rewind()
        return inputBuffer
    }

    fun runInference(bitmap: Bitmap): Array<Array<FloatArray>> {
        val input = preprocessBitmap(bitmap)

        // Выходной массив: [1, 5, 1344]
        val output = Array(1) { Array(5) { FloatArray(1344) } }

        interpreter.run(input, output)
        return output
    }

    fun close() {
        interpreter.close()
    }
}
