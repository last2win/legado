#!/usr/bin/env bash
# Build the real app without signing or publishing a GitHub Release.
set -euo pipefail
cd "$(dirname "$0")/../.."
mode="${1:?Expected simulator or device}"
case "$mode" in
  simulator) native_target=ios_simulator_arm64 ;;
  device) native_target=ios_arm64 ;;
  *) echo "Expected simulator or device" >&2; exit 2 ;;
esac

bash scripts/build-ios-native.sh "$native_target"
./gradlew syncIosVersion --console=plain
cd iosApp
xcodegen generate
mkdir -p build/security
signing=(CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY= CODE_SIGNING_REQUIRED=NO
  CODE_SIGNING_ALLOWED=NO DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER=)
if [[ "$mode" == simulator ]]; then
  xcodebuild build -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
    -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath build/security/simulator ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
    "${signing[@]}" 2>&1 | tee build/security/build.log
  app_path=build/security/simulator/Build/Products/Debug-iphonesimulator/legado.app
  test -d "$app_path"
  ditto -c -k --sequesterRsrc --keepParent "$app_path" build/security/legado-simulator.zip
else
  xcodebuild archive -project iosApp.xcodeproj -scheme iosApp -configuration Release \
    -destination 'generic/platform=iOS' -archivePath build/security/legado.xcarchive \
    "${signing[@]}" 2>&1 | tee build/security/build.log
  app_path=build/security/legado.xcarchive/Products/Applications/legado.app
  test -d "$app_path"
  mkdir -p build/security/ipa/Payload
  ditto "$app_path" build/security/ipa/Payload/legado.app
  (cd build/security/ipa && zip -qry ../legado-unsigned.ipa Payload)
fi
