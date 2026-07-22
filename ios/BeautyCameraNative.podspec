require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

Pod::Spec.new do |s|
  s.name           = 'BeautyCameraNative'
  s.version        = package['version']
  s.summary        = package['description']
  s.description    = package['description']
  s.license        = package['license']
  s.author         = package['author']
  s.homepage       = package['homepage']
  s.platforms      = { :ios => '15.5' }
  # CocoaPods replaces this source with the local package path during Expo
  # autolinking. A valid remote descriptor keeps the podspec publishable.
  s.source         = {
    :git => 'https://github.com/sherlockbui/beauty-camera-native.git',
    :tag => "v#{s.version}"
  }
  s.static_framework = true

  s.source_files = '**/*.{h,m,mm,swift}'
  # A static CocoaPods target compiles .metal files into its build-products
  # folder, but does not copy the resulting default.metallib into the host app.
  # Compile the shader inside a resource bundle so it is available at runtime
  # both in the app and when this module is distributed as a package.
  s.resource_bundles = {
    'BeautyCameraNative' => ['BeautyCameraShaders.metal']
  }
  s.frameworks = ['AVFoundation', 'Metal', 'MetalKit', 'CoreVideo', 'CoreImage']
  s.dependency 'ExpoModulesCore'
  s.dependency 'GoogleMLKit/FaceDetection', '9.0.0'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'SWIFT_VERSION' => '5.9',
    'MTL_FAST_MATH' => 'YES'
  }
end
