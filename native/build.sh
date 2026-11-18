#!/bin/sh
# Builds the static arm64 helper and places it where Gradle packages native libs.
# Named lib*.so so Android extracts it into the app's nativeLibraryDir (executable).
set -e
cd "$(dirname "$0")"
aarch64-linux-gnu-gcc -O2 -static -Wall -Wextra -o ../app/src/main/jniLibs/arm64-v8a/libtitanpad.so titanpad.c
aarch64-linux-gnu-strip ../app/src/main/jniLibs/arm64-v8a/libtitanpad.so
echo built
