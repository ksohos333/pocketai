#!/bin/bash
# Fires when NDK is ready: cross-compile llama-server for arm64, repack embedded APK, install.
set -e
LOG=/tmp/opencode/embedded_build.log
exec > $LOG 2>&1
echo "=== waiting for NDK toolchain ==="
NDK=/home/bpoppa12/pocketai/android-sdk/ndk/29.0.14206865
for i in $(seq 1 120); do
  [ -f "$NDK/build/cmake/android.toolchain.cmake" ] && break
  sleep 10
done
[ -f "$NDK/build/cmake/android.toolchain.cmake" ] || { echo "NDK never arrived"; exit 1; }
echo "=== NDK ready, configuring llama.cpp for arm64 ==="
cmake -S /home/bpoppa12/llama.cpp -B /home/bpoppa12/pocketai/llama-android \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DBUILD_SHARED_LIBS=OFF \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=ON \
  -DGGML_OPENMP=OFF -DCMAKE_BUILD_TYPE=Release
echo "=== building llama-server (arm64 static) ==="
cmake --build /home/bpoppa12/pocketai/llama-android --target llama-server -j 8
BIN=/home/bpoppa12/pocketai/llama-android/bin/llama-server
ls -la $BIN
file $BIN | head -1
echo "=== copying into jniLibs ==="
cp $BIN /home/bpoppa12/pocketai/PocketAI/app/src/embedded/jniLibs/arm64-v8a/libllama_server.so
echo "=== rebuilding embedded APK ==="
export ANDROID_HOME=/home/bpoppa12/pocketai/android-sdk
cd /home/bpoppa12/pocketai/PocketAI
/home/bpoppa12/pocketai/gradle-8.14/bin/gradle :app:assembleEmbeddedDebug
echo "=== installing embedded APK to users 0,10,12 ==="
ADB=/home/bpoppa12/platform-tools/adb
for u in 0 10 12; do
  $ADB install --user $u app/build/outputs/apk/embedded/debug/app-embedded-debug.apk && echo "user $u OK"
done
echo EMBEDDED_ALL_DONE
