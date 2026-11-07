#!/bin/bash
# 构建 AI Commit Message 插件（IntelliJ 平台通用）
# 依赖：IntelliJ IDEA CE 自带的 JBR 21（javac）+ zip；无需 Gradle
#
# 用法：
#   ./build.sh                          # 默认：GoLand 2025.3，since 253 until 253.*
#   IDE_APP=~/Applications/PhpStorm.app SINCE=241 UNTIL=253.* ./build.sh
#   IDEA_APP=/Applications/IntelliJ IDEA CE.app ...  # 编译用 JDK 仍取 IDEA CE 的 JBR
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
JAVAC="/Applications/IntelliJ IDEA CE.app/Contents/jbr/Contents/Home/bin/javac"
IDE_APP="${IDE_APP:-$HOME/Applications/GoLand.app}"
SINCE="${SINCE:-253}"
UNTIL="${UNTIL:-253.*}"
# 2024.1 运行于 JBR 17，字节码统一降到 17 保证全版本可加载
BYTECODE="${BYTECODE:-17}"

IDE_LIB="$IDE_APP/Contents/lib"
VERSION="$(grep -o '<version>[^<]*</version>' "$ROOT/src/main/resources/META-INF/plugin.xml" | head -1 | sed 's/<[^>]*>//g')"

# 编译 classpath：目标 IDE 全部平台 jar + 模块 jar（modules 目录仅 2024.2+ 存在）
CP=$(find "$IDE_LIB" -maxdepth 1 -name '*.jar' | tr '\n' ':')
if [ -d "$IDE_LIB/modules" ]; then
    CP="$CP$(find "$IDE_LIB/modules" -name '*.jar' | tr '\n' ':')"
fi

OUT_NAME="ai-commit-${VERSION}-since${SINCE}"
rm -rf "$ROOT/build/classes" "$ROOT/build/resources" "$ROOT/build/stage" "$ROOT/build/$OUT_NAME.zip"
mkdir -p "$ROOT/build/classes" "$ROOT/build/resources" "$ROOT/build/stage/ai-commit/lib"

find "$ROOT/src/main/java" -name '*.java' > "$ROOT/build/sources.txt"
"$JAVAC" --release "$BYTECODE" -encoding UTF-8 -nowarn -cp "$CP" -d "$ROOT/build/classes" @"$ROOT/build/sources.txt"

# 资源拷贝 + 按目标 IDE 改写兼容范围
cp -R "$ROOT/src/main/resources/." "$ROOT/build/resources/"
sed -i '' "s|since-build=\"[^\"]*\"|since-build=\"$SINCE\"|; s|until-build=\"[^\"]*\"|until-build=\"$UNTIL\"|" \
    "$ROOT/build/resources/META-INF/plugin.xml"

# 组装插件 jar（class + 资源），用 zip 代替 JBR 缺失的 jar 工具
(cd "$ROOT/build/classes" && zip -q -r "$ROOT/build/stage/ai-commit/lib/ai-commit.jar" .)
(cd "$ROOT/build/resources" && zip -q -r "$ROOT/build/stage/ai-commit/lib/ai-commit.jar" .)

# 打成可安装的插件分发包
(cd "$ROOT/build/stage" && zip -q -r "$ROOT/build/$OUT_NAME.zip" .)

echo "构建完成: $ROOT/build/${OUT_NAME}.zip（编译基线: $IDE_APP, since-build=${SINCE}）"
