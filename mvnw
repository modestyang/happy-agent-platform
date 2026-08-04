#!/bin/sh
set -eu

base_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
java_command="${JAVA_HOME:+$JAVA_HOME/bin/}java"

exec "$java_command" ${MAVEN_OPTS:-} -Dmaven.multiModuleProjectDirectory="$base_dir" -classpath "$base_dir/.mvn/wrapper/maven-wrapper.jar" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"
