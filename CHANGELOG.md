# Changelog

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
