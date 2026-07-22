import React, {
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from 'react';
import {
  ActivityIndicator,
  AppState,
  Image,
  InteractionManager,
  Linking,
  Platform,
  Pressable,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import Ionicons from '@expo/vector-icons/Ionicons';
import {useCameraPermissions} from 'expo-camera';
import {
  initialWindowMetrics,
  SafeAreaInsetsContext,
} from 'react-native-safe-area-context';

import {
  isNativeBeautyCameraAvailable,
  NativeBeautyCamera,
  type NativeBeautyCameraError,
  type NativeBeautyCameraRef,
} from './NativeBeautyCamera';
import {deleteBeautyCameraFile} from './file-utils';
import {
  BeautyCameraError,
  type BeautyCameraFacing,
  type BeautyCameraProps,
  type BeautyCameraResult,
} from './types';
import {
  COLORS,
  SIZES,
  SPACING,
  TYPOGRAPHY,
  scale,
  verticalScale,
} from './theme';

const CONTROL_BACKGROUND = 'rgba(0, 0, 0, 0.58)';
const PANEL_BACKGROUND = 'rgba(0, 0, 0, 0.42)';
const SUCCESS_BACKGROUND = 'rgba(16, 185, 129, 0.88)';
const WARNING_BACKGROUND = 'rgba(245, 158, 11, 0.9)';
const GUIDE_SHADOW = 'rgba(0, 0, 0, 0.7)';
const DEFAULT_SMOOTHING_STRENGTH = 0.75;
const DEFAULT_MAX_OUTPUT_WIDTH = 1024;
const DEFAULT_JPEG_QUALITY = 0.8;

const clamp = (value: number, min: number, max: number) =>
  Math.min(max, Math.max(min, value));

const BeautyCamera = ({
  active = true,
  initialFacing = 'front',
  allowCameraFlip = true,
  beautyEnabled = true,
  smoothingStrength = DEFAULT_SMOOTHING_STRENGTH,
  slimFaceStrength = 0,
  enlargeEyesStrength = 0,
  noseSlimStrength = 0,
  maxOutputWidth = DEFAULT_MAX_OUTPUT_WIDTH,
  jpegQuality = DEFAULT_JPEG_QUALITY,
  onCapture,
  onCancel,
  onError,
}: BeautyCameraProps) => {
  const providedInsets = useContext(SafeAreaInsetsContext);
  const insets = providedInsets ??
    initialWindowMetrics?.insets ?? {
      top: Platform.OS === 'android' ? (StatusBar.currentHeight ?? 0) : 0,
      right: 0,
      bottom: 0,
      left: 0,
    };
  const cameraRef = useRef<NativeBeautyCameraRef>(null);
  const currentPreviewUri = useRef<string | null>(null);
  const confirmedUri = useRef<string | null>(null);
  const captureInFlight = useRef(false);
  const confirmInFlight = useRef(false);

  const [permission, requestPermission] = useCameraPermissions();
  const [interactionsFinished, setInteractionsFinished] = useState(false);
  const [appState, setAppState] = useState(AppState.currentState);
  const [facing, setFacing] = useState<BeautyCameraFacing>(initialFacing);
  const [naturalRequested, setNaturalRequested] = useState(beautyEnabled);
  const [hasFace, setHasFace] = useState(false);
  const [cameraReady, setCameraReady] = useState(false);
  const [cameraSessionKey, setCameraSessionKey] = useState(0);
  const [torchEnabled, setTorchEnabled] = useState(false);
  const [isProcessing, setIsProcessing] = useState(false);
  const [isConfirming, setIsConfirming] = useState(false);
  const [preview, setPreview] = useState<BeautyCameraResult | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const effectiveBeauty = facing === 'front' && naturalRequested;
  const isCameraActive =
    active &&
    permission?.granted === true &&
    interactionsFinished &&
    appState === 'active' &&
    preview == null;

  const reportError = useCallback(
    (error: BeautyCameraError) => {
      setErrorMessage(error.message);
      onError?.(error);
    },
    [onError],
  );

  useEffect(() => {
    const task = InteractionManager.runAfterInteractions(() => {
      setInteractionsFinished(true);
    });
    const timer = setTimeout(() => {
      task.cancel();
      setInteractionsFinished(true);
    }, 100);

    return () => {
      clearTimeout(timer);
      task.cancel();
    };
  }, []);

  useEffect(() => {
    const subscription = AppState.addEventListener('change', setAppState);
    return () => subscription.remove();
  }, []);

  useEffect(() => {
    if (permission?.canAskAgain && !permission.granted) {
      requestPermission().catch(error => {
        reportError(
          new BeautyCameraError(
            'permission-denied',
            'Không thể yêu cầu quyền truy cập camera.',
            error,
          ),
        );
      });
    }
  }, [permission?.canAskAgain, permission?.granted, reportError, requestPermission]);

  useEffect(() => {
    setCameraReady(false);
    setHasFace(false);
    if (facing !== 'back') setTorchEnabled(false);
  }, [facing]);

  useEffect(() => {
    return () => {
      if (
        currentPreviewUri.current &&
        currentPreviewUri.current !== confirmedUri.current
      ) {
        deleteBeautyCameraFile(currentPreviewUri.current);
      }
    };
  }, []);

  const handleNativeError = useCallback(
    (error: NativeBeautyCameraError) => {
      if (error.code === 'face-detector') {
        setHasFace(false);
        return;
      }

      setCameraReady(false);
      reportError(
        new BeautyCameraError(
          'camera-unavailable',
          error.message || 'Camera chưa sẵn sàng. Vui lòng thử lại.',
          error,
        ),
      );
    },
    [reportError],
  );

  const handleTakePicture = useCallback(async () => {
    if (!cameraReady || captureInFlight.current || preview) return;

    captureInFlight.current = true;
    setIsProcessing(true);
    setErrorMessage(null);
    try {
      const capture = await cameraRef.current?.capture({
        maxWidth: Math.max(1, Math.round(maxOutputWidth)),
        quality: clamp(jpegQuality, 0.1, 1),
      });
      if (!capture) {
        throw new BeautyCameraError(
          'capture-failed',
          'Camera chưa sẵn sàng. Vui lòng thử lại.',
        );
      }

      const result: BeautyCameraResult = {...capture, facing};
      currentPreviewUri.current = result.uri;
      setPreview(result);
      setCameraReady(false);
    } catch (error) {
      reportError(
        error instanceof BeautyCameraError
          ? error
          : new BeautyCameraError(
              'capture-failed',
              'Không thể chụp ảnh. Vui lòng thử lại.',
              error,
            ),
      );
    } finally {
      captureInFlight.current = false;
      setIsProcessing(false);
    }
  }, [cameraReady, facing, jpegQuality, maxOutputWidth, preview, reportError]);

  const handleRetake = useCallback(() => {
    if (confirmInFlight.current) return;
    deleteBeautyCameraFile(preview?.uri);
    currentPreviewUri.current = null;
    setPreview(null);
    setErrorMessage(null);
    setHasFace(false);
    setCameraSessionKey(current => current + 1);
  }, [preview?.uri]);

  const handleConfirm = useCallback(async () => {
    if (!preview || confirmInFlight.current) return;

    confirmInFlight.current = true;
    setIsConfirming(true);
    setErrorMessage(null);
    try {
      await onCapture(preview);
      confirmedUri.current = preview.uri;
    } catch (error) {
      reportError(
        new BeautyCameraError(
          'file-write-failed',
          'Không thể xác nhận ảnh. Vui lòng thử lại.',
          error,
        ),
      );
    } finally {
      confirmInFlight.current = false;
      setIsConfirming(false);
    }
  }, [onCapture, preview, reportError]);

  const handleCancel = useCallback(() => {
    if (captureInFlight.current || confirmInFlight.current) return;
    deleteBeautyCameraFile(preview?.uri);
    currentPreviewUri.current = null;
    onCancel();
  }, [onCancel, preview?.uri]);

  const handleFlip = useCallback(() => {
    setTorchEnabled(false);
    setHasFace(false);
    setFacing(current => (current === 'front' ? 'back' : 'front'));
    setCameraSessionKey(current => current + 1);
  }, []);

  const handleRetry = useCallback(() => {
    setErrorMessage(null);
    setCameraReady(false);
    setCameraSessionKey(current => current + 1);
  }, []);

  if (permission == null) {
    return (
      <View style={styles.permissionContainer}>
        <ActivityIndicator size="large" color={COLORS.white} />
      </View>
    );
  }

  if (!permission.granted) {
    return (
      <View style={styles.permissionContainer}>
        <Ionicons
          name="camera-outline"
          size={SIZES.iconXxxl}
          color={COLORS.white}
        />
        <Text style={styles.permissionTitle}>Cần quyền truy cập camera</Text>
        <Text style={styles.permissionDescription}>
          Cho phép ứng dụng sử dụng camera để chụp ảnh.
        </Text>
        <Pressable
          style={styles.permissionButton}
          onPress={() =>
            permission.canAskAgain ? requestPermission() : Linking.openSettings()
          }>
          <Text style={styles.permissionButtonText}>
            {permission.canAskAgain ? 'Cho phép camera' : 'Mở cài đặt'}
          </Text>
        </Pressable>
        <Pressable style={styles.cancelPermissionButton} onPress={handleCancel}>
          <Text style={styles.cancelPermissionText}>Hủy</Text>
        </Pressable>
      </View>
    );
  }

  if (!isNativeBeautyCameraAvailable) {
    return (
      <View style={styles.permissionContainer}>
        <Text style={styles.permissionTitle}>Native camera chưa khả dụng</Text>
        <Text style={styles.permissionDescription}>
          Hãy tạo development build mới. BeautyCamera không chạy trong Expo Go.
        </Text>
        <Pressable style={styles.permissionButton} onPress={handleCancel}>
          <Text style={styles.permissionButtonText}>Quay lại</Text>
        </Pressable>
      </View>
    );
  }

  const status = (() => {
    if (facing === 'back') return 'Camera sau · không làm mịn';
    if (!naturalRequested) return 'Đã tắt Natural';
    if (hasFace) return 'Đã nhận diện khuôn mặt';
    return 'Giữ khuôn mặt rõ và đủ sáng';
  })();

  return (
    <View style={styles.container}>
      {!preview && (
        <NativeBeautyCamera
          key={`${facing}-${cameraSessionKey}`}
          ref={cameraRef}
          style={StyleSheet.absoluteFill}
          active={isCameraActive}
          facing={facing}
          beautyEnabled={effectiveBeauty}
          smoothingStrength={clamp(smoothingStrength, 0, 1)}
          slimFaceStrength={clamp(slimFaceStrength, 0, 1)}
          enlargeEyesStrength={clamp(enlargeEyesStrength, 0, 1)}
          noseSlimStrength={clamp(noseSlimStrength, 0, 1)}
          enableTorch={torchEnabled}
          onReady={() => {
            setErrorMessage(null);
            setCameraReady(true);
          }}
          onFaceState={({detected}) => setHasFace(detected)}
          onError={handleNativeError}
        />
      )}

      {preview && (
        <Image
          source={{uri: preview.uri}}
          style={StyleSheet.absoluteFill}
          resizeMode="contain"
        />
      )}

      {!preview && (
        <>
          <View style={[styles.topBar, {top: insets.top + SPACING.sm}]}>
            <Pressable
              accessibilityLabel="Đóng camera"
              style={styles.iconButton}
              onPress={handleCancel}>
              <Ionicons name="close" size={SIZES.iconXl} color={COLORS.white} />
            </Pressable>

            <View style={styles.iconButtonPlaceholder} />

            {facing === 'back' ? (
              <Pressable
                accessibilityLabel="Bật tắt đèn camera"
                style={[styles.iconButton, torchEnabled && styles.iconButtonActive]}
                onPress={() => setTorchEnabled(current => !current)}>
                <Ionicons
                  name={torchEnabled ? 'flash' : 'flash-off'}
                  size={SIZES.iconLg}
                  color={torchEnabled ? COLORS.black : COLORS.white}
                />
              </Pressable>
            ) : (
              <View style={styles.iconButtonPlaceholder} />
            )}
          </View>

          <View style={styles.guideContainer} pointerEvents="none">
            <View
              style={[
                styles.faceGuide,
                hasFace && effectiveBeauty && styles.faceGuideSuccess,
              ]}
            />
          </View>

          <View style={styles.statusArea} pointerEvents="box-none">
            <View
              style={[
                styles.statusBadge,
                hasFace && effectiveBeauty
                  ? styles.statusBadgeSuccess
                  : styles.statusBadgeWarning,
              ]}>
              <Text style={styles.statusText}>{status}</Text>
            </View>
            {errorMessage && <Text style={styles.errorText}>{errorMessage}</Text>}
            {errorMessage && (
              <Pressable style={styles.retryButton} onPress={handleRetry}>
                <Text style={styles.retryButtonText}>Thử lại</Text>
              </Pressable>
            )}
          </View>

          <View
            style={[
              styles.bottomControls,
              {paddingBottom: insets.bottom + SPACING.lg},
            ]}>
            <View style={styles.bottomActionRow}>
              <Pressable
                accessibilityLabel="Bật tắt filter làm đẹp"
                disabled={facing === 'back'}
                style={[
                  styles.sideAction,
                  effectiveBeauty && styles.beautyActionButtonActive,
                  facing === 'back' && styles.buttonDisabled,
                ]}
                onPress={() => setNaturalRequested(current => !current)}>
                <Ionicons
                  name="sparkles"
                  size={SIZES.iconXl}
                  color={effectiveBeauty ? COLORS.warning : COLORS.white}
                />
              </Pressable>

              <Pressable
                accessibilityLabel="Chụp ảnh"
                disabled={!cameraReady || isProcessing}
                style={[
                  styles.captureButton,
                  (!cameraReady || isProcessing) && styles.buttonDisabled,
                ]}
                onPress={handleTakePicture}>
                <View style={styles.captureButtonInner} />
              </Pressable>

              {allowCameraFlip ? (
                <Pressable
                  accessibilityLabel="Chuyển camera"
                  style={styles.sideAction}
                  onPress={handleFlip}>
                  <Ionicons
                    name="camera-reverse-outline"
                    size={SIZES.iconXl}
                    color={COLORS.white}
                  />
                </Pressable>
              ) : (
                <View style={styles.sideActionPlaceholder} />
              )}
            </View>
          </View>
        </>
      )}

      {preview && (
        <View
          style={[
            styles.previewControls,
            {paddingBottom: insets.bottom + SPACING.lg},
          ]}>
          <View style={styles.previewActionRow}>
            <Pressable
              accessibilityLabel="Chụp lại"
              disabled={isConfirming}
              style={styles.retakeButton}
              onPress={handleRetake}>
              <Text style={styles.retakeButtonText}>Chụp lại</Text>
            </Pressable>
            <Pressable
              accessibilityLabel="Xác nhận ảnh"
              disabled={isConfirming}
              style={styles.confirmButton}
              onPress={handleConfirm}>
              {isConfirming ? (
                <ActivityIndicator color={COLORS.white} />
              ) : (
                <>
                  <Ionicons
                    name="checkmark"
                    size={SIZES.iconMd}
                    color={COLORS.white}
                  />
                  <Text style={styles.confirmButtonText}>Xác nhận</Text>
                </>
              )}
            </Pressable>
          </View>
        </View>
      )}

      {isProcessing && (
        <View style={styles.processingOverlay}>
          <ActivityIndicator size="large" color={COLORS.white} />
          <Text style={styles.processingText}>Đang xử lý ảnh...</Text>
        </View>
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: COLORS.black},
  permissionContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: COLORS.black,
    padding: SPACING.xxl,
  },
  permissionTitle: {
    color: COLORS.white,
    fontSize: TYPOGRAPHY.h3,
    fontWeight: '700',
    marginTop: SPACING.lg,
    textAlign: 'center',
  },
  permissionDescription: {
    color: COLORS.textLight,
    fontSize: TYPOGRAPHY.bodyMedium,
    lineHeight: TYPOGRAPHY.lineHeight.bodyMedium,
    marginTop: SPACING.sm,
    textAlign: 'center',
  },
  permissionButton: {
    alignItems: 'center',
    backgroundColor: COLORS.primary,
    borderRadius: SIZES.radiusRound,
    justifyContent: 'center',
    marginTop: SPACING.xxl,
    minHeight: SIZES.buttonHeightMd,
    paddingHorizontal: SPACING.xxl,
  },
  permissionButtonText: {
    color: COLORS.white,
    fontSize: TYPOGRAPHY.buttonMedium,
    fontWeight: '700',
  },
  cancelPermissionButton: {marginTop: SPACING.md, padding: SPACING.md},
  cancelPermissionText: {color: COLORS.textLight, fontSize: TYPOGRAPHY.bodyMedium},
  topBar: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
    left: SPACING.lg,
    position: 'absolute',
    right: SPACING.lg,
    zIndex: 20,
  },
  iconButton: {
    alignItems: 'center',
    backgroundColor: CONTROL_BACKGROUND,
    borderRadius: SIZES.radiusRound,
    height: SIZES.buttonHeightMd,
    justifyContent: 'center',
    width: SIZES.buttonHeightMd,
  },
  iconButtonActive: {backgroundColor: COLORS.white},
  iconButtonPlaceholder: {
    height: SIZES.buttonHeightMd,
    width: SIZES.buttonHeightMd,
  },
  guideContainer: {
    ...StyleSheet.absoluteFillObject,
    alignItems: 'center',
    justifyContent: 'center',
  },
  faceGuide: {
    borderColor: COLORS.white,
    borderRadius: SIZES.radiusRound,
    borderWidth: SIZES.borderWidthThick,
    height: verticalScale(330),
    shadowColor: GUIDE_SHADOW,
    shadowOffset: SIZES.shadowOffsetSm,
    shadowOpacity: 1,
    shadowRadius: SIZES.shadowRadiusMd,
    width: scale(250),
  },
  faceGuideSuccess: {borderColor: COLORS.success},
  statusArea: {
    alignItems: 'center',
    bottom: verticalScale(155),
    left: SPACING.lg,
    position: 'absolute',
    right: SPACING.lg,
  },
  statusBadge: {
    borderRadius: SIZES.radiusRound,
    paddingHorizontal: SPACING.md,
    paddingVertical: SPACING.sm,
  },
  statusBadgeSuccess: {backgroundColor: SUCCESS_BACKGROUND},
  statusBadgeWarning: {backgroundColor: WARNING_BACKGROUND},
  statusText: {
    color: COLORS.white,
    fontSize: TYPOGRAPHY.labelSmall,
    fontWeight: '700',
  },
  errorText: {
    color: COLORS.white,
    fontSize: TYPOGRAPHY.caption,
    marginTop: SPACING.sm,
    textAlign: 'center',
  },
  retryButton: {
    backgroundColor: COLORS.primary,
    borderRadius: SIZES.radiusRound,
    marginTop: SPACING.md,
    paddingHorizontal: SPACING.xl,
    paddingVertical: SPACING.sm,
  },
  retryButtonText: {
    color: COLORS.white,
    fontSize: TYPOGRAPHY.labelMedium,
    fontWeight: '700',
  },
  buttonDisabled: {opacity: 0.45},
  bottomControls: {
    backgroundColor: PANEL_BACKGROUND,
    bottom: 0,
    left: 0,
    paddingHorizontal: SPACING.xxl,
    paddingTop: SPACING.xl,
    position: 'absolute',
    right: 0,
  },
  bottomActionRow: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  captureButton: {
    alignItems: 'center',
    borderColor: COLORS.white,
    borderRadius: SIZES.radiusRound,
    borderWidth: SIZES.borderWidthThick,
    height: scale(78),
    justifyContent: 'center',
    width: scale(78),
  },
  captureButtonInner: {
    backgroundColor: COLORS.white,
    borderRadius: SIZES.radiusRound,
    height: scale(60),
    width: scale(60),
  },
  sideAction: {
    alignItems: 'center',
    backgroundColor: CONTROL_BACKGROUND,
    borderColor: 'rgba(255, 255, 255, 0.3)',
    borderRadius: SIZES.radiusRound,
    borderWidth: SIZES.borderWidthThin,
    height: SIZES.buttonHeightXl,
    justifyContent: 'center',
    width: SIZES.buttonHeightXl,
  },
  beautyActionButtonActive: {
    backgroundColor: 'rgba(245, 158, 11, 0.25)',
    borderColor: COLORS.warning,
  },
  sideActionPlaceholder: {
    height: SIZES.buttonHeightXl,
    width: SIZES.buttonHeightXl,
  },
  previewControls: {
    backgroundColor: PANEL_BACKGROUND,
    bottom: 0,
    left: 0,
    paddingHorizontal: SPACING.lg,
    paddingTop: SPACING.md,
    position: 'absolute',
    right: 0,
  },
  previewActionRow: {flexDirection: 'row', gap: SPACING.md},
  retakeButton: {
    alignItems: 'center',
    backgroundColor: CONTROL_BACKGROUND,
    borderColor: COLORS.white,
    borderRadius: SIZES.radiusXl,
    borderWidth: SIZES.borderWidthThin,
    flex: 1,
    justifyContent: 'center',
    minHeight: SIZES.buttonHeightLg,
  },
  retakeButtonText: {
    color: COLORS.white,
    fontSize: TYPOGRAPHY.buttonMedium,
    fontWeight: '700',
  },
  confirmButton: {
    alignItems: 'center',
    backgroundColor: COLORS.primary,
    borderRadius: SIZES.radiusXl,
    flex: 1,
    flexDirection: 'row',
    gap: SPACING.xs,
    justifyContent: 'center',
    minHeight: SIZES.buttonHeightLg,
  },
  confirmButtonText: {
    color: COLORS.white,
    fontSize: TYPOGRAPHY.buttonMedium,
    fontWeight: '700',
  },
  processingOverlay: {
    ...StyleSheet.absoluteFillObject,
    alignItems: 'center',
    backgroundColor: COLORS.overlay,
    justifyContent: 'center',
    zIndex: 50,
  },
  processingText: {
    color: COLORS.white,
    fontSize: TYPOGRAPHY.bodyMedium,
    fontWeight: '600',
    marginTop: SPACING.md,
  },
});

export default BeautyCamera;
