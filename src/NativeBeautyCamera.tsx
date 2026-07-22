import {
  requireNativeViewManager,
  requireOptionalNativeModule,
} from 'expo-modules-core';
import React, {forwardRef, useImperativeHandle, useRef} from 'react';
import type {NativeSyntheticEvent, ViewProps} from 'react-native';

export type NativeBeautyCameraFacing = 'front' | 'back';

export type NativeBeautyCameraCapture = {
  uri: string;
  width: number;
  height: number;
  filterApplied: boolean;
  smoothingApplied: boolean;
};

export type NativeBeautyCameraError = {
  code: string;
  message: string;
};

export type NativeBeautyCameraFaceState = {
  detected: boolean;
};

export type NativeBeautyCameraProps = ViewProps & {
  active: boolean;
  facing: NativeBeautyCameraFacing;
  beautyEnabled: boolean;
  smoothingStrength: number;
  slimFaceStrength?: number;
  enlargeEyesStrength?: number;
  noseSlimStrength?: number;
  enableTorch?: boolean;
  onReady?: () => void;
  onError?: (error: NativeBeautyCameraError) => void;
  onFaceState?: (state: NativeBeautyCameraFaceState) => void;
};

export type NativeBeautyCameraRef = {
  capture(options?: {
    maxWidth?: number;
    quality?: number;
  }): Promise<NativeBeautyCameraCapture>;
};

type NativeViewRef = {
  capture(options: {
    maxWidth: number;
    quality: number;
  }): Promise<NativeBeautyCameraCapture>;
};

type NativeViewProps = Omit<
  NativeBeautyCameraProps,
  'onError' | 'onFaceState'
> & {
  onError?: (
    event: NativeSyntheticEvent<NativeBeautyCameraError>,
  ) => void;
  onFaceState?: (
    event: NativeSyntheticEvent<NativeBeautyCameraFaceState>,
  ) => void;
};

const nativeModule = requireOptionalNativeModule('BeautyCameraNative');
export const isNativeBeautyCameraAvailable = nativeModule != null;

const NativeView = isNativeBeautyCameraAvailable
  ? (requireNativeViewManager(
      'BeautyCameraNative',
    ) as React.ForwardRefExoticComponent<
      NativeViewProps & React.RefAttributes<NativeViewRef>
    >)
  : null;

export const NativeBeautyCamera = forwardRef<
  NativeBeautyCameraRef,
  NativeBeautyCameraProps
>(function NativeBeautyCamera(
  {onError, onFaceState, ...props},
  forwardedRef,
) {
  const nativeRef = useRef<NativeViewRef>(null);

  useImperativeHandle(
    forwardedRef,
    () => ({
      capture: options => {
        if (!nativeRef.current) {
          return Promise.reject(
            new Error('BeautyCameraNative view is not mounted.'),
          );
        }
        return nativeRef.current.capture({
          maxWidth: options?.maxWidth ?? 1024,
          quality: options?.quality ?? 0.8,
        });
      },
    }),
    [],
  );

  if (!NativeView) return null;

  return (
    <NativeView
      {...props}
      ref={nativeRef}
      onError={event => onError?.(event.nativeEvent)}
      onFaceState={event => onFaceState?.(event.nativeEvent)}
    />
  );
});


