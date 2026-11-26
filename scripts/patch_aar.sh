#!/usr/bin/env bash
# 给 app/sdk/lib-release.aar 打 Xposed 注入所需的字节码补丁
# 依赖: JDK 17 + gradle 发行版自带的 asm jar
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_BIN="${JAVA_HOME:-.}/bin"
ASM="${ASM_JARS:-}"   # 可选：显式给出 asm jar 路径（用 : 分隔）
if [ -z "$ASM" ]; then
  # 常见位置：gradle 发行版 lib 目录
  for c in "$GRADLE_HOME/lib" "${GRADLE_LIB:-}"; do
    if [ -n "$c" ] && ls "$c"/asm*.jar >/dev/null 2>&1; then
      ASM=$(ls "$c"/asm*.jar | tr '\n' ':')
      break
    fi
  done
fi
if [ -z "$ASM" ]; then
  echo "找不到 asm jar，请设置 ASM_JARS 或 GRADLE_HOME" >&2
  exit 1
fi

AAR="$ROOT/app/sdk/lib-release.aar"
cp -f "$AAR" "$AAR.orig.local"
OUT="$ROOT/tools/patch/.build"
mkdir -p "$OUT"

"$JAVA_BIN/javac" -cp "$ASM" -d "$OUT" "$ROOT"/tools/patch/*.java
"$JAVA_BIN/java" -cp "$ASM:$OUT" PatchWeChatHook "$AAR"
"$JAVA_BIN/java" -cp "$ASM:$OUT" PatchVClient "$AAR"
"$JAVA_BIN/java" -cp "$ASM:$OUT" PatchExtProcess "$AAR"
"$JAVA_BIN/java" -cp "$ASM:$OUT" RenameXposedStubs "$AAR"
echo "补丁完成: $AAR"
