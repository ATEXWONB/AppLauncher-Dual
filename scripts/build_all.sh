#!/usr/bin/env bash
# 依次构建 5 个发行版 APK
set -e
cd "$(dirname "$0")/.."

echo "=== 1/5 arm64-v8a (内置 libmcmod.so) ==="
ABI_LIST=arm64-v8a MCMOD=on  OUTVER=v1.0 bash scripts/build_apk.sh

echo "=== 2/5 armeabi-v7a ==="
ABI_LIST=armeabi-v7a MCMOD=off OUTVER=v1.0 bash scripts/build_apk.sh

echo "=== 3/5 x86 ==="
ABI_LIST=x86 MCMOD=off OUTVER=v1.0 bash scripts/build_apk.sh

echo "=== 4/5 x86_64 ==="
ABI_LIST=x86_64 MCMOD=off OUTVER=v1.0 bash scripts/build_apk.sh

echo "=== 5/5 universal (libmcmod.so 默认 ARM64) ==="
ABI_LIST=arm64-v8a,armeabi-v7a,x86,x86_64 MCMOD=on OUTVER=v1.0-universal bash scripts/build_apk.sh

echo "=== 完成 ==="
ls -l out/
