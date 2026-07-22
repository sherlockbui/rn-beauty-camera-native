# @sherlockbui/beauty-camera-native

Ready-to-use GPU beauty camera for Expo and React Native. The package includes
the full-screen React component, native Android/iOS renderer, Expo config plugin
and capture file helpers.

## Requirements

- Expo SDK 55
- React Native 0.83
- React 19
- Android API 26 or newer
- iOS 15.5 or newer
- A development/release build; Expo Go cannot load this native module

## Install

```sh
yarn add @sherlockbui/beauty-camera-native
# or: npm install @sherlockbui/beauty-camera-native
```

Add the config plugin:

```json
{
  "expo": {
    "plugins": [
      [
        "@sherlockbui/beauty-camera-native",
        {
          "cameraPermission": "Allow $(PRODUCT_NAME) to use the camera."
        }
      ]
    ]
  }
}
```

Then regenerate the native project or make a new development build:

```sh
npx expo prebuild
```

The plugin adds the Android/iOS camera permission metadata and enforces Android
min SDK 26 and iOS deployment target 15.5. Runtime permission is requested by
the `BeautyCamera` component.

## Ready-to-use component

```tsx
import BeautyCamera from '@sherlockbui/beauty-camera-native';

export function CameraScreen() {
  return (
    <BeautyCamera
      active
      initialFacing="front"
      allowCameraFlip
      beautyEnabled
      smoothingStrength={0.3}
      slimFaceStrength={0}
      enlargeEyesStrength={0}
      noseSlimStrength={0}
      maxOutputWidth={1024}
      jpegQuality={0.8}
      onCapture={photo => console.log(photo.uri)}
      onCancel={() => {}}
      onError={error => console.warn(error.code, error.message)}
    />
  );
}
```

`BeautyCamera` includes permission, camera preview, face status, Natural filter,
torch, camera flip, capture, preview, retake and confirmation UI. Pass
`active={false}` when its screen is not focused.

The confirmed capture result is:

```ts
type BeautyCameraResult = {
  uri: string;
  width: number;
  height: number;
  facing: 'front' | 'back';
  filterApplied: boolean;
  smoothingApplied: boolean;
};
```

Temporary captures are stored in the app cache. Delete a discarded capture with:

```ts
import {deleteBeautyCameraFile} from '@sherlockbui/beauty-camera-native';

deleteBeautyCameraFile(photo.uri);
```

## Low-level native view

Use `NativeBeautyCamera` when the app provides its own camera UI:

```tsx
import React, {useRef} from 'react';
import {StyleSheet} from 'react-native';
import {
  NativeBeautyCamera,
  type NativeBeautyCameraRef,
} from '@sherlockbui/beauty-camera-native';

export function CustomCamera() {
  const cameraRef = useRef<NativeBeautyCameraRef>(null);

  return (
    <NativeBeautyCamera
      ref={cameraRef}
      style={StyleSheet.absoluteFill}
      active
      facing="front"
      beautyEnabled
      smoothingStrength={0.3}
      onReady={() => {}}
      onFaceState={({detected}) => console.log({detected})}
      onError={({code, message}) => console.warn(code, message)}
    />
  );
}
```

The low-level ref exposes `capture({maxWidth, quality})`.

## Native implementation

- iOS: `AVCaptureVideoDataOutput -> CVMetalTextureCache -> Metal -> MTKView`
- Android: `CameraX -> SurfaceTexture/OES -> OpenGL ES 3 -> GLSurfaceView`
- Face analysis stays on-device
- Preview frames remain on the GPU path; pixel readback happens only on capture

## Release checks

```sh
yarn build
yarn typecheck
npm pack --dry-run
```

Native behavior and shader output should be verified on physical Android and iOS
devices before releasing a new version.
