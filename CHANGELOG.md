# Changelog

## 0.2.3

- Exclude the AutoValue annotation processor from the MediaPipe Android runtime
  classpath so R8 release builds do not resolve compiler-only
  `javax.lang.model` classes.

## 0.2.2

- Rename the package to `@sherlockbui/rn-beauty-camera-native` and the GitHub
  repository to `sherlockbui/rn-beauty-camera-native`.
- Raise the preferred Android preview resolution from 720p to 1080p.
- Replace the strong Gaussian-heavy Android smoothing pass with a clearer,
  edge-aware bilateral filter.
- Keep background pixels unchanged, apply a subtle skin-only fallback while face
  detection warms up, and tune the Natural filter for a visible but balanced result.

## 0.2.1

- Remove the face guide and recognition status badge from the ready-to-use
  `BeautyCamera` UI.
- Keep the native face-analysis, beauty-rendering and capture pipelines
  unchanged.

## 0.2.0

- Export a ready-to-use `BeautyCamera` component with capture, preview, retake,
  confirmation, torch, camera flip and beauty controls.
- Include reusable permission handling and capture cache file helpers previously
  maintained by the consuming app.
- Use the package's native renderer directly so consumers do not inherit the
  app-specific patched VisionCamera fallback stack.
- Remove app-specific theme and navigation imports; camera activity is now
  controlled through the reusable `active` prop.
- Configure the minimum Android and iOS deployment targets through the Expo
  config plugin.

## 0.1.0

- Initial standalone Expo/React Native package.
- Native GPU camera preview and filtered photo capture for Android and iOS.
- Expo config plugin for camera permission metadata.
