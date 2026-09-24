#!/usr/bin/env sh
# 极简 gradlew：若无 wrapper jar，则退回系统 gradle
# GitHub Actions 的 ubuntu-latest 已自带 gradle
DIR=$(cd "$(dirname "$0")" && pwd)

if [ -f "$DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -classpath "$DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
fi

if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

echo "No gradlew jar and no system gradle. Please install gradle." >&2
exit 1