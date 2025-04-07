package com.google.mediapipe.examples.facelandmarker.fragment

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Camera
import androidx.camera.core.AspectRatio
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.mediapipe.examples.facelandmarker.FaceLandmarkerHelper
import com.google.mediapipe.examples.facelandmarker.YoloHelper
import com.google.mediapipe.examples.facelandmarker.MainViewModel
import com.google.mediapipe.examples.facelandmarker.R
import com.google.mediapipe.examples.facelandmarker.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import java.util.Locale

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock

class CameraFragment : Fragment(), FaceLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "Face Landmarker"
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper
    private lateinit var yoloHelper: YoloHelper                         //YOLO
    private val viewModel: MainViewModel by activityViewModels()
    private val faceBlendshapesResultAdapter by lazy { FaceBlendshapesResultAdapter() }

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    private lateinit var backgroundExecutor: ExecutorService

    // Текущий выбор упражнения
    private var currentExercise = Exercise.CIRCLE

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(R.id.action_camera_to_permissions)
        }
        backgroundExecutor.execute {
            if (faceLandmarkerHelper.isClose()) {
                faceLandmarkerHelper.setupFaceLandmarker()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (this::faceLandmarkerHelper.isInitialized) {
            viewModel.setMaxFaces(faceLandmarkerHelper.maxNumFaces)
            viewModel.setMinFaceDetectionConfidence(faceLandmarkerHelper.minFaceDetectionConfidence)
            viewModel.setMinFaceTrackingConfidence(faceLandmarkerHelper.minFaceTrackingConfidence)
            viewModel.setMinFacePresenceConfidence(faceLandmarkerHelper.minFacePresenceConfidence)
            viewModel.setDelegate(faceLandmarkerHelper.currentDelegate)
            backgroundExecutor.execute { faceLandmarkerHelper.clearFaceLandmarker() }
        }
        if (this::yoloHelper.isInitialized) {
            backgroundExecutor.execute { yoloHelper.close() }
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        with(fragmentCameraBinding.recyclerviewResults) {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = faceBlendshapesResultAdapter
        }

        backgroundExecutor = Executors.newSingleThreadExecutor()

        fragmentCameraBinding.viewFinder.post {
            setUpCamera()
        }

        backgroundExecutor.execute {
            faceLandmarkerHelper = FaceLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minFaceDetectionConfidence = viewModel.currentMinFaceDetectionConfidence,
                minFaceTrackingConfidence = viewModel.currentMinFaceTrackingConfidence,
                minFacePresenceConfidence = viewModel.currentMinFacePresenceConfidence,
                maxNumFaces = viewModel.currentMaxFaces,
                currentDelegate = viewModel.currentDelegate,
                faceLandmarkerHelperListener = this
            )
            yoloHelper = YoloHelper(requireContext(), object : YoloHelper.YoloListener {
                override fun onError(error: String) {
                    Log.e("YoloHelper", error)
                }
                override fun onResults(resultBundle: YoloHelper.YoloResultBundle) {
                    activity?.runOnUiThread {
                        if (_fragmentCameraBinding != null) {
                            fragmentCameraBinding.overlay.setYoloResults(
                                resultBundle.results,
                                resultBundle.inputImageHeight,
                                resultBundle.inputImageWidth
                            )
                            fragmentCameraBinding.overlay.invalidate()
                        }
                    }
                }
            })
        }

        initBottomSheetControls()
    }

    private fun initBottomSheetControls() {
        // Настройка существующих элементов управления
        fragmentCameraBinding.bottomSheetLayout.maxFacesValue.text = viewModel.currentMaxFaces.toString()
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(Locale.US, "%.2f", viewModel.currentMinFaceDetectionConfidence)
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(Locale.US, "%.2f", viewModel.currentMinFaceTrackingConfidence)
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(Locale.US, "%.2f", viewModel.currentMinFacePresenceConfidence)

        fragmentCameraBinding.bottomSheetLayout.detectionThresholdMinus.setOnClickListener {
            if (faceLandmarkerHelper.minFaceDetectionConfidence >= 0.2) {
                faceLandmarkerHelper.minFaceDetectionConfidence -= 0.1f
                updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdPlus.setOnClickListener {
            if (faceLandmarkerHelper.minFaceDetectionConfidence <= 0.8) {
                faceLandmarkerHelper.minFaceDetectionConfidence += 0.1f
                updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdMinus.setOnClickListener {
            if (faceLandmarkerHelper.minFaceTrackingConfidence >= 0.2) {
                faceLandmarkerHelper.minFaceTrackingConfidence -= 0.1f
                updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdPlus.setOnClickListener {
            if (faceLandmarkerHelper.minFaceTrackingConfidence <= 0.8) {
                faceLandmarkerHelper.minFaceTrackingConfidence += 0.1f
                updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdMinus.setOnClickListener {
            if (faceLandmarkerHelper.minFacePresenceConfidence >= 0.2) {
                faceLandmarkerHelper.minFacePresenceConfidence -= 0.1f
                updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdPlus.setOnClickListener {
            if (faceLandmarkerHelper.minFacePresenceConfidence <= 0.8) {
                faceLandmarkerHelper.minFacePresenceConfidence += 0.1f
                updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.maxFacesMinus.setOnClickListener {
            if (faceLandmarkerHelper.maxNumFaces > 1) {
                faceLandmarkerHelper.maxNumFaces--
                updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.maxFacesPlus.setOnClickListener {
            if (faceLandmarkerHelper.maxNumFaces < 2) {
                faceLandmarkerHelper.maxNumFaces++
                updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(viewModel.currentDelegate, false)
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                    try {
                        faceLandmarkerHelper.currentDelegate = p2
                        updateControlsUi()
                    } catch (e: UninitializedPropertyAccessException) {
                        Log.e(TAG, "FaceLandmarkerHelper has not been initialized yet.")
                    }
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }

        // Настройка Spinner для упражнений
        fragmentCameraBinding.bottomSheetLayout.exerciseSpinner.setSelection(currentExercise.ordinal, false)
        fragmentCameraBinding.bottomSheetLayout.exerciseSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                    currentExercise = Exercise.values()[p2]
                    fragmentCameraBinding.overlay.setExercise(currentExercise)
                    fragmentCameraBinding.overlay.invalidate() // Перерисовка OverlayView
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
    }

    private fun updateControlsUi() {
        fragmentCameraBinding.bottomSheetLayout.maxFacesValue.text = faceLandmarkerHelper.maxNumFaces.toString()
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(Locale.US, "%.2f", faceLandmarkerHelper.minFaceDetectionConfidence)
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(Locale.US, "%.2f", faceLandmarkerHelper.minFaceTrackingConfidence)
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(Locale.US, "%.2f", faceLandmarkerHelper.minFacePresenceConfidence)

        backgroundExecutor.execute {
            faceLandmarkerHelper.clearFaceLandmarker()
            faceLandmarkerHelper.setupFaceLandmarker()
        }
        fragmentCameraBinding.overlay.clear()
    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(backgroundExecutor) { image -> detectFace(image) }
            }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

//    private fun detectFace(imageProxy: ImageProxy) {
//        faceLandmarkerHelper.detectLiveStream(
//            imageProxy = imageProxy,
//            isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
//        )
//        //val bitmap = imageProxyToBitmap(imageProxy)
//        //yoloHelper.runInference(bitmap)
//    }
//    private fun detectFace(imageProxy: ImageProxy) {
//        Log.d("Aboba", "Starting detectFace with imageProxy: ${imageProxy.width}x${imageProxy.height}")
//        val bitmap = imageProxyToBitmap(imageProxy)
//        Log.d("Aboba", "Bitmap created: ${bitmap.width}x${bitmap.height}")
//        if (this::faceLandmarkerHelper.isInitialized) {
//            faceLandmarkerHelper.detectLiveStream(
//                imageProxy = imageProxy,
//                isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
//            )
//            Log.d("Aboba", "FaceLandmarkerHelper.detectLiveStream called")
//        } else {
//            Log.w("Aboba", "FaceLandmarkerHelper not initialized yet")
//        }
//        // yoloHelper.runInference(bitmap) // Закомментировано
//    }

    private fun detectFace(imageProxy: ImageProxy) {
            try {
                val bitmap = imageProxyToBitmap(imageProxy)
                Log.d(TAG, "Bitmap created: ${bitmap.width}x${bitmap.height}")
                if (this::faceLandmarkerHelper.isInitialized) {
                    Log.d(TAG, "Calling FaceLandmarkerHelper.detectLiveStream")
                    faceLandmarkerHelper.detectLiveStream(
                        bitmap = bitmap,
                        frameTime = SystemClock.uptimeMillis(),
                        isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
                    )
                    Log.d(TAG, "FaceLandmarkerHelper.detectLiveStream completed")
                } else {
                    Log.w(TAG, "FaceLandmarkerHelper not initialized yet")
                }
                yoloHelper.runInference(bitmap) // Закомментировано
            } catch (e: Exception) {
                Log.e(TAG, "Error in detectFace: ${e.message}", e)
            }
            imageProxy.close() // Закрываем после использования
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = fragmentCameraBinding.viewFinder.display.rotation
    }

    override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                if (fragmentCameraBinding.recyclerviewResults.scrollState != 1) {
                    faceBlendshapesResultAdapter.updateResults(resultBundle.result)
                    faceBlendshapesResultAdapter.notifyDataSetChanged()
                }
                fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
                    String.format("%d ms", resultBundle.inferenceTime)
                fragmentCameraBinding.overlay.setResults(
                    resultBundle.result,
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    RunningMode.LIVE_STREAM
                )
                fragmentCameraBinding.overlay.invalidate()
            }
        }
    }

//    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
//        val buffer = imageProxy.planes[0].buffer
//        val bytes = ByteArray(buffer.remaining())
//        buffer.get(bytes)
//        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
//    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        try {
            Log.d("Gaga", "Converting ImageProxy to Bitmap: ${imageProxy.width}x${imageProxy.height}")
            val bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            val buffer = imageProxy.planes[0].buffer
            Log.d("Gaga", "Buffer size: ${buffer.remaining()} bytes, expected: ${imageProxy.width * imageProxy.height * 4}")
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            Log.d("Gaga", "Pixels copied from buffer")
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (cameraFacing == CameraSelector.LENS_FACING_FRONT) {
                    postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
                }
            }
            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            Log.d("Gaga", "Bitmap rotated and mirrored")
            return rotatedBitmap
        } catch (e: Exception) {
            Log.e("Gaga", "Error converting ImageProxy to Bitmap: ${e.message}", e)
            throw e // Передаём исключение вверх для дальнейшей диагностики
        }
    }

    override fun onEmpty() {
        fragmentCameraBinding.overlay.clear()
        activity?.runOnUiThread {
            faceBlendshapesResultAdapter.updateResults(null)
            faceBlendshapesResultAdapter.notifyDataSetChanged()
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            faceBlendshapesResultAdapter.updateResults(null)
            faceBlendshapesResultAdapter.notifyDataSetChanged()
            if (errorCode == FaceLandmarkerHelper.GPU_ERROR) {
                fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    FaceLandmarkerHelper.DELEGATE_CPU, false
                )
            }
        }
    }


}
