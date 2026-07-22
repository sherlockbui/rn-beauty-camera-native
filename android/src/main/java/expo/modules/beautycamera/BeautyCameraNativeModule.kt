package expo.modules.beautycamera

import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class BeautyCameraNativeModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("BeautyCameraNative")

    View(BeautyCameraNativeView::class) {
      Events("onReady", "onError", "onFaceState")

      Prop("active") { view, active: Boolean ->
        view.active = active
      }

      Prop("facing") { view, facing: String ->
        view.facing = facing
      }

      Prop("beautyEnabled") { view, enabled: Boolean ->
        view.beautyEnabled = enabled
      }

      Prop("smoothingStrength") { view, strength: Double ->
        view.smoothingStrength = strength.toFloat().coerceIn(0f, 0.3f)
      }

      Prop("slimFaceStrength") { view, strength: Double ->
        view.slimFaceStrength = strength.toFloat().coerceIn(0f, 1f)
      }

      Prop("enlargeEyesStrength") { view, strength: Double ->
        view.enlargeEyesStrength = strength.toFloat().coerceIn(0f, 1f)
      }

      Prop("noseSlimStrength") { view, strength: Double ->
        view.noseSlimStrength = strength.toFloat().coerceIn(0f, 1f)
      }

      Prop("enableTorch") { view, enabled: Boolean ->
        view.enableTorch = enabled
      }

      OnViewDestroys { view ->
        view.dispose()
      }

      AsyncFunction("capture") { view: BeautyCameraNativeView, options: Map<String, Double>, promise: Promise ->
        view.capture(
          maxWidth = (options["maxWidth"] ?: 1024.0).toInt(),
          quality = (options["quality"] ?: 0.8).coerceIn(0.1, 1.0),
          promise = promise
        )
      }
    }
  }
}
