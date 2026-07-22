package expo.modules.beautycamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.Promise
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class BeautyCameraNativeView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  private val onReady by EventDispatcher<Unit>()
  private val onError by EventDispatcher<Map<String, Any>>()
  private val onFaceState by EventDispatcher<Map<String, Any>>()
  private val mainExecutor = ContextCompat.getMainExecutor(context)
  private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private val analysisBusy = AtomicBoolean(false)
  private val glView = GLSurfaceView(context)
  private val detector: FaceDetector
  private var mediaPipeLandmarker: FaceLandmarker? = null
  private val renderer: BeautyGLRenderer
  private var cameraProvider: ProcessCameraProvider? = null
  private var camera: Camera? = null
  private var lastAnalysisMs = 0L
  private var lastFaceEvent = false
  private var disposed = false

  var active = true
    set(value) {
      if (field == value) return
      field = value
      if (value) bindCamera() else unbindCamera()
    }
  var facing = "front"
    set(value) {
      val normalized = if (value == "back") "back" else "front"
      if (field == normalized) return
      field = normalized
      renderer.frontFacing = normalized == "front"
      renderer.clearFaceMask()
      emitFaceState(false)
      if (active) bindCamera()
    }
  var beautyEnabled = true
    set(value) {
      field = value
      renderer.beautyEnabled = value
      if (!value) {
        renderer.clearFaceMask()
        emitFaceState(false)
      }
    }
  var smoothingStrength = 0.75f
    set(value) {
      field = value.coerceIn(0f, 1.0f)
      renderer.smoothingStrength = field
    }
  var slimFaceStrength = 0f
    set(value) {
      field = value.coerceIn(0f, 1f)
      renderer.slimFaceStrength = field
    }
  var enlargeEyesStrength = 0f
    set(value) {
      field = value.coerceIn(0f, 1f)
      renderer.enlargeEyesStrength = field
    }
  var noseSlimStrength = 0f
    set(value) {
      field = value.coerceIn(0f, 1f)
      renderer.noseSlimStrength = field
    }
  var enableTorch = false
    set(value) {
      field = value
      updateTorch()
    }

  init {
    try {
      val baseOptions = BaseOptions.builder()
        .setModelAssetPath("face_landmarker.task")
        .build()
      val options = FaceLandmarker.FaceLandmarkerOptions.builder()
        .setBaseOptions(baseOptions)
        .setMinFaceDetectionConfidence(0.45f)
        .setMinFacePresenceConfidence(0.45f)
        .setMinTrackingConfidence(0.45f)
        .setNumFaces(1)
        .setRunningMode(RunningMode.IMAGE)
        .build()
      mediaPipeLandmarker = FaceLandmarker.createFromOptions(context, options)
    } catch (error: Throwable) {
      mediaPipeLandmarker = null
    }

    detector = FaceDetection.getClient(
      FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setMinFaceSize(0.12f)
        .build()
    )
    renderer = BeautyGLRenderer(
      view = glView,
      mainExecutor = mainExecutor,
      cacheDirectory = context.cacheDir,
      onFirstFrame = { if (!disposed) onReady(Unit) },
      onRenderError = { emitError("renderer", it.message ?: "OpenGL beauty renderer failed.") }
    )
    glView.setEGLContextClientVersion(3)
    glView.preserveEGLContextOnPause = true
    glView.setRenderer(renderer)
    glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    addView(glView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    glView.onResume()
    if (active) bindCamera()
  }

  override fun onDetachedFromWindow() {
    glView.onPause()
    unbindCamera()
    super.onDetachedFromWindow()
  }

  fun capture(maxWidth: Int, quality: Double, promise: Promise) {
    if (!active || disposed) {
      promise.reject("camera-inactive", "Camera is not active.", null)
      return
    }
    val accepted = renderer.capture(
      BeautyCaptureOptions(maxWidth.coerceIn(1, 2048), quality.coerceIn(0.1, 1.0)) { result ->
        mainExecutor.execute {
          result.fold(
            onSuccess = promise::resolve,
            onFailure = { promise.reject("capture-failed", it.message ?: "Capture failed.", it) }
          )
        }
      }
    )
    if (!accepted) promise.reject("capture-in-progress", "A capture is already in progress.", null)
  }

  fun dispose() {
    if (disposed) return
    disposed = true
    unbindCamera()
    try {
      mediaPipeLandmarker?.close()
    } catch (_: Throwable) {}
    detector.close()
    analysisExecutor.shutdownNow()
    renderer.release()
  }

  @SuppressLint("MissingPermission")
  private fun bindCamera() {
    if (!active || disposed || !isAttachedToWindow) return
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      emitError("permission-denied", "Camera permission has not been granted.")
      return
    }
    val lifecycleOwner = appContext.currentActivity as? LifecycleOwner
    if (lifecycleOwner == null) {
      emitError("lifecycle-unavailable", "Camera lifecycle is unavailable.")
      return
    }
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
      if (disposed || !active) return@addListener
      try {
        val provider = future.get()
        val rotation = display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder()
          .setTargetRotation(rotation)
          .setResolutionSelector(resolutionSelector(Size(1920, 1080)))
          .build()
        preview.setSurfaceProvider { request ->
          renderer.frontFacing = facing == "front"
          request.setTransformationInfoListener(mainExecutor) {
            renderer.rotationDegrees = it.rotationDegrees
          }
          renderer.attachSurfaceRequest(request)
        }
        val analysis = ImageAnalysis.Builder()
          .setTargetRotation(rotation)
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .setResolutionSelector(resolutionSelector(Size(640, 360)))
          .build()
        analysis.setAnalyzer(analysisExecutor, ::analyze)
        val selector = CameraSelector.Builder().requireLensFacing(
          if (facing == "back") CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        ).build()
        provider.unbindAll()
        camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        cameraProvider = provider
        updateTorch()
      } catch (error: Throwable) {
        emitError("camera-bind", error.message ?: "Could not start camera.")
      }
    }, mainExecutor)
  }

  private fun resolutionSelector(size: Size) = ResolutionSelector.Builder()
    .setResolutionStrategy(
      ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
    ).build()

  private fun unbindCamera() {
    mainExecutor.execute {
      cameraProvider?.unbindAll()
      cameraProvider = null
      camera = null
      analysisBusy.set(false)
      renderer.clearFaceMask()
      emitFaceState(false)
    }
  }

  @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
  private fun analyze(proxy: ImageProxy) {
    val now = SystemClock.elapsedRealtime()
    if (facing != "front" || !beautyEnabled || now - lastAnalysisMs < 100L ||
      !analysisBusy.compareAndSet(false, true)) {
      proxy.close()
      return
    }
    lastAnalysisMs = now

    val mpLandmarker = mediaPipeLandmarker
    if (mpLandmarker != null) {
      try {
        val bitmap = proxy.toBitmap()
        val rotation = proxy.imageInfo.rotationDegrees
        val rotated = if (rotation != 0) {
          val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
          android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
          bitmap
        }
        val mpImage = BitmapImageBuilder(rotated).build()
        val result = mpLandmarker.detect(mpImage)
        val faceLandmarks = result.faceLandmarks()
        if (!faceLandmarks.isNullOrEmpty() && faceLandmarks[0].isNotEmpty()) {
          renderer.updateFaceMask(BeautyFaceMask.fromMediaPipe(faceLandmarks[0], mirrored = true))
          emitFaceState(true)
          analysisBusy.set(false)
          proxy.close()
          return
        }
      } catch (_: Throwable) {
        // Fallback to MLKit below
      }
    }

    val mediaImage = proxy.image
    if (mediaImage == null) {
      analysisBusy.set(false)
      proxy.close()
      return
    }
    val rotation = proxy.imageInfo.rotationDegrees
    val image = InputImage.fromMediaImage(mediaImage, rotation)
    val width = if (rotation == 90 || rotation == 270) proxy.height else proxy.width
    val height = if (rotation == 90 || rotation == 270) proxy.width else proxy.height
    detector.process(image)
      .addOnSuccessListener(analysisExecutor) { faces ->
        val face = selectPrimaryFace(faces, width, height)
        if (face == null) {
          renderer.clearFaceMask()
          emitFaceState(false)
        } else {
          renderer.updateFaceMask(BeautyFaceMask.fromFace(face, width, height, mirrored = true))
          emitFaceState(true)
        }
      }
      .addOnFailureListener(analysisExecutor) {
        renderer.clearFaceMask()
        emitFaceState(false)
        emitError("face-detector", it.message ?: "Face detector failed.")
      }
      .addOnCompleteListener(analysisExecutor) {
        analysisBusy.set(false)
        proxy.close()
      }
  }

  private fun selectPrimaryFace(faces: List<Face>, width: Int, height: Int): Face? {
    if (faces.isEmpty()) return null
    return faces.maxByOrNull { face ->
      val rect = face.boundingBox
      val area = rect.width().toFloat() * rect.height()
      val centerPenalty = (
        abs(rect.exactCenterX() - width / 2f) / width +
          abs(rect.exactCenterY() - height / 2f) / height
        ).coerceAtMost(1f)
      area * (1f - centerPenalty * 0.35f)
    }
  }

  private fun updateTorch() {
    camera?.cameraControl?.enableTorch(enableTorch && facing == "back")
  }

  private fun emitFaceState(detected: Boolean) {
    if (lastFaceEvent == detected) return
    lastFaceEvent = detected
    mainExecutor.execute { if (!disposed) onFaceState(mapOf("detected" to detected)) }
  }

  private fun emitError(code: String, message: String) {
    mainExecutor.execute { if (!disposed) onError(mapOf("code" to code, "message" to message)) }
  }
}
