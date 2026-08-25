#!/bin/bash
set -e

# Fixed Termux OpenJDK 17 URL
JDK_URL="https://packages.termux.dev/apt/termux-main/pool/main/o/openjdk-17/openjdk-17_17.0.12-2_aarch64.deb"
SHMEM_URL="https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.3_aarch64.deb"
SPAWN_URL="https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-spawn/libandroid-spawn_0.3_aarch64.deb"
ICONV_URL="https://packages.termux.dev/apt/termux-main/pool/main/libi/libiconv/libiconv_1.17-1_aarch64.deb"
ZLIB_URL="https://packages.termux.dev/apt/termux-main/pool/main/z/zlib/zlib_1.3.1_aarch64.deb"
LIBCXX_URL="https://packages.termux.dev/apt/termux-main/pool/main/l/libc++/libc++_18.1.8_aarch64.deb"

OUT_DIR="java17-build"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
cd "$OUT_DIR"

wget -q "$JDK_URL" -O jdk.deb
wget -q "$SHMEM_URL" -O shmem.deb
wget -q "$SPAWN_URL" -O spawn.deb
wget -q "$ICONV_URL" -O iconv.deb
wget -q "$ZLIB_URL" -O zlib.deb
wget -q "$LIBCXX_URL" -O libcxx.deb

for f in *.deb; do
    ar x "$f"
    tar xf data.tar.xz
done

# Create final runtime structure
mkdir -p runtime
cp -r data/data/com.termux/files/usr/lib/jvm/openjdk-17/* runtime/
mkdir -p runtime/lib/termux
cp data/data/com.termux/files/usr/lib/libandroid-shmem.so runtime/lib/termux/
cp data/data/com.termux/files/usr/lib/libandroid-spawn.so runtime/lib/termux/
cp data/data/com.termux/files/usr/lib/libiconv.so runtime/lib/termux/
cp data/data/com.termux/files/usr/lib/libz.so.1 runtime/lib/termux/
cp data/data/com.termux/files/usr/lib/libc++_shared.so runtime/lib/termux/

# Pack
cd runtime
tar -cJf ../../java17-arm64.tar.xz .
cd ../../
rm -rf "$OUT_DIR"
echo "Built java17-arm64.tar.xz"
