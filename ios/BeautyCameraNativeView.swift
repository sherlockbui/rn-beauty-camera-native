import AVFoundation
import CoreGraphics
import CoreVideo
import ExpoModulesCore
import Metal
import MetalKit
import MLKitFaceDetection
import MLKitVision
import UIKit

private enum BeautyCameraNativeError: Error, LocalizedError {
  case metalUnavailable
  case cameraUnavailable
  case captureInProgress
  case captureFailed

  var errorDescription: String? {
    switch self {
    case .metalUnavailable: return "Metal beauty renderer is unavailable."
    case .cameraUnavailable: return "Camera device is unavailable."
    case .captureInProgress: return "A capture is already in progress."
    case .captureFailed: return "Could not encode the filtered camera frame."
    }
  }
}

private struct BeautyFaceMask {
  var face = SIMD4<Float>.zero
  var leftEye = SIMD4<Float>.zero
  var rightEye = SIMD4<Float>.zero
  var leftBrow = SIMD4<Float>.zero
  var rightBrow = SIMD4<Float>.zero
  var nose = SIMD4<Float>.zero
  var mouth = SIMD4<Float>.zero
  var timestamp: TimeInterval = 0

  var detected: Bool { face.z > 0 && face.w > 0 }
}

private struct BeautyUniforms {
  var face = SIMD4<Float>.zero
  var leftEye = SIMD4<Float>.zero
  var rightEye = SIMD4<Float>.zero
  var leftBrow = SIMD4<Float>.zero
  var rightBrow = SIMD4<Float>.zero
  var nose = SIMD4<Float>.zero
  var mouth = SIMD4<Float>.zero
  var texelSize = SIMD2<Float>.zero
  var uvScale = SIMD2<Float>(repeating: 1)
  var smoothing: Float = 0.3
  var beautyEnabled: Float = 1
  var highQuality: Float = 1
  var mirrored: Float = 1
  var slimFace: Float = 0
  var enlargeEyes: Float = 0
  var noseSlim: Float = 0
}

private struct PendingCapture {
  let maxWidth: Int
  let quality: Double
  let continuation: CheckedContinuation<[String: Any], Error>
}

public final class BeautyCameraNativeView: ExpoView, AVCaptureVideoDataOutputSampleBufferDelegate {
  let onReady = EventDispatcher()
  let onError = EventDispatcher()
  let onFaceState = EventDispatcher()

  private let session = AVCaptureSession()
  private let sessionQueue = DispatchQueue(label: "jobtik.beauty-camera.session")
  private let videoQueue = DispatchQueue(
    label: "jobtik.beauty-camera.video",
    qos: .userInteractive
  )
  private let stateLock = NSLock()
  private let metalView: MTKView
  private let metalDevice: MTLDevice?
  private let commandQueue: MTLCommandQueue?
  private let pipeline: MTLRenderPipelineState?
  private let rendererAvailable: Bool
  private var textureCache: CVMetalTextureCache?
  private var faceDetector: FaceDetector?
  private var currentInput: AVCaptureDeviceInput?
  private var videoOutput: AVCaptureVideoDataOutput?
  private var configuredFacing = "front"
  private var currentMask = BeautyFaceMask()
  private var lastFaceAnalysisTime: TimeInterval = 0
  private var faceAnalysisBusy = false
  private var lastFaceEvent = false
  private var readySent = false
  private var pendingCapture: PendingCapture?

  public var active = true {
    didSet { updateSessionRunningState() }
  }

  public var facing = "front" {
    didSet {
      guard facing != configuredFacing else { return }
      configureSession()
    }
  }

  public var beautyEnabled = true
  public var smoothingStrength: Float = 0.75
  public var slimFaceStrength: Float = 0
  public var enlargeEyesStrength: Float = 0
  public var noseSlimStrength: Float = 0

  public var torchEnabled = false {
    didSet { updateTorch() }
  }

  public required init(appContext: AppContext? = nil) {
    let device = MTLCreateSystemDefaultDevice()
    self.metalDevice = device
    self.commandQueue = device?.makeCommandQueue()
    self.metalView = MTKView(frame: .zero, device: device)

    let createdPipeline = device.flatMap { try? Self.makePipeline(device: $0) }
    self.pipeline = createdPipeline
    self.rendererAvailable = device != nil && self.commandQueue != nil && createdPipeline != nil

    super.init(appContext: appContext)

    metalView.framebufferOnly = false
    metalView.colorPixelFormat = .bgra8Unorm
    metalView.isPaused = true
    metalView.enableSetNeedsDisplay = false
    metalView.contentMode = .scaleAspectFill
    if let metalLayer = metalView.layer as? CAMetalLayer {
      metalLayer.pixelFormat = .bgra8Unorm
      metalLayer.framebufferOnly = false
    }
    addSubview(metalView)

    if let device, rendererAvailable {
      CVMetalTextureCacheCreate(
        kCFAllocatorDefault,
        nil,
        device,
        nil,
        &textureCache
      )
      configureSession()
    } else {
      // Event handlers are attached immediately after the Expo view is made.
      // Defer the event so JS can switch to its stable camera fallback.
      DispatchQueue.main.async { [weak self] in
        self?.emitError(
          code: "metal-unavailable",
          message: BeautyCameraNativeError.metalUnavailable.localizedDescription
        )
      }
    }
  }

  private static func makePipeline(device: MTLDevice) throws -> MTLRenderPipelineState {
    let classBundle = Bundle(for: BeautyCameraNativeView.self)
    var candidates = [classBundle, Bundle.main]

    if
      let bundleURL = Bundle.main.url(forResource: "BeautyCameraNative", withExtension: "bundle"),
      let resourceBundle = Bundle(url: bundleURL)
    {
      candidates.insert(resourceBundle, at: 0)
    }

    for bundle in candidates {
      if let library = try? device.makeDefaultLibrary(bundle: bundle),
         let pipeline = try? makePipeline(device: device, library: library)
      {
        return pipeline
      }

      if let metallibURL = bundle.url(forResource: "BeautyCameraShaders", withExtension: "metallib") ??
                           bundle.url(forResource: "default", withExtension: "metallib"),
         let library = try? device.makeLibrary(URL: metallibURL),
         let pipeline = try? makePipeline(device: device, library: library)
      {
        return pipeline
      }

      if let metalURL = bundle.url(forResource: "BeautyCameraShaders", withExtension: "metal"),
         let source = try? String(contentsOf: metalURL, encoding: .utf8),
         let library = try? device.makeLibrary(source: source, options: nil),
         let pipeline = try? makePipeline(device: device, library: library)
      {
        return pipeline
      }
    }

    if let metalURL = Bundle.main.url(forResource: "BeautyCameraShaders", withExtension: "metal"),
       let source = try? String(contentsOf: metalURL, encoding: .utf8),
       let library = try? device.makeLibrary(source: source, options: nil),
       let pipeline = try? makePipeline(device: device, library: library)
    {
      return pipeline
    }

    throw BeautyCameraNativeError.metalUnavailable
  }

  private static func makePipeline(
    device: MTLDevice,
    library: MTLLibrary
  ) throws -> MTLRenderPipelineState {
    guard
      let vertex = library.makeFunction(name: "beautyVertex"),
      let fragment = library.makeFunction(name: "beautyFragment")
    else {
      throw BeautyCameraNativeError.metalUnavailable
    }
    let descriptor = MTLRenderPipelineDescriptor()
    descriptor.vertexFunction = vertex
    descriptor.fragmentFunction = fragment
    descriptor.colorAttachments[0].pixelFormat = .bgra8Unorm
    return try device.makeRenderPipelineState(descriptor: descriptor)
  }

  deinit {
    session.stopRunning()
  }

  public override func layoutSubviews() {
    super.layoutSubviews()
    metalView.frame = bounds
    metalView.drawableSize = CGSize(
      width: max(1, bounds.width * UIScreen.main.scale),
      height: max(1, bounds.height * UIScreen.main.scale)
    )
  }

  public func capture(maxWidth: Int, quality: Double) async throws -> [String: Any] {
    guard rendererAvailable else {
      throw BeautyCameraNativeError.metalUnavailable
    }
    return try await withCheckedThrowingContinuation { continuation in
      videoQueue.async { [weak self] in
        guard let self else {
          continuation.resume(throwing: BeautyCameraNativeError.captureFailed)
          return
        }
        guard self.pendingCapture == nil else {
          continuation.resume(throwing: BeautyCameraNativeError.captureInProgress)
          return
        }
        self.pendingCapture = PendingCapture(
          maxWidth: max(1, min(2048, maxWidth)),
          quality: max(0.1, min(1, quality)),
          continuation: continuation
        )
      }
    }
  }

  private func configureSession() {
    sessionQueue.async { [weak self] in
      guard let self else { return }
      self.session.beginConfiguration()
      self.session.sessionPreset = .hd1280x720

      if let input = self.currentInput {
        self.session.removeInput(input)
      }
      if let output = self.videoOutput {
        self.session.removeOutput(output)
      }

      let position: AVCaptureDevice.Position = self.facing == "back" ? .back : .front
      guard
        let camera = AVCaptureDevice.default(
          .builtInWideAngleCamera,
          for: .video,
          position: position
        ),
        let input = try? AVCaptureDeviceInput(device: camera),
        self.session.canAddInput(input)
      else {
        self.session.commitConfiguration()
        self.emitError(code: "device-unavailable", message: "Camera device is unavailable.")
        return
      }
      self.session.addInput(input)
      self.currentInput = input
      self.configuredFacing = self.facing

      let output = AVCaptureVideoDataOutput()
      output.alwaysDiscardsLateVideoFrames = true
      output.videoSettings = [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
      ]
      output.setSampleBufferDelegate(self, queue: self.videoQueue)
      guard self.session.canAddOutput(output) else {
        self.session.commitConfiguration()
        self.emitError(code: "output-unavailable", message: "Filtered camera output is unavailable.")
        return
      }
      self.session.addOutput(output)
      self.videoOutput = output

      if let connection = output.connection(with: .video) {
        if #available(iOS 17.0, *) {
          if connection.isVideoRotationAngleSupported(90) {
            connection.videoRotationAngle = 90
          }
        } else if connection.isVideoOrientationSupported {
          connection.videoOrientation = .portrait
        }
        connection.automaticallyAdjustsVideoMirroring = false
        connection.isVideoMirrored = false
      }

      self.session.commitConfiguration()
      self.readySent = false
      self.resetFaceMask()
      self.updateSessionRunningState()
      self.updateTorch()
    }
  }

  private func updateSessionRunningState() {
    sessionQueue.async { [weak self] in
      guard let self else { return }
      if self.active && !self.session.isRunning {
        self.session.startRunning()
      } else if !self.active && self.session.isRunning {
        self.session.stopRunning()
      }
    }
  }

  private func updateTorch() {
    sessionQueue.async { [weak self] in
      guard let device = self?.currentInput?.device, device.hasTorch else { return }
      do {
        try device.lockForConfiguration()
        device.torchMode = self?.torchEnabled == true ? .on : .off
        device.unlockForConfiguration()
      } catch {
        self?.emitError(code: "torch-failed", message: error.localizedDescription)
      }
    }
  }

  public func captureOutput(
    _ output: AVCaptureOutput,
    didOutput sampleBuffer: CMSampleBuffer,
    from connection: AVCaptureConnection
  ) {
    guard active, let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
    if !readySent {
      readySent = true
      DispatchQueue.main.async { [weak self] in
        self?.onReady()
      }
    }
    render(pixelBuffer: pixelBuffer)
    analyzeFace(sampleBuffer: sampleBuffer, pixelBuffer: pixelBuffer)
  }

  private func render(pixelBuffer: CVPixelBuffer) {
    guard
      let textureCache,
      let commandQueue,
      let pipeline,
      let metalDevice,
      let metalLayer = metalView.layer as? CAMetalLayer,
      let drawable = metalLayer.nextDrawable()
    else { return }

    let passDescriptor = MTLRenderPassDescriptor()
    passDescriptor.colorAttachments[0].texture = drawable.texture
    passDescriptor.colorAttachments[0].loadAction = .clear
    passDescriptor.colorAttachments[0].storeAction = .store
    passDescriptor.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)

    let width = CVPixelBufferGetWidth(pixelBuffer)
    let height = CVPixelBufferGetHeight(pixelBuffer)
    var cvTexture: CVMetalTexture?
    let status = CVMetalTextureCacheCreateTextureFromImage(
      kCFAllocatorDefault,
      textureCache,
      pixelBuffer,
      nil,
      .bgra8Unorm,
      width,
      height,
      0,
      &cvTexture
    )
    guard
      status == kCVReturnSuccess,
      let retainedCVTexture = cvTexture,
      let cameraTexture = CVMetalTextureGetTexture(retainedCVTexture),
      let commandBuffer = commandQueue.makeCommandBuffer(),
      let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: passDescriptor)
    else { return }

    var uniforms = makeUniforms(textureWidth: width, textureHeight: height)
    encoder.setRenderPipelineState(pipeline)
    encoder.setFragmentTexture(cameraTexture, index: 0)
    encoder.setFragmentBytes(
      &uniforms,
      length: MemoryLayout<BeautyUniforms>.stride,
      index: 0
    )
    encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 6)
    encoder.endEncoding()

    let capture = pendingCapture
    pendingCapture = nil
    var captureTexture: MTLTexture?
    if capture != nil {
      let descriptor = MTLTextureDescriptor.texture2DDescriptor(
        pixelFormat: .bgra8Unorm,
        width: drawable.texture.width,
        height: drawable.texture.height,
        mipmapped: false
      )
      descriptor.storageMode = .shared
      descriptor.usage = [.shaderRead]
      captureTexture = metalDevice.makeTexture(descriptor: descriptor)
      if let captureTexture, let blit = commandBuffer.makeBlitCommandEncoder() {
        blit.copy(
          from: drawable.texture,
          sourceSlice: 0,
          sourceLevel: 0,
          sourceOrigin: .init(x: 0, y: 0, z: 0),
          sourceSize: .init(
            width: drawable.texture.width,
            height: drawable.texture.height,
            depth: 1
          ),
          to: captureTexture,
          destinationSlice: 0,
          destinationLevel: 0,
          destinationOrigin: .init(x: 0, y: 0, z: 0)
        )
        blit.endEncoding()
      }
    }

    commandBuffer.present(drawable)
    commandBuffer.addCompletedHandler { [weak self] _ in
      _ = retainedCVTexture
      guard let self else { return }
      if let capture, let captureTexture {
        self.finishCapture(texture: captureTexture, request: capture)
      }
      if !self.readySent {
        self.readySent = true
        DispatchQueue.main.async { self.onReady() }
      }
    }
    commandBuffer.commit()
  }

  private func makeUniforms(textureWidth: Int, textureHeight: Int) -> BeautyUniforms {
    stateLock.lock()
    let mask = currentMask
    stateLock.unlock()
    let freshMask = ProcessInfo.processInfo.systemUptime - mask.timestamp <= 0.45
      ? mask
      : BeautyFaceMask()
    let thermal = ProcessInfo.processInfo.thermalState
    let highQuality = thermal == .nominal || thermal == .fair
    let sourceAspect = Float(textureWidth) / Float(max(1, textureHeight))
    let viewAspect = Float(max(1, metalView.drawableSize.width)) /
      Float(max(1, metalView.drawableSize.height))
    let uvScale = sourceAspect > viewAspect
      ? SIMD2<Float>(viewAspect / sourceAspect, 1)
      : SIMD2<Float>(1, sourceAspect / viewAspect)
    return BeautyUniforms(
      face: freshMask.face,
      leftEye: freshMask.leftEye,
      rightEye: freshMask.rightEye,
      leftBrow: freshMask.leftBrow,
      rightBrow: freshMask.rightBrow,
      nose: freshMask.nose,
      mouth: freshMask.mouth,
      texelSize: SIMD2(1 / Float(textureWidth), 1 / Float(textureHeight)),
      uvScale: uvScale,
      smoothing: smoothingStrength,
      beautyEnabled: beautyEnabled && configuredFacing == "front" ? 1 : 0,
      highQuality: highQuality ? 1 : 0,
      mirrored: configuredFacing == "front" ? 1 : 0,
      slimFace: slimFaceStrength,
      enlargeEyes: enlargeEyesStrength,
      noseSlim: noseSlimStrength
    )
  }

  private func getFaceDetector() -> FaceDetector {
    if let detector = faceDetector { return detector }
    let options = FaceDetectorOptions()
    options.performanceMode = .fast
    options.contourMode = .all
    options.landmarkMode = .none
    options.classificationMode = .none
    options.minFaceSize = 0.12
    let detector = FaceDetector.faceDetector(options: options)
    self.faceDetector = detector
    return detector
  }

  private func analyzeFace(sampleBuffer: CMSampleBuffer, pixelBuffer: CVPixelBuffer) {
    let now = ProcessInfo.processInfo.systemUptime
    guard now - lastFaceAnalysisTime >= 0.1, !faceAnalysisBusy else { return }
    faceAnalysisBusy = true
    lastFaceAnalysisTime = now
    let image = VisionImage(buffer: sampleBuffer)
    image.orientation = .up
    let width = CGFloat(CVPixelBufferGetWidth(pixelBuffer))
    let height = CGFloat(CVPixelBufferGetHeight(pixelBuffer))

    getFaceDetector().process(image) { [weak self] faces, error in
      guard let self else { return }
      self.videoQueue.async {
        self.faceAnalysisBusy = false
        if let error {
          self.emitError(code: "face-detector", message: error.localizedDescription)
          return
        }
        let next = faces?
          .max(by: { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height })
          .map { self.makeMask(face: $0, width: width, height: height, now: now) }
          ?? BeautyFaceMask()
        self.stateLock.lock()
        self.currentMask = self.interpolateMask(from: self.currentMask, to: next)
        self.stateLock.unlock()
        self.emitFaceState(next.detected)
      }
    }
  }

  private func makeMask(
    face: Face,
    width: CGFloat,
    height: CGFloat,
    now: TimeInterval
  ) -> BeautyFaceMask {
    let faceRect = normalizedRect(
      points: face.contour(ofType: .face)?.points.map {
        CGPoint(x: $0.x, y: $0.y)
      },
      fallback: face.frame,
      width: width,
      height: height,
      expansionX: 0.94,
      expansionY: 0.96
    )
    let fallback = fallbackFeatures(face: faceRect)
    return BeautyFaceMask(
      face: faceRect,
      leftEye: contourRect(face, [.leftEye], fallback.leftEye, width, height, 1.45, 1.65),
      rightEye: contourRect(face, [.rightEye], fallback.rightEye, width, height, 1.45, 1.65),
      leftBrow: contourRect(face, [.leftEyebrowTop, .leftEyebrowBottom], fallback.leftBrow, width, height, 1.35, 1.8),
      rightBrow: contourRect(face, [.rightEyebrowTop, .rightEyebrowBottom], fallback.rightBrow, width, height, 1.35, 1.8),
      nose: contourRect(face, [.noseBridge, .noseBottom], fallback.nose, width, height, 1.5, 1.3),
      mouth: contourRect(face, [.upperLipTop, .upperLipBottom, .lowerLipTop, .lowerLipBottom], fallback.mouth, width, height, 1.35, 1.6),
      timestamp: now
    )
  }

  private func contourRect(
    _ face: Face,
    _ types: [FaceContourType],
    _ fallback: SIMD4<Float>,
    _ width: CGFloat,
    _ height: CGFloat,
    _ expansionX: Float,
    _ expansionY: Float
  ) -> SIMD4<Float> {
    let points = types.flatMap {
      face.contour(ofType: $0)?.points.map {
        CGPoint(x: $0.x, y: $0.y)
      } ?? []
    }
    return normalizedRect(
      points: points.isEmpty ? nil : points,
      fallback: .zero,
      width: width,
      height: height,
      expansionX: expansionX,
      expansionY: expansionY,
      emptyFallback: fallback
    )
  }

  private func normalizedRect(
    points: [CGPoint]?,
    fallback: CGRect,
    width: CGFloat,
    height: CGFloat,
    expansionX: Float,
    expansionY: Float,
    emptyFallback: SIMD4<Float> = .zero
  ) -> SIMD4<Float> {
    let rect: CGRect
    if let points, !points.isEmpty {
      let xs = points.map(\.x)
      let ys = points.map(\.y)
      rect = CGRect(
        x: xs.min() ?? 0,
        y: ys.min() ?? 0,
        width: (xs.max() ?? 0) - (xs.min() ?? 0),
        height: (ys.max() ?? 0) - (ys.min() ?? 0)
      )
    } else if fallback.width > 0 && fallback.height > 0 {
      rect = fallback
    } else {
      return emptyFallback
    }
    let normalized = SIMD4<Float>(
      Float(rect.minX / width),
      Float(rect.minY / height),
      Float(rect.width / width),
      Float(rect.height / height)
    )
    return expand(normalized, x: expansionX, y: expansionY)
  }

  private func expand(_ rect: SIMD4<Float>, x: Float, y: Float) -> SIMD4<Float> {
    let newWidth = min(1, rect.z * x)
    let newHeight = min(1, rect.w * y)
    let newX = max(0, min(1 - newWidth, rect.x - (newWidth - rect.z) / 2))
    let newY = max(0, min(1 - newHeight, rect.y - (newHeight - rect.w) / 2))
    return SIMD4(newX, newY, newWidth, newHeight)
  }

  private func fallbackFeatures(face: SIMD4<Float>) -> BeautyFaceMask {
    let x = face.x, y = face.y, w = face.z, h = face.w
    return BeautyFaceMask(
      face: face,
      leftEye: SIMD4(x + w * 0.13, y + h * 0.3, w * 0.3, h * 0.16),
      rightEye: SIMD4(x + w * 0.57, y + h * 0.3, w * 0.3, h * 0.16),
      leftBrow: SIMD4(x + w * 0.1, y + h * 0.2, w * 0.34, h * 0.12),
      rightBrow: SIMD4(x + w * 0.56, y + h * 0.2, w * 0.34, h * 0.12),
      nose: SIMD4(x + w * 0.37, y + h * 0.38, w * 0.26, h * 0.3),
      mouth: SIMD4(x + w * 0.25, y + h * 0.68, w * 0.5, h * 0.2)
    )
  }

  private func interpolateMask(from previous: BeautyFaceMask, to next: BeautyFaceMask) -> BeautyFaceMask {
    guard previous.detected, next.detected else { return next }
    let alpha: Float = 0.38
    func mix(_ a: SIMD4<Float>, _ b: SIMD4<Float>) -> SIMD4<Float> {
      a + (b - a) * alpha
    }
    return BeautyFaceMask(
      face: mix(previous.face, next.face),
      leftEye: mix(previous.leftEye, next.leftEye),
      rightEye: mix(previous.rightEye, next.rightEye),
      leftBrow: mix(previous.leftBrow, next.leftBrow),
      rightBrow: mix(previous.rightBrow, next.rightBrow),
      nose: mix(previous.nose, next.nose),
      mouth: mix(previous.mouth, next.mouth),
      timestamp: next.timestamp
    )
  }

  private func resetFaceMask() {
    stateLock.lock()
    currentMask = BeautyFaceMask()
    stateLock.unlock()
    emitFaceState(false)
  }

  private func emitFaceState(_ detected: Bool) {
    guard detected != lastFaceEvent else { return }
    lastFaceEvent = detected
    DispatchQueue.main.async { [weak self] in
      self?.onFaceState(["detected": detected])
    }
  }

  private func emitError(code: String, message: String) {
    DispatchQueue.main.async { [weak self] in
      self?.onError(["code": code, "message": message])
    }
  }

  private func finishCapture(texture: MTLTexture, request: PendingCapture) {
    let width = texture.width
    let height = texture.height
    let bytesPerRow = width * 4
    var bytes = [UInt8](repeating: 0, count: bytesPerRow * height)
    bytes.withUnsafeMutableBytes { pointer in
      if let address = pointer.baseAddress {
        texture.getBytes(
          address,
          bytesPerRow: bytesPerRow,
          from: MTLRegionMake2D(0, 0, width, height),
          mipmapLevel: 0
        )
      }
    }
    guard let provider = CGDataProvider(data: Data(bytes) as CFData) else {
      request.continuation.resume(throwing: BeautyCameraNativeError.captureFailed)
      return
    }
    let bitmapInfo = CGBitmapInfo.byteOrder32Little.union(
      CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue)
    )
    guard let cgImage = CGImage(
      width: width,
      height: height,
      bitsPerComponent: 8,
      bitsPerPixel: 32,
      bytesPerRow: bytesPerRow,
      space: CGColorSpaceCreateDeviceRGB(),
      bitmapInfo: bitmapInfo,
      provider: provider,
      decode: nil,
      shouldInterpolate: true,
      intent: .defaultIntent
    ) else {
      request.continuation.resume(throwing: BeautyCameraNativeError.captureFailed)
      return
    }

    let image = UIImage(cgImage: cgImage)
    let scale = min(1, CGFloat(request.maxWidth) / image.size.width)
    let outputSize = CGSize(
      width: max(1, floor(image.size.width * scale)),
      height: max(1, floor(image.size.height * scale))
    )
    let renderer = UIGraphicsImageRenderer(size: outputSize)
    let shouldUnmirror = configuredFacing == "front"
    let outputImage = renderer.image { context in
      if shouldUnmirror {
        context.cgContext.translateBy(x: outputSize.width, y: 0)
        context.cgContext.scaleBy(x: -1, y: 1)
      }
      image.draw(in: CGRect(origin: .zero, size: outputSize))
    }
    guard let jpeg = outputImage.jpegData(compressionQuality: CGFloat(request.quality)) else {
      request.continuation.resume(throwing: BeautyCameraNativeError.captureFailed)
      return
    }

    do {
      let directory = FileManager.default.urls(
        for: .cachesDirectory,
        in: .userDomainMask
      )[0].appendingPathComponent("beauty-camera-native", isDirectory: true)
      try FileManager.default.createDirectory(
        at: directory,
        withIntermediateDirectories: true
      )
      let url = directory.appendingPathComponent("attendance-\(UUID().uuidString).jpg")
      try jpeg.write(to: url, options: .atomic)
      stateLock.lock()
      let smoothingApplied = currentMask.detected
      stateLock.unlock()
      request.continuation.resume(returning: [
        "uri": url.absoluteString,
        "width": Int(outputSize.width),
        "height": Int(outputSize.height),
        "filterApplied": beautyEnabled && configuredFacing == "front",
        "smoothingApplied": beautyEnabled && configuredFacing == "front" && smoothingApplied
      ])
    } catch {
      request.continuation.resume(throwing: error)
    }
  }
}
