#!/usr/bin/env zsh

# This file is a modified copy of the original found at https://github.com/ianthetechie/uniffi-starter.

set -e
set -u

# NOTE: You MUST run this every time you make changes to the core. Unfortunately, calling this from Xcode directly
# does not work so well.
#
# Environment:
#   IOS_DIR  convex-swift checkout that receives the XCFramework and the generated Swift bindings
#            (default: ../ios, the submodule).
#
# Requires cmake and libclang (Xcode) for aws-lc-sys, and the aarch64-apple-ios and aarch64-apple-ios-sim
# Rust targets.

# In release mode, we create a ZIP archive of the xcframework and update Package.swift with the computed checksum.
# This is only needed when cutting a new release, not for local development.
release=false

for arg in "$@"
do
    case $arg in
        --release)
            release=true
            shift # Remove --release from processing
            ;;
        *)
            shift # Ignore other argument from processing
            ;;
    esac
done

cd "$(dirname "$0")"
ios_dir="${IOS_DIR:-../ios}"

# Keep build-machine paths out of the shipped binary (panic and log messages embed source paths), so the
# library doesn't leak them and builds on different machines produce the same strings.
cargo_home="${CARGO_HOME:-$HOME/.cargo}"
export RUSTFLAGS="--remap-path-prefix=$cargo_home=/cargo --remap-path-prefix=$(rustc --print sysroot)=/rust --remap-path-prefix=$PWD=/convex-mobile/rust"
export AWS_LC_SYS_CFLAGS="-ffile-prefix-map=$cargo_home=/cargo -ffile-prefix-map=$PWD=/convex-mobile/rust"

echo "Building with $(rustc --version) at convex-mobile $(git rev-parse --short HEAD)$(git diff --quiet HEAD -- . || echo ' (dirty)')"

simulator_lib_dir="target/ios-simulator/release"

generate_ffi() {
  echo "Generating framework module mapping and FFI bindings"
  cargo run --locked --features=uniffi/cli --bin uniffi-bindgen generate --library target/aarch64-apple-ios/release/lib$1.dylib --language swift --out-dir target/uniffi-xcframework-staging
  mkdir -p $ios_dir/Sources/UniFFI/
  mv target/uniffi-xcframework-staging/*.swift $ios_dir/Sources/UniFFI/
  mv target/uniffi-xcframework-staging/$1FFI.modulemap target/uniffi-xcframework-staging/module.modulemap  # Convention requires this have a specific name
}

create_simulator_lib() {
  echo "Creating a library for aarch64 simulator"
  mkdir -p $simulator_lib_dir
  lipo -create target/aarch64-apple-ios-sim/release/lib$1.a -output $simulator_lib_dir/lib$1.a
}

build_xcframework() {
  # Builds an XCFramework
  echo "Generating XCFramework"
  rm -rf target/ios  # Delete the output folder so we can regenerate it
  xcodebuild -create-xcframework \
    -library target/aarch64-apple-ios/release/lib$1.a -headers target/uniffi-xcframework-staging \
    -library target/ios-simulator/release/lib$1.a -headers target/uniffi-xcframework-staging \
    -library target/aarch64-apple-darwin/release/lib$1.a -headers target/uniffi-xcframework-staging \
    -output target/ios/lib$1-rs.xcframework
  rm -rf $ios_dir/lib$1-rs.xcframework
  cp -R target/ios/lib$1-rs.xcframework $ios_dir

  if $release; then
    echo "Building xcframework archive"
    zip -r target/ios/lib$1-rs.xcframework.zip target/ios/lib$1-rs.xcframework
    checksum=$(swift package compute-checksum target/ios/lib$1-rs.xcframework.zip)
    version=$(cargo metadata --format-version 1 | jq -r '.packages[] | select(.name=="foobar") .version')
    sed -i "" -E "s/(let releaseTag = \")[^\"]+(\")/\1$version\2/g" $ios_dir/Package.swift
    sed -i "" -E "s/(let releaseChecksum = \")[^\"]+(\")/\1$checksum\2/g" $ios_dir/Package.swift
  fi
}

# Pin each slice's minimum OS for Rust and for the C code that cc / cmake build (aws-lc, tracing-oslog). Unset, the
# C code targets the SDK's own version (e.g. iOS 27): the device dylib then fails to link (___chkstk_darwin) and the
# library silently raises the minimum OS of every app that uses it. These match Package.swift's platforms, raised to
# the lowest version each arm64 slice supports.
IPHONEOS_DEPLOYMENT_TARGET=14.0 cargo build --locked --lib --release --target aarch64-apple-ios-sim
IPHONEOS_DEPLOYMENT_TARGET=13.0 cargo build --locked --lib --release --target aarch64-apple-ios
MACOSX_DEPLOYMENT_TARGET=11.0 cargo build --locked --lib --release --target aarch64-apple-darwin

basename=convexmobile
generate_ffi $basename
create_simulator_lib $basename
build_xcframework $basename
