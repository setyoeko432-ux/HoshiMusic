#!/bin/sh

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [ -n "$JAVA_HOME" ]; then
    JAVA_EXE="$JAVA_HOME/bin/java"
else
    JAVA_EXE="java"
fi

if ! command -v "$JAVA_EXE" >/dev/null 2>&1; then
    echo "Java tidak ditemukan. Pasang JDK 17 atau atur JAVA_HOME." >&2
    exit 1
fi

exec "$JAVA_EXE" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
