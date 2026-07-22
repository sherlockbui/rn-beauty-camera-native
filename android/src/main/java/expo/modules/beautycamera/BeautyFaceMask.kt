package expo.modules.beautycamera

import android.graphics.PointF
import android.graphics.Rect
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import kotlin.math.max
import kotlin.math.min

data class NormalizedRect(
  val x: Float = 0f,
  val y: Float = 0f,
  val width: Float = 0f,
  val height: Float = 0f
) {
  val detected: Boolean get() = width > 0f && height > 0f

  fun expanded(xFactor: Float, yFactor: Float): NormalizedRect {
    val newWidth = min(1f, width * xFactor)
    val newHeight = min(1f, height * yFactor)
    val newX = (x - (newWidth - width) / 2f).coerceIn(0f, 1f - newWidth)
    val newY = (y - (newHeight - height) / 2f).coerceIn(0f, 1f - newHeight)
    return NormalizedRect(newX, newY, newWidth, newHeight)
  }

  fun mirrored(): NormalizedRect = copy(x = 1f - x - width)

  fun interpolate(next: NormalizedRect, alpha: Float): NormalizedRect = NormalizedRect(
    x + (next.x - x) * alpha,
    y + (next.y - y) * alpha,
    width + (next.width - width) * alpha,
    height + (next.height - height) * alpha
  )
}

data class BeautyFaceMask(
  val face: NormalizedRect = NormalizedRect(),
  val leftEye: NormalizedRect = NormalizedRect(),
  val rightEye: NormalizedRect = NormalizedRect(),
  val leftBrow: NormalizedRect = NormalizedRect(),
  val rightBrow: NormalizedRect = NormalizedRect(),
  val nose: NormalizedRect = NormalizedRect(),
  val mouth: NormalizedRect = NormalizedRect(),
  val timestampMs: Long = 0L
) {
  val detected: Boolean get() = face.detected

  fun mirrored(): BeautyFaceMask = copy(
    face = face.mirrored(),
    leftEye = leftEye.mirrored(),
    rightEye = rightEye.mirrored(),
    leftBrow = leftBrow.mirrored(),
    rightBrow = rightBrow.mirrored(),
    nose = nose.mirrored(),
    mouth = mouth.mirrored()
  )

  fun stabilized(next: BeautyFaceMask, alpha: Float = 0.38f): BeautyFaceMask {
    if (!detected || !next.detected) return next
    return BeautyFaceMask(
      face.interpolate(next.face, alpha),
      leftEye.interpolate(next.leftEye, alpha),
      rightEye.interpolate(next.rightEye, alpha),
      leftBrow.interpolate(next.leftBrow, alpha),
      rightBrow.interpolate(next.rightBrow, alpha),
      nose.interpolate(next.nose, alpha),
      mouth.interpolate(next.mouth, alpha),
      next.timestampMs
    )
  }

  companion object {
    fun fromFace(face: Face, imageWidth: Int, imageHeight: Int, mirrored: Boolean): BeautyFaceMask {
      val faceRect = rectFromPoints(
        face.getContour(FaceContour.FACE)?.points,
        face.boundingBox,
        imageWidth,
        imageHeight
      ).expanded(0.94f, 0.96f)
      val fallback = fallbackFeatures(faceRect)
      val result = BeautyFaceMask(
        face = faceRect,
        leftEye = contourRect(face, intArrayOf(FaceContour.LEFT_EYE), fallback.leftEye, imageWidth, imageHeight, 1.08f, 1.12f),
        rightEye = contourRect(face, intArrayOf(FaceContour.RIGHT_EYE), fallback.rightEye, imageWidth, imageHeight, 1.08f, 1.12f),
        leftBrow = contourRect(face, intArrayOf(FaceContour.LEFT_EYEBROW_TOP, FaceContour.LEFT_EYEBROW_BOTTOM), fallback.leftBrow, imageWidth, imageHeight, 1.05f, 1.10f),
        rightBrow = contourRect(face, intArrayOf(FaceContour.RIGHT_EYEBROW_TOP, FaceContour.RIGHT_EYEBROW_BOTTOM), fallback.rightBrow, imageWidth, imageHeight, 1.05f, 1.10f),
        nose = contourRect(face, intArrayOf(FaceContour.NOSE_BRIDGE, FaceContour.NOSE_BOTTOM), fallback.nose, imageWidth, imageHeight, 1.05f, 1.05f),
        mouth = contourRect(face, intArrayOf(FaceContour.UPPER_LIP_TOP, FaceContour.UPPER_LIP_BOTTOM, FaceContour.LOWER_LIP_TOP, FaceContour.LOWER_LIP_BOTTOM), fallback.mouth, imageWidth, imageHeight, 1.08f, 1.12f),
        timestampMs = android.os.SystemClock.elapsedRealtime()
      )
      return if (mirrored) result.mirrored() else result
    }

    fun fromMediaPipe(
      landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
      mirrored: Boolean
    ): BeautyFaceMask {
      if (landmarks.isEmpty()) return BeautyFaceMask()
      val faceRect = landmarkRect(
        landmarks,
        intArrayOf(10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109),
        fallback = NormalizedRect(0.1f, 0.1f, 0.8f, 0.8f),
        xFactor = 0.96f,
        yFactor = 0.96f
      )
      val fallback = fallbackFeatures(faceRect)
      val result = BeautyFaceMask(
        face = faceRect,
        leftEye = landmarkRect(landmarks, intArrayOf(33, 160, 158, 133, 153, 144, 163, 7), fallback.leftEye, 1.08f, 1.12f),
        rightEye = landmarkRect(landmarks, intArrayOf(362, 385, 387, 263, 373, 380, 382, 249), fallback.rightEye, 1.08f, 1.12f),
        leftBrow = landmarkRect(landmarks, intArrayOf(70, 63, 105, 66, 107, 55, 65, 52, 53, 46), fallback.leftBrow, 1.05f, 1.10f),
        rightBrow = landmarkRect(landmarks, intArrayOf(300, 293, 334, 296, 336, 285, 295, 282, 283, 276), fallback.rightBrow, 1.05f, 1.10f),
        nose = landmarkRect(landmarks, intArrayOf(1, 2, 98, 327, 168, 6, 197, 195, 5, 4), fallback.nose, 1.05f, 1.05f),
        mouth = landmarkRect(landmarks, intArrayOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291, 146, 91, 181, 84, 17, 314, 405, 321, 375), fallback.mouth, 1.08f, 1.12f),
        timestampMs = android.os.SystemClock.elapsedRealtime()
      )
      return if (mirrored) result.mirrored() else result
    }

    private fun landmarkRect(
      landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
      indices: IntArray,
      fallback: NormalizedRect,
      xFactor: Float,
      yFactor: Float
    ): NormalizedRect {
      var minX = 1f
      var minY = 1f
      var maxX = 0f
      var maxY = 0f
      var count = 0
      val size = landmarks.size
      for (idx in indices) {
        if (idx in 0 until size) {
          val lm = landmarks[idx]
          val lx = lm.x()
          val ly = lm.y()
          if (lx < minX) minX = lx
          if (ly < minY) minY = ly
          if (lx > maxX) maxX = lx
          if (ly > maxY) maxY = ly
          count++
        }
      }
      if (count == 0) return fallback
      return NormalizedRect(
        minX.coerceIn(0f, 1f),
        minY.coerceIn(0f, 1f),
        (maxX - minX).coerceIn(0f, 1f),
        (maxY - minY).coerceIn(0f, 1f)
      ).expanded(xFactor, yFactor)
    }

    private fun contourRect(
      face: Face,
      types: IntArray,
      fallback: NormalizedRect,
      imageWidth: Int,
      imageHeight: Int,
      xFactor: Float,
      yFactor: Float
    ): NormalizedRect {
      val points = types.flatMap { face.getContour(it)?.points ?: emptyList() }
      if (points.isEmpty()) return fallback
      return rectFromPoints(points, null, imageWidth, imageHeight).expanded(xFactor, yFactor)
    }

    private fun rectFromPoints(
      points: List<PointF>?,
      fallback: Rect?,
      imageWidth: Int,
      imageHeight: Int
    ): NormalizedRect {
      if (!points.isNullOrEmpty()) {
        val minX = points.minOf { it.x }
        val minY = points.minOf { it.y }
        val maxX = points.maxOf { it.x }
        val maxY = points.maxOf { it.y }
        return NormalizedRect(
          (minX / imageWidth).coerceIn(0f, 1f),
          (minY / imageHeight).coerceIn(0f, 1f),
          ((maxX - minX) / imageWidth).coerceIn(0f, 1f),
          ((maxY - minY) / imageHeight).coerceIn(0f, 1f)
        )
      }
      if (fallback == null) return NormalizedRect()
      return NormalizedRect(
        fallback.left.toFloat() / imageWidth,
        fallback.top.toFloat() / imageHeight,
        fallback.width().toFloat() / imageWidth,
        fallback.height().toFloat() / imageHeight
      )
    }

    private fun fallbackFeatures(face: NormalizedRect): BeautyFaceMask {
      val x = face.x
      val y = face.y
      val w = face.width
      val h = face.height
      return BeautyFaceMask(
        face = face,
        leftEye = NormalizedRect(x + w * 0.13f, y + h * 0.3f, w * 0.3f, h * 0.16f),
        rightEye = NormalizedRect(x + w * 0.57f, y + h * 0.3f, w * 0.3f, h * 0.16f),
        leftBrow = NormalizedRect(x + w * 0.1f, y + h * 0.2f, w * 0.34f, h * 0.12f),
        rightBrow = NormalizedRect(x + w * 0.56f, y + h * 0.2f, w * 0.34f, h * 0.12f),
        nose = NormalizedRect(x + w * 0.37f, y + h * 0.38f, w * 0.26f, h * 0.3f),
        mouth = NormalizedRect(x + w * 0.25f, y + h * 0.68f, w * 0.5f, h * 0.2f)
      )
    }
  }
}

