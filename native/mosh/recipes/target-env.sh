#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Target flags intentionally retain the pre-refactor optimization policy.
case "$ABI" in
    arm64-v8a) HOST=aarch64-linux-android; CLANG_TARGET=$HOST ;;
    armeabi-v7a) HOST=arm-linux-androideabi; CLANG_TARGET=armv7a-linux-androideabi ;;
    x86) HOST=i686-linux-android; CLANG_TARGET=$HOST ;;
    x86_64) HOST=x86_64-linux-android; CLANG_TARGET=$HOST ;;
    *) echo "Unsupported ABI: $ABI" >&2; exit 1 ;;
esac
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
CC="$TOOLCHAIN/bin/${CLANG_TARGET}${ANDROID_API}-clang"
CXX="$TOOLCHAIN/bin/${CLANG_TARGET}${ANDROID_API}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
COMMON_FLAGS="-fPIC -fPIE -D_FORTIFY_SOURCE=2 -fstack-protector-all -fno-strict-overflow -w"
COMMON_FLAGS="$COMMON_FLAGS -ffile-prefix-map=$WORK_DIR=. -fdebug-prefix-map=$WORK_DIR=. -fmacro-prefix-map=$WORK_DIR=."
CFLAGS="$COMMON_FLAGS -std=gnu17"
CXXFLAGS="$COMMON_FLAGS -std=gnu++17"
ASMFLAGS="--target=$HOST -w -D_FORTIFY_SOURCE=2 -fPIE -fPIC"
LDFLAGS="-pie -Wl,--build-id=none -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
export CC CXX AR RANLIB CFLAGS CXXFLAGS ASMFLAGS LDFLAGS
mkdir -p "$BUILD_DIR" "$INSTALL_DIR"
