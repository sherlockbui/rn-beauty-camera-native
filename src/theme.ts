import {Dimensions} from 'react-native';

const {width, height} = Dimensions.get('window');
const GUIDELINE_WIDTH = 375;
const GUIDELINE_HEIGHT = 812;

export const scale = (size: number) => (width / GUIDELINE_WIDTH) * size;
export const verticalScale = (size: number) =>
  (height / GUIDELINE_HEIGHT) * size;
const moderateScale = (size: number, factor = 0.5) =>
  size + (scale(size) - size) * factor;

export const COLORS = {
  primary: '#76ae2f',
  textLight: '#94A3B8',
  warning: '#F59E0B',
  success: '#10B981',
  white: '#FFFFFF',
  black: '#000000',
  overlay: 'rgba(0, 0, 0, 0.5)',
} as const;

export const SPACING = {
  xs: scale(4),
  sm: scale(8),
  md: scale(12),
  lg: scale(16),
  xl: scale(20),
  xxl: scale(24),
} as const;

export const SIZES = {
  iconMd: scale(20),
  iconLg: scale(24),
  iconXl: scale(28),
  iconXxxl: scale(40),
  buttonHeightSm: verticalScale(36),
  buttonHeightMd: verticalScale(44),
  buttonHeightLg: verticalScale(52),
  buttonHeightXl: verticalScale(60),
  radiusXl: scale(16),
  radiusRound: 999,
  borderWidthThin: 1,
  borderWidthThick: 3,
  shadowRadiusMd: scale(8),
  shadowOffsetSm: {width: 0, height: scale(2)},
} as const;

export const TYPOGRAPHY = {
  h3: moderateScale(18),
  bodyMedium: moderateScale(14),
  labelMedium: moderateScale(13),
  labelSmall: moderateScale(12),
  caption: moderateScale(11),
  buttonMedium: moderateScale(14),
  lineHeight: {
    bodyMedium: moderateScale(20),
  },
} as const;
