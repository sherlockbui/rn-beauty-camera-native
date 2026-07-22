export type BeautyCameraFacing = 'front' | 'back';

export type BeautyCameraErrorCode =
  | 'permission-denied'
  | 'device-unavailable'
  | 'camera-unavailable'
  | 'capture-failed'
  | 'file-write-failed';

export class BeautyCameraError extends Error {
  code: BeautyCameraErrorCode;
  cause?: unknown;

  constructor(
    code: BeautyCameraErrorCode,
    message: string,
    cause?: unknown,
  ) {
    super(message);
    this.name = 'BeautyCameraError';
    this.code = code;
    this.cause = cause;
  }
}

export type BeautyCameraResult = {
  uri: string;
  width: number;
  height: number;
  facing: BeautyCameraFacing;
  filterApplied: boolean;
  smoothingApplied: boolean;
};

export type BeautyCameraProps = {
  /** Controls whether the camera session is active. */
  active?: boolean;
  initialFacing?: BeautyCameraFacing;
  allowCameraFlip?: boolean;
  beautyEnabled?: boolean;
  smoothingStrength?: number;
  slimFaceStrength?: number;
  enlargeEyesStrength?: number;
  noseSlimStrength?: number;
  maxOutputWidth?: number;
  jpegQuality?: number;
  onCapture: (
    result: BeautyCameraResult,
  ) => void | Promise<void>;
  onCancel: () => void;
  onError?: (error: BeautyCameraError) => void;
};

