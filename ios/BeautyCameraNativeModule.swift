import ExpoModulesCore

public final class BeautyCameraNativeModule: Module {
  public func definition() -> ModuleDefinition {
    Name("BeautyCameraNative")

    View(BeautyCameraNativeView.self) {
      Events("onReady", "onError", "onFaceState")

      Prop("active") { (view, active: Bool) in
        view.active = active
      }

      Prop("facing") { (view, facing: String) in
        view.facing = facing
      }

      Prop("beautyEnabled") { (view, enabled: Bool) in
        view.beautyEnabled = enabled
      }

      Prop("smoothingStrength") { (view, strength: Double) in
        view.smoothingStrength = Float(max(0, min(0.3, strength)))
      }

      Prop("slimFaceStrength") { (view, strength: Double) in
        view.slimFaceStrength = Float(max(0, min(1, strength)))
      }

      Prop("enlargeEyesStrength") { (view, strength: Double) in
        view.enlargeEyesStrength = Float(max(0, min(1, strength)))
      }

      Prop("noseSlimStrength") { (view, strength: Double) in
        view.noseSlimStrength = Float(max(0, min(1, strength)))
      }

      Prop("enableTorch") { (view, enabled: Bool) in
        view.torchEnabled = enabled
      }

      AsyncFunction("capture") { (
        view: BeautyCameraNativeView,
        options: [String: Double]
      ) in
        let maxWidth = Int(options["maxWidth"] ?? 1024)
        let quality = options["quality"] ?? 0.8
        return try await view.capture(
          maxWidth: maxWidth,
          quality: quality
        )
      }
    }
  }
}
