#!/bin/bash
set -e
SRC_ROOT="$1"; OUT="$2"; VER="$3"
export ANDROID_NDK_ROOT=$HOME/Library/Android/sdk/ndk/27.1.12297006
export PATH=$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH
cd "$SRC_ROOT"
[ -f openssl-$VER.tar.gz ] || curl -fsSL -o openssl-$VER.tar.gz https://github.com/openssl/openssl/releases/download/openssl-$VER/openssl-$VER.tar.gz
for pair in "armeabi-v7a:android-arm" "arm64-v8a:android-arm64"; do
  ABI=${pair%%:*}; TGT=${pair##*:}
  rm -rf build-$ABI && mkdir build-$ABI && tar xzf openssl-$VER.tar.gz -C build-$ABI --strip-components=1
  ( cd build-$ABI && ./Configure $TGT -D__ANDROID_API__=26 no-shared no-tests no-apps no-docs no-engine no-dso no-ui-console no-module -fPIC >/dev/null && make -j8 build_libs >/dev/null 2>&1 )
  mkdir -p "$OUT/$ABI/lib" && cp build-$ABI/libcrypto.a "$OUT/$ABI/lib/"
  rm -rf "$OUT/$ABI/include" && cp -R build-$ABI/include "$OUT/$ABI/include"
  echo "built $ABI: $(ls -la $OUT/$ABI/lib/libcrypto.a)"
done
echo DONE
