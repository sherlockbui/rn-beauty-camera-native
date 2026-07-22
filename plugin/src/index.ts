import {
  AndroidConfig,
  createRunOncePlugin,
  IOSConfig,
  type ConfigPlugin,
  withGradleProperties,
  withPodfileProperties,
} from 'expo/config-plugins';

const pkg = require('../../package.json');

type BeautyCameraPluginOptions = {
  cameraPermission?: string | false;
};

const DEFAULT_CAMERA_PERMISSION =
  'Allow $(PRODUCT_NAME) to access your camera for photos.';
const MIN_ANDROID_SDK = 26;
const MIN_IOS_DEPLOYMENT_TARGET = '15.5';

const compareVersions = (left: string, right: string) => {
  const leftParts = left.split('.').map(Number);
  const rightParts = right.split('.').map(Number);
  const length = Math.max(leftParts.length, rightParts.length);

  for (let index = 0; index < length; index += 1) {
    const difference = (leftParts[index] ?? 0) - (rightParts[index] ?? 0);
    if (difference !== 0) return difference;
  }
  return 0;
};

const withMinimumNativeTargets: ConfigPlugin = config => {
  config = withGradleProperties(config, configWithProps => {
    const current = configWithProps.modResults.find(
      item =>
        item.type === 'property' && item.key === 'android.minSdkVersion',
    );
    const currentValue =
      current?.type === 'property' ? Number(current.value) : 0;
    const minimum = Math.max(
      Number.isFinite(currentValue) ? currentValue : 0,
      MIN_ANDROID_SDK,
    );
    configWithProps.modResults =
      AndroidConfig.BuildProperties.updateAndroidBuildProperty(
        configWithProps.modResults,
        'android.minSdkVersion',
        String(minimum),
      );
    return configWithProps;
  });

  return withPodfileProperties(config, configWithProps => {
    const current = configWithProps.modResults['ios.deploymentTarget'];
    const deploymentTarget =
      current && compareVersions(current, MIN_IOS_DEPLOYMENT_TARGET) > 0
        ? current
        : MIN_IOS_DEPLOYMENT_TARGET;
    configWithProps.modResults =
      IOSConfig.BuildProperties.updateIosBuildProperty(
        configWithProps.modResults,
        'ios.deploymentTarget',
        deploymentTarget,
      );
    return configWithProps;
  });
};

const withBeautyCameraNative: ConfigPlugin<BeautyCameraPluginOptions | void> = (
  config,
  {cameraPermission} = {},
) => {
  IOSConfig.Permissions.createPermissionsPlugin({
    NSCameraUsageDescription: DEFAULT_CAMERA_PERMISSION,
  })(config, {
    NSCameraUsageDescription: cameraPermission,
  });

  config = AndroidConfig.Permissions.withPermissions(config, [
    'android.permission.CAMERA',
  ]);

  return withMinimumNativeTargets(config);
};

export default createRunOncePlugin(
  withBeautyCameraNative,
  pkg.name,
  pkg.version,
);
