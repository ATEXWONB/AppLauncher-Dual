#!/usr/bin/env bash
# 出单个 ABI 变体的 APK
# 用法: ABI_LIST=arm64-v8a MCMOD=on OUTVER=v1.0 bash scripts/build_apk.sh
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ABI_LIST="${ABI_LIST:-arm64-v8a}"
MCMOD="${MCMOD:-on}"
OUTVER="${OUTVER:-v1.0}"
OUTDIR="${OUTDIR:-$ROOT/out}"
APKNAME="直装分身-$OUTVER-$ABI_LIST.apk"

if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
  echo "请先设置 ANDROID_HOME / ANDROID_SDK_ROOT" >&2
  exit 1
fi
echo "sdk.dir=${ANDROID_HOME:-$ANDROID_SDK_ROOT}" > "$ROOT/local.properties"

if [ "$MCMOD" = "off" ]; then
  rm -f "$ROOT/app/src/main/assets/libmcmod.so"
  echo "libmcmod.so: 已移除（非 arm64 变体）"
fi

bash "$ROOT/scripts/patch_aar.sh"

GRADLE=${GRADLE:-gradle}
"$GRADLE" --no-daemon -p "$ROOT" :app:assembleDebug -Pabi="$ABI_LIST"

mkdir -p "$OUTDIR"
cp -f "$ROOT/app/build/outputs/apk/debug/app-debug.apk" "$OUTDIR/$APKNAME"
ls -l "$OUTDIR/$APKNAME"
