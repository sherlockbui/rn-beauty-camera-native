import {
  AndroidConfig,
  createRunOncePlugin,
  IOSConfig,
  type ConfigPlugin,
} from 'expo/config-plugins';

const pkg = require('../../package.json');

type BeautyCameraPluginOptions = {
  cameraPermission?: string | false;
};

const DEFAULT_CAMERA_PERMISSION =
  'Allow $(PRODUCT_NAME) to access your camera for photos.';

const withBeautyCameraNative: ConfigPlugin<BeautyCameraPluginOptions | void> = (
  config,
  {cameraPermission} = {},
) => {
  IOSConfig.Permissions.createPermissionsPlugin({
    NSCameraUsageDescription: DEFAULT_CAMERA_PERMISSION,
  })(config, {
    NSCameraUsageDescription: cameraPermission,
  });

  return AndroidConfig.Permissions.withPermissions(config, [
    'android.permission.CAMERA',
  ]);
};

export default createRunOncePlugin(
  withBeautyCameraNative,
  pkg.name,
  pkg.version,
);
