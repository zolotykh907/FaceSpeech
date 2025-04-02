package com.google.mediapipe.examples.facelandmarker
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    private var results: FaceLandmarkerResult? = null
    private var linePaint = Paint()
    private var pointPaint = Paint()
    private var circlePaint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    init {
        initPaints()
    }

    fun clear() {
        results = null
        linePaint.reset()
        pointPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        linePaint.color =
            ContextCompat.getColor(context!!, R.color.mp_color_primary)
        linePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        linePaint.style = Paint.Style.STROKE

        pointPaint.color = Color.YELLOW
        pointPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        pointPaint.style = Paint.Style.FILL

        circlePaint.color = Color.RED
        circlePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        circlePaint.style = Paint.Style.STROKE
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // Clear previous drawings if results exist but have no face landmarks
        if (results?.faceLandmarks().isNullOrEmpty()) {
            clear()
            return
        }

        results?.let { faceLandmarkerResult ->

            // Calculate scaled image dimensions
            val scaledImageWidth = imageWidth * scaleFactor
            val scaledImageHeight = imageHeight * scaleFactor

            // Calculate offsets to center the image on the canvas
            val offsetX = (width - scaledImageWidth) / 2f
            val offsetY = (height - scaledImageHeight) / 2f

            // Iterate through each detected face
            faceLandmarkerResult.faceLandmarks().forEach { faceLandmarks ->
                // Draw all landmarks for the current face
                drawFaceLandmarks(canvas, faceLandmarks, offsetX, offsetY)

                // Draw all connectors for the current face
                //drawFaceConnectors(canvas, faceLandmarks, offsetX, offsetY)
            }
        }
    }

    /**
     * Draws all landmarks for a single face on the canvas.
     */
//    private fun drawFaceLandmarks(
//        canvas: Canvas,
//        faceLandmarks: List<NormalizedLandmark>,
//        offsetX: Float,
//        offsetY: Float
//    ) {
//        val points_for_draw = setOf(13, 14, 61, 291)
//        faceLandmarks.forEachIndexed {id, landmark ->
//            if (id in points_for_draw) {
//                val x = landmark.x() * imageWidth * scaleFactor + offsetX
//                val y = landmark.y() * imageHeight * scaleFactor + offsetY
//                //Log.d("Точка №$id: ($x, $y)")
//                canvas.drawPoint(x, y, pointPaint)
//            }
//        }
//    }

    private fun drawFaceLandmarks(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        val pointsForDraw = setOf(13, 14, 61, 291)
        val selectedLandmarks = mutableListOf<Pair<Float, Float>>()

        // Собираем координаты выбранных точек
        faceLandmarks.forEachIndexed { id, landmark ->
            if (id in pointsForDraw) {
                val x = landmark.x() * imageWidth * scaleFactor + offsetX
                val y = landmark.y() * imageHeight * scaleFactor + offsetY
                selectedLandmarks.add(Pair(x, y))
                canvas.drawPoint(x, y, pointPaint) // Рисуем точки для наглядности
            }
        }

        // Если собраны все 4 точки, рисуем эллипс
        if (selectedLandmarks.size == 4) {
            // Находим минимальные и максимальные координаты
            val minX = selectedLandmarks.minOf { it.first }
            val maxX = selectedLandmarks.maxOf { it.first }
            val minY = selectedLandmarks.minOf { it.second }
            val maxY = selectedLandmarks.maxOf { it.second }

            // Рисуем эллипс, который охватывает все точки
            canvas.drawOval(minX, minY, maxX, maxY, circlePaint)
        }
    }

    /**
     * Draws all the connectors between landmarks for a single face on the canvas.
     */
    private fun drawFaceConnectors(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        FaceLandmarker.FACE_LANDMARKS_CONNECTORS.filterNotNull().forEach { connector ->
            val startLandmark = faceLandmarks.getOrNull(connector.start())
            val endLandmark = faceLandmarks.getOrNull(connector.end())

            if (startLandmark != null && endLandmark != null) {
                val startX = startLandmark.x() * imageWidth * scaleFactor + offsetX
                val startY = startLandmark.y() * imageHeight * scaleFactor + offsetY
                val endX = endLandmark.x() * imageWidth * scaleFactor + offsetX
                val endY = endLandmark.y() * imageHeight * scaleFactor + offsetY

                canvas.drawLine(startX, startY, endX, endY, linePaint)
            }
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
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                min(width * 1f / imageWidth, height * 1f / imageHeight)
            }
            RunningMode.LIVE_STREAM -> {
                // PreviewView is in FILL_START mode. So we need to scale up the
                // landmarks to match with the size that the captured images will be
                // displayed.
                max(width * 1f / imageWidth, height * 1f / imageHeight)
            }
        }
        invalidate()
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 8F
        private const val TAG = "Face Landmarker Overlay"
    }
}
