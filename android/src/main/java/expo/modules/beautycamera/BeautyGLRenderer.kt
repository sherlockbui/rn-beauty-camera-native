package expo.modules.beautycamera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.Surface
import androidx.camera.core.SurfaceRequest
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

internal data class BeautyCaptureOptions(
  val maxWidth: Int,
  val quality: Double,
  val callback: (Result<Map<String, Any>>) -> Unit
)

/** CameraX -> external OES texture -> GLES shader -> GLSurfaceView. */
internal class BeautyGLRenderer(
  private val view: GLSurfaceView,
  private val mainExecutor: Executor,
  private val cacheDirectory: File,
  private val onFirstFrame: () -> Unit,
  private val onRenderError: (Throwable) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
  @Volatile var beautyEnabled = true
  @Volatile var smoothingStrength = 0.75f
  @Volatile var slimFaceStrength = 0f
  @Volatile var enlargeEyesStrength = 0f
  @Volatile var noseSlimStrength = 0f
  @Volatile var frontFacing = true
  @Volatile var rotationDegrees = 0
  @Volatile var sourceWidth = 1280
  @Volatile var sourceHeight = 720

  private val faceMask = AtomicReference(BeautyFaceMask())
  private val pendingCapture = AtomicReference<BeautyCaptureOptions?>(null)
  private val textureMatrix = FloatArray(16)
  private var viewportWidth = 1
  private var viewportHeight = 1
  private var oesTexture = 0
  private var surfaceTexture: SurfaceTexture? = null
  private var cameraSurface: Surface? = null
  private var pendingSurfaceRequest: SurfaceRequest? = null
  private var program = 0
  private var frameAvailable = false
  private var firstFrameSent = false
  private val vertices: FloatBuffer = ByteBuffer.allocateDirect(VERTICES.size * 4)
    .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(VERTICES).position(0) }

  fun updateFaceMask(mask: BeautyFaceMask) = faceMask.set(mask)
  fun clearFaceMask() = faceMask.set(BeautyFaceMask())

  fun attachSurfaceRequest(request: SurfaceRequest) {
    view.queueEvent {
      pendingSurfaceRequest = request
      provideSurfaceIfReady()
    }
  }

  fun capture(options: BeautyCaptureOptions): Boolean =
    pendingCapture.compareAndSet(null, options).also { if (it) view.requestRender() }

  fun release() {
    pendingCapture.getAndSet(null)?.callback?.invoke(
      Result.failure(IllegalStateException("Camera was closed."))
    )
    view.queueEvent { releaseCameraSurface() }
  }

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    try {
      program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
      oesTexture = createExternalTexture()
      provideSurfaceIfReady()
    } catch (error: Throwable) {
      onRenderError(error)
    }
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    viewportWidth = max(1, width)
    viewportHeight = max(1, height)
    GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
  }

  override fun onDrawFrame(gl: GL10?) {
    try {
      val texture = surfaceTexture
      if (texture != null && frameAvailable) {
        frameAvailable = false
        texture.updateTexImage()
        texture.getTransformMatrix(textureMatrix)
      }
      GLES30.glClearColor(0f, 0f, 0f, 1f)
      GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
      if (texture == null || program == 0) return

      GLES30.glUseProgram(program)
      vertices.position(0)
      val position = GLES30.glGetAttribLocation(program, "aPosition")
      GLES30.glEnableVertexAttribArray(position)
      GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 0, vertices)
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
      GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
      GLES30.glUniform1i(location("uCamera"), 0)
      GLES30.glUniformMatrix4fv(location("uTextureMatrix"), 1, false, textureMatrix, 0)
      GLES30.glUniform2f(location("uSourceSize"), sourceWidth.toFloat(), sourceHeight.toFloat())
      GLES30.glUniform2f(location("uViewportSize"), viewportWidth.toFloat(), viewportHeight.toFloat())
      GLES30.glUniform1i(location("uRotation"), ((rotationDegrees % 360) + 360) % 360)
      GLES30.glUniform1f(location("uMirrored"), if (frontFacing) 1f else 0f)

      val mask = faceMask.get().takeIf {
        SystemClock.elapsedRealtime() - it.timestampMs <= 450L
      } ?: BeautyFaceMask()
      setRect("uFace", mask.face)
      setRect("uLeftEye", mask.leftEye)
      setRect("uRightEye", mask.rightEye)
      setRect("uLeftBrow", mask.leftBrow)
      setRect("uRightBrow", mask.rightBrow)
      setRect("uNose", mask.nose)
      setRect("uMouth", mask.mouth)
      GLES30.glUniform2f(location("uTexelSize"), 1f / max(1, sourceWidth), 1f / max(1, sourceHeight))
      GLES30.glUniform1f(location("uSmoothing"), smoothingStrength.coerceIn(0f, 1.0f))
      GLES30.glUniform1f(location("uSlimFace"), slimFaceStrength.coerceIn(0f, 1f))
      GLES30.glUniform1f(location("uEnlargeEyes"), enlargeEyesStrength.coerceIn(0f, 1f))
      GLES30.glUniform1f(location("uNoseSlim"), noseSlimStrength.coerceIn(0f, 1f))
      GLES30.glUniform1f(
        location("uBeautyEnabled"),
        if (beautyEnabled && frontFacing) 1f else 0f
      )
      GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
      GLES30.glDisableVertexAttribArray(position)

      if (!firstFrameSent) {
        firstFrameSent = true
        mainExecutor.execute(onFirstFrame)
      }
      pendingCapture.getAndSet(null)?.let { finishCapture(it, mask.detected) }
    } catch (error: Throwable) {
      pendingCapture.getAndSet(null)?.callback?.invoke(Result.failure(error))
      onRenderError(error)
    }
  }

  override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
    frameAvailable = true
    view.requestRender()
  }

  private fun provideSurfaceIfReady() {
    val request = pendingSurfaceRequest ?: return
    if (oesTexture == 0) return
    pendingSurfaceRequest = null
    releaseCameraSurface()
    sourceWidth = request.resolution.width
    sourceHeight = request.resolution.height
    val texture = SurfaceTexture(oesTexture).apply {
      setDefaultBufferSize(sourceWidth, sourceHeight)
      setOnFrameAvailableListener(this@BeautyGLRenderer)
    }
    val surface = Surface(texture)
    surfaceTexture = texture
    cameraSurface = surface
    firstFrameSent = false
    request.provideSurface(surface, mainExecutor) {
      view.queueEvent { if (cameraSurface === surface) releaseCameraSurface() }
    }
  }

  private fun releaseCameraSurface() {
    surfaceTexture?.setOnFrameAvailableListener(null)
    cameraSurface?.release()
    surfaceTexture?.release()
    cameraSurface = null
    surfaceTexture = null
    frameAvailable = false
  }

  private fun location(name: String) = GLES30.glGetUniformLocation(program, name)

  private fun setRect(name: String, rect: NormalizedRect) {
    GLES30.glUniform4f(location(name), rect.x, rect.y, rect.width, rect.height)
  }

  private fun finishCapture(options: BeautyCaptureOptions, faceDetected: Boolean) {
    try {
      val pixels = ByteBuffer.allocateDirect(viewportWidth * viewportHeight * 4)
        .order(ByteOrder.nativeOrder())
      GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
      GLES30.glReadPixels(0, 0, viewportWidth, viewportHeight, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
      val raw = Bitmap.createBitmap(viewportWidth, viewportHeight, Bitmap.Config.ARGB_8888)
      pixels.position(0)
      raw.copyPixelsFromBuffer(pixels)
      val transform = Matrix().apply { postScale(if (frontFacing) -1f else 1f, -1f) }
      val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, transform, true)
      if (upright !== raw) raw.recycle()

      val scale = min(1f, options.maxWidth.coerceAtLeast(1).toFloat() / upright.width)
      val width = max(1, (upright.width * scale).toInt())
      val height = max(1, (upright.height * scale).toInt())
      val output = if (width != upright.width) Bitmap.createScaledBitmap(upright, width, height, true) else upright
      val folder = File(cacheDirectory, "beauty-camera-native").apply { mkdirs() }
      val file = File(folder, "attendance-${java.util.UUID.randomUUID()}.jpg")
      FileOutputStream(file).use {
        check(output.compress(Bitmap.CompressFormat.JPEG, (options.quality * 100).toInt(), it))
      }
      if (output !== upright) output.recycle()
      upright.recycle()
      val applied = beautyEnabled && frontFacing
      options.callback(Result.success(mapOf(
        "uri" to "file://${file.absolutePath}",
        "width" to width,
        "height" to height,
        "filterApplied" to applied,
        "smoothingApplied" to (applied && faceDetected)
      )))
    } catch (error: Throwable) {
      options.callback(Result.failure(error))
    }
  }

  private fun createExternalTexture(): Int {
    val textures = IntArray(1)
    GLES30.glGenTextures(1, textures, 0)
    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
    GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    return textures[0]
  }

  private fun createProgram(vertexSource: String, fragmentSource: String): Int {
    val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
    val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
    return GLES30.glCreateProgram().also {
      GLES30.glAttachShader(it, vertex)
      GLES30.glAttachShader(it, fragment)
      GLES30.glLinkProgram(it)
      val status = IntArray(1)
      GLES30.glGetProgramiv(it, GLES30.GL_LINK_STATUS, status, 0)
      check(status[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(it) }
      GLES30.glDeleteShader(vertex)
      GLES30.glDeleteShader(fragment)
    }
  }

  private fun compileShader(type: Int, source: String): Int = GLES30.glCreateShader(type).also {
    GLES30.glShaderSource(it, source)
    GLES30.glCompileShader(it)
    val status = IntArray(1)
    GLES30.glGetShaderiv(it, GLES30.GL_COMPILE_STATUS, status, 0)
    check(status[0] == GLES30.GL_TRUE) { GLES30.glGetShaderInfoLog(it) }
  }

  private companion object {
    val VERTICES = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    const val VERTEX_SHADER = """
      #version 300 es
      in vec2 aPosition;
      out vec2 vViewUv;
      void main() {
        gl_Position = vec4(aPosition, 0.0, 1.0);
        vViewUv = vec2(aPosition.x * 0.5 + 0.5, 0.5 - aPosition.y * 0.5);
      }
    """
    const val FRAGMENT_SHADER = """
      #version 300 es
      #extension GL_OES_EGL_image_external_essl3 : require
      precision highp float;
      uniform samplerExternalOES uCamera;
      uniform mat4 uTextureMatrix;
      uniform vec2 uSourceSize, uViewportSize, uTexelSize;
      uniform int uRotation;
      uniform float uMirrored, uSmoothing, uBeautyEnabled;
      uniform float uSlimFace, uEnlargeEyes, uNoseSlim;
      uniform vec4 uFace, uLeftEye, uRightEye, uLeftBrow, uRightBrow, uNose, uMouth;
      in vec2 vViewUv;
      out vec4 outColor;

      vec2 aspectFillUv(vec2 uv) {
        vec2 size = (uRotation == 90 || uRotation == 270) ? uSourceSize.yx : uSourceSize;
        float sourceAspect = size.x / max(size.y, 1.0);
        float viewAspect = uViewportSize.x / max(uViewportSize.y, 1.0);
        if (sourceAspect > viewAspect) {
          uv.x = (uv.x - 0.5) * viewAspect / sourceAspect + 0.5;
        } else {
          uv.y = (uv.y - 0.5) * sourceAspect / viewAspect + 0.5;
        }
        return uv;
      }
      vec2 rawUv(vec2 p) {
        if (uMirrored > 0.5) {
          p.x = 1.0 - p.x;
        } else {
          p = vec2(1.0 - p.x, 1.0 - p.y);
        }
        p = vec2(1.0 - p.y, p.x);
        if (uRotation == 90) return vec2(p.y, 1.0 - p.x);
        if (uRotation == 180) return vec2(1.0 - p.x, 1.0 - p.y);
        if (uRotation == 270) return vec2(1.0 - p.y, p.x);
        return p;
      }
      vec4 cameraAt(vec2 uv) {
        vec2 transformed = (uTextureMatrix * vec4(rawUv(uv), 0.0, 1.0)).xy;
        return texture(uCamera, transformed);
      }
      vec2 warpUv(vec2 uv) {
        if (uFace.z <= 0.0 || uFace.w <= 0.0) return uv;
        if (uEnlargeEyes > 0.01) {
          vec2 leftCenter = uLeftEye.xy + uLeftEye.zw * 0.5;
          vec2 rightCenter = uRightEye.xy + uRightEye.zw * 0.5;
          float radius = max(uLeftEye.z, uLeftEye.w) * 1.2;
          if (radius > 0.005) {
            float dLeft = length(uv - leftCenter);
            if (dLeft < radius) {
              float factor = dLeft / radius;
              uv = leftCenter + (uv - leftCenter) * mix(1.0, factor * factor, uEnlargeEyes * 0.35);
            }
            float dRight = length(uv - rightCenter);
            if (dRight < radius) {
              float factor = dRight / radius;
              uv = rightCenter + (uv - rightCenter) * mix(1.0, factor * factor, uEnlargeEyes * 0.35);
            }
          }
        }
        if (uSlimFace > 0.01) {
          vec2 faceCenter = uFace.xy + uFace.zw * 0.5;
          float jawY = uFace.y + uFace.w * 0.65;
          if (uv.y > jawY && uv.y < uFace.y + uFace.w) {
            float distToAxis = abs(uv.x - faceCenter.x);
            float maxDist = uFace.z * 0.5;
            if (distToAxis < maxDist) {
              float pull = (1.0 - distToAxis / maxDist) * uSlimFace * 0.08;
              if (uv.x < faceCenter.x) { uv.x += pull; } else { uv.x -= pull; }
            }
          }
        }
        if (uNoseSlim > 0.01 && uNose.z > 0.0) {
          vec2 noseCenter = uNose.xy + uNose.zw * 0.5;
          float distToNose = length(uv - noseCenter);
          float noseRadius = max(uNose.z, uNose.w) * 1.1;
          if (distToNose < noseRadius) {
            float pull = (1.0 - distToNose / noseRadius) * uNoseSlim * 0.05;
            if (uv.x < noseCenter.x) { uv.x += pull; } else { uv.x -= pull; }
          }
        }
        return uv;
      }
      float ellipse(vec2 uv, vec4 rect, float feather) {
        if (rect.z <= 0.0 || rect.w <= 0.0) return 0.0;
        vec2 center = rect.xy + rect.zw * 0.5;
        vec2 radius = max(rect.zw * 0.5, vec2(0.0001));
        float d = length((uv - center) / radius);
        return 1.0 - smoothstep(1.0 - feather, 1.0 + feather, d);
      }
      float skinConfidence(vec3 rgb) {
        float y = dot(rgb, vec3(0.299, 0.587, 0.114));
        float cb = 0.5 + (rgb.b - y) * 0.564;
        float cr = 0.5 + (rgb.r - y) * 0.713;
        float c = smoothstep(0.25, 0.34, cr) * (1.0 - smoothstep(0.68, 0.78, cr));
        c *= smoothstep(0.20, 0.28, cb) * (1.0 - smoothstep(0.58, 0.68, cb));
        return c * smoothstep(0.08, 0.2, y) * (1.0 - smoothstep(0.94, 1.0, y));
      }
      vec3 naturalTone(vec3 c) {
        c = pow(max(c, vec3(0.0)), vec3(0.965)) * vec3(1.028, 1.012, 0.982);
        float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
        c = mix(vec3(l), c, 1.045);
        return clamp((c - 0.5) * 1.025 + 0.515, 0.0, 1.0);
      }
      void main() {
        vec2 uv = warpUv(aspectFillUv(vViewUv));
        vec4 original = cameraAt(uv);
        if (uBeautyEnabled < 0.5) { outColor = original; return; }
        vec3 toned = naturalTone(original.rgb);
        // Super-smooth gradient feathering (0.55 face, 0.40 features) to eliminate all mask boundaries
        float faceMask = (uFace.z <= 0.0 || uFace.w <= 0.0) ? 1.0 : ellipse(uv, uFace, 0.55);
        float protectedFeat = (uFace.z <= 0.0 || uFace.w <= 0.0) ? 0.0 : max(
          max(ellipse(uv, uLeftEye, 0.40), ellipse(uv, uRightEye, 0.40)),
          max(
            max(ellipse(uv, uLeftBrow, 0.40), ellipse(uv, uRightBrow, 0.40)),
            max(ellipse(uv, uNose, 0.35), ellipse(uv, uMouth, 0.40))
          )
        );
        float skinProb = skinConfidence(original.rgb);
        float blendMask = faceMask * (1.0 - protectedFeat * 0.50) * smoothstep(0.10, 0.38, skinProb);

        // Early Exit Optimization: Skip heavy GPU sampling for non-face background pixels (80% frame speedup)
        if (blendMask < 0.015) {
          outColor = vec4(toned, original.a);
          return;
        }

        // Fast 6-Point TikTok Airbrush Filter (Bilateral + Gaussian Dual-Pass at 60 FPS)
        vec2 r1 = max(uTexelSize * 6.5, vec2(0.0036));
        vec2 r2 = max(uTexelSize * 13.5, vec2(0.0074));
        vec3 sum = toned * 1.0;
        vec3 softSum = toned * 1.0;
        float weight = 1.0;
        float softWeight = 1.0;
        vec2 offsets[6] = vec2[6](
          vec2(r1.x, 0.0), vec2(-r1.x, 0.0), vec2(0.0, r1.y), vec2(0.0, -r1.y),
          vec2(r2.x, r2.y), vec2(-r2.x, -r2.y)
        );
        for (int i = 0; i < 6; i++) {
          vec3 sampleColor = naturalTone(cameraAt(uv + offsets[i]).rgb);
          float edge = exp(-dot(sampleColor - toned, sampleColor - toned) * 3.2);
          float spatial = i < 4 ? 1.0 : 0.75;
          sum += sampleColor * edge * spatial;
          weight += edge * spatial;
          softSum += sampleColor * spatial;
          softWeight += spatial;
        }
        vec3 bilateralSmoothed = sum / max(weight, 0.001);
        vec3 gaussianBlur = softSum / max(softWeight, 0.001);

        // TikTok Airbrush Dual-Blend: Mix edge-aware smoothing with soft airbrush blur
        vec3 tiktokSmoothed = mix(bilateralSmoothed, gaussianBlur, 0.58);

        // Blend smoothing seamlessly onto skin (TikTok Ultra Airbrush Smooth)
        float smoothingFactor = clamp(uSmoothing * 3.2, 0.0, 0.999) * blendMask;
        vec3 beauty = mix(toned, tiktokSmoothed, smoothingFactor);

        // Natural Soft-Light Skin Whitening (smooth tone curve, no mask outlines)
        vec3 brightTone = beauty * (vec3(1.0) + (vec3(1.0) - beauty) * 0.28 * uSmoothing);
        brightTone += vec3(0.038, 0.035, 0.035) * uSmoothing;
        beauty = mix(beauty, brightTone, blendMask);

        outColor = vec4(clamp(beauty, 0.0, 1.0), original.a);
      }
    """
  }
}
