package com.google.mediapipe.examples.facelandmarker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.examples.facelandmarker.fragment.Exercise
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results: FaceLandmarkerResult? = null
    private var linePaint = Paint()  // Для текущего контура губ
    private var pointPaint = Paint() // Для точек губ
    private var maskPaint = Paint()  // Для эталонного круга
    private var textPaint = Paint()  // Для текста
    private var overlayPaint = Paint() // Для эффекта


    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var allIsCorrect: Boolean = false
    private var currentExercise = Exercise.SMILE

    init {
        initPaints()
    }

    fun clear() {
        results = null
        linePaint.reset()
        pointPaint.reset()
        maskPaint.reset()
        textPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        linePaint.color = ContextCompat.getColor(context!!, R.color.mp_color_primary)
        linePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        linePaint.style = Paint.Style.STROKE

        pointPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        pointPaint.style = Paint.Style.FILL

        maskPaint.color = Color.YELLOW
        maskPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        maskPaint.style = Paint.Style.STROKE

        overlayPaint.color = Color.argb(50, 152, 251, 152)
        overlayPaint.style = Paint.Style.FILL

        textPaint.textSize = 50f
        textPaint.style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (allIsCorrect){
            canvas.drawPaint(overlayPaint)
        }


        if (results?.faceLandmarks().isNullOrEmpty()) {
            clear()
            return
        }

        results?.let { faceLandmarkerResult ->
            val scaledImageWidth = imageWidth * scaleFactor
            val scaledImageHeight = imageHeight * scaleFactor
            val offsetX = (width - scaledImageWidth) / 2f
            val offsetY = (height - scaledImageHeight) / 2f

            faceLandmarkerResult.faceLandmarks().forEach { faceLandmarks ->
                drawReferenceCircle(canvas, faceLandmarks, offsetX, offsetY)
                drawFaceLandmarks(canvas, faceLandmarks, offsetX, offsetY)
            }
        }
    }

    // Отрисовка эталонного круга
    private fun drawReferenceCircle(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        val leftCorner = faceLandmarks[61]  // Левый угол рта
        val rightCorner = faceLandmarks[291] // Правый угол рта
        val upCorner = faceLandmarks[13]    // Верхняя точка рта
        val downCorner = faceLandmarks[14]  // Нижняя точка рта

        // Центр круга — среднее положение губ
        val centerX = (leftCorner.x() + rightCorner.x()) / 2 * imageWidth * scaleFactor + offsetX
        val centerY = (upCorner.y() + downCorner.y()) / 2 * imageHeight * scaleFactor + offsetY
        val radius = 60f

        canvas.drawCircle(centerX, centerY, radius, maskPaint)
    }

    // Отрисовка текущих точек губ и проверка
    private fun drawFaceLandmarks(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        val lipPoints = listOf(61, 291, 13, 14) // Левый, правый, верхний, нижний углы рта
        val coordinates = mutableListOf<Pair<Float, Float>>()

        // Собираем координаты точек губ
        lipPoints.forEach { id ->
            val landmark = faceLandmarks[id]
            val x = landmark.x() * imageWidth * scaleFactor + offsetX
            val y = landmark.y() * imageHeight * scaleFactor + offsetY
            coordinates.add(Pair(x, y))
        }

        // Вычисляем центр круга
        val centerX = coordinates.map { it.first }.average().toFloat()
        val centerY = coordinates.map { it.second }.average().toFloat()
        val radius = 60f // Тот же радиус, что у эталонного круга

        // Проверяем расстояние каждой точки до центра
        var isCorrect = true
        coordinates.forEach { (x, y) ->
            val distanceToCenter = sqrt((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY))
            val deviation = abs(distanceToCenter - radius)
            if (deviation > 15f) { // Допустимое отклонение
                isCorrect = false
                pointPaint.color = Color.RED
            } else {
                pointPaint.color = Color.GREEN
            }
            canvas.drawCircle(x, y, 5f, pointPaint) // Рисуем точки

            if (isCorrect)
                allIsCorrect = true
            else
                allIsCorrect = false
        }

    }

    fun setResults(
        faceLandmarkerResults: FaceLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.IMAGE
    ) {
        results = faceLandmarkerResults
        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        scaleFactor = when (runningMode) {
            RunningMode.IMAGE, RunningMode.VIDEO -> min(width * 1f / imageWidth, height * 1f / imageHeight)
            RunningMode.LIVE_STREAM -> max(width * 1f / imageWidth, height * 1f / imageHeight)
        }
        invalidate()
    }

    fun setExercise(exercise: Exercise) {
        currentExercise = exercise
        Log.d("OverlayView", "Current exercise set to: $currentExercise")
        invalidate()
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 8F
    }
}