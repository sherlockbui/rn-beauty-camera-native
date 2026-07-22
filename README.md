# @sherlockbui/beauty-camera-native

GPU-accelerated native beauty camera for Expo and React Native. The preview stays
on the native GPU path and JavaScript only receives events and capture results.

## Requirements

- Expo SDK 55 or newer
- React Native 0.83 or newer
- React 19 or newer
- Android min SDK 26
- iOS 15.5 or newer
- A development/release build; Expo Go cannot load this native module

## Install

With Expo:

```sh
npx expo install @sherlockbui/beauty-camera-native
```

Or with Yarn/npm:

```sh
yarn add @sherlockbui/beauty-camera-native
# npm install @sherlockbui/beauty-camera-native
```

Add the config plugin when the installer does not add it automatically:

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

Then regenerate/install the native projects and create a new binary:

```sh
npx expo prebuild
npx pod-install
```

The config plugin adds `android.permission.CAMERA` and
`NSCameraUsageDescription`. Your app must still request camera permission at
runtime before mounting the camera view.

For a bare React Native app, first install and configure Expo Modules, set Android
`minSdkVersion` to at least 26 and the iOS deployment target to at least 15.5,
then run CocoaPods as usual.

## Usage

```tsx
import React, {useRef} from 'react';
import {StyleSheet} from 'react-native';
import {
  NativeBeautyCamera,
  type NativeBeautyCameraRef,
} from '@sherlockbui/beauty-camera-native';

export function CameraScreen() {
  const cameraRef = useRef<NativeBeautyCameraRef>(null);

  const takePhoto = async () => {
    const photo = await cameraRef.current?.capture({
      maxWidth: 1024,
      quality: 0.8,
    });
    console.log(photo?.uri);
  };

  return (
    <NativeBeautyCamera
      ref={cameraRef}
      style={StyleSheet.absoluteFill}
      active
      facing="front"
      beautyEnabled
      smoothingStrength={0.3}
      slimFaceStrength={0}
      enlargeEyesStrength={0}
      noseSlimStrength={0}
      onReady={() => {}}
      onFaceState={({detected}) => console.log({detected})}
      onError={({code, message}) => console.warn(code, message)}
    />
  );
}
```

`capture()` resolves to:

```ts
type NativeBeautyCameraCapture = {
  uri: string;
  width: number;
  height: number;
  filterApplied: boolean;
  smoothingApplied: boolean;
};
```

## Props

| Prop | Type | Notes |
| --- | --- | --- |
| `active` | `boolean` | Starts/stops the camera session. |
| `facing` | `'front' \| 'back'` | Selects the lens. |
| `beautyEnabled` | `boolean` | Enables tone and smoothing on the front camera. |
| `smoothingStrength` | `number` | Clamped to `0...0.3`. |
| `slimFaceStrength` | `number?` | Face warp strength, clamped to `0...1`. |
| `enlargeEyesStrength` | `number?` | Eye warp strength, clamped to `0...1`. |
| `noseSlimStrength` | `number?` | Nose warp strength, clamped to `0...1`. |
| `enableTorch` | `boolean?` | Torch is available on the back camera only. |

## Native implementation

- iOS: `AVCaptureVideoDataOutput -> CVMetalTextureCache -> Metal -> MTKView`
- Android: `CameraX -> SurfaceTexture/OES -> OpenGL ES 3 -> GLSurfaceView`
- Face analysis is local; MediaPipe/ML Kit data is not sent to JavaScript
- Preview frames are not copied to JavaScript; pixel readback happens only for capture

Captured JPEG files are written to the app cache directory. Move or upload a
file before the operating system clears that cache.

## Release checks

```sh
yarn build
npm pack --dry-run
```

Native behavior and shader output should be verified on physical Android and iOS
devices before releasing a new version.
