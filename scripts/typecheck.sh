#!/usr/bin/env bash
# 对本项目源码做 javac 类型检查。
# 本仓库无法用 gradle 构建（loom 1.18.2 要求 JVM 25，环境只有 Java 21），
# 所以用 loom 缓存里 remap 好的 jar 拼 classpath 手动编译。详见 docs/redstone-unit-testing-plan.md。
#
# 两处 gradle 缓存缺一不可：
#   /tmp/gh          —— mixin、brigadier、datafixerupper、netty、guava…
#   ~/.gradle        —— gson、unpick、fabric-api 元数据…
#
# 用法: scripts/typecheck.sh
# 可用环境变量覆盖: LOOM_CACHE / GH_CACHE / GRADLE_CACHE / OUT_DIR

set -u

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
LOOM=${LOOM_CACHE:-$ROOT/.gradle/loom-cache}
OUT=${OUT_DIR:-/tmp/wrt-out}
GH_CACHE=${GH_CACHE:-/tmp/gh/caches/modules-2/files-2.1}
GRADLE_CACHE=${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}

fail() { echo "typecheck: $*" >&2; exit 1; }

[ -d "$LOOM/minecraftMaven" ] || fail "找不到 loom 缓存 $LOOM（需先在有 JVM 25 的环境跑一次 gradle）"
[ -d "$GH_CACHE" ] || fail "找不到 $GH_CACHE（mixin/brigadier/datafixerupper 只在这里）"
[ -d "$GRADLE_CACHE" ] || fail "找不到 $GRADLE_CACHE（gson/unpick 在这里）"

MC=$(find "$LOOM/minecraftMaven/net/minecraft" -maxdepth 4 -name 'minecraft-common-*.jar' ! -name '*sources*' | head -1)
MCC=$(find "$LOOM/minecraftMaven/net/minecraft" -maxdepth 4 -name 'minecraft-clientOnly-*.jar' ! -name '*sources*' | head -1)
[ -n "$MC" ] || fail "找不到 minecraft-common jar"
[ -n "$MCC" ] || fail "找不到 minecraft-clientOnly jar"

CP="$MC:$MCC"
CP="$CP:$(find "$LOOM/remapped_mods" -name '*.jar' ! -name '*sources*' | tr '\n' ':')"
CP="$CP$(find "$GH_CACHE" -name '*.jar' ! -name '*sources*' 2>/dev/null | tr '\n' ':')"
CP="$CP$(find "$GRADLE_CACHE" -name '*.jar' ! -name '*sources*' 2>/dev/null | tr '\n' ':')"

rm -rf "$OUT"
mkdir -p "$OUT"

# -proc:none 必须加，否则 Mixin 注解处理器因缺混淆映射而误报。
javac -proc:none -nowarn --release 21 -d "$OUT" -cp "$CP" \
    $(find "$ROOT/src/main/java" "$ROOT/src/client/java" -name '*.java')
status=$?

if [ "$status" -eq 0 ]; then
    echo "typecheck: OK（$OUT）"
else
    echo "typecheck: 失败，退出码 $status" >&2
fi
exit $status
