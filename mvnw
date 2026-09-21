#!/bin/sh
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
###
# Maven Wrapper script for Unix/macOS
# Adapted from https://github.com/apache/maven-wrapper

set -e

MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-$(cd "$(dirname "$0")" && pwd)}"
MAVEN_WRAPPER_PROPERTIES="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"
MAVEN_WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"

if [ -f "$MAVEN_WRAPPER_PROPERTIES" ]; then
    DISTRIBUTION_URL=$(grep "distributionUrl" "$MAVEN_WRAPPER_PROPERTIES" | cut -d'=' -f2- | tr -d '\r')
fi

DISTRIBUTION_URL="${DISTRIBUTION_URL:-https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip}"

MAVEN_HOME="$HOME/.m2/wrapper/dists/$(basename "$DISTRIBUTION_URL" .zip)"

if [ ! -d "$MAVEN_HOME" ]; then
    echo "Downloading Maven from $DISTRIBUTION_URL"
    mkdir -p "$MAVEN_HOME"
    TMPFILE=$(mktemp /tmp/maven-wrapper.XXXXXX.zip)
    if command -v curl > /dev/null 2>&1; then
        curl -fsSL -o "$TMPFILE" "$DISTRIBUTION_URL"
    elif command -v wget > /dev/null 2>&1; then
        wget -q -O "$TMPFILE" "$DISTRIBUTION_URL"
    else
        echo "Error: curl or wget is required to download Maven." >&2
        exit 1
    fi
    unzip -q -o "$TMPFILE" -d "$MAVEN_HOME"
    rm -f "$TMPFILE"
fi

MVN_CMD=$(find "$MAVEN_HOME" -name "mvn" -path "*/bin/mvn" -type f | head -1)

if [ -z "$MVN_CMD" ]; then
    echo "Error: Could not find mvn executable in $MAVEN_HOME" >&2
    exit 1
fi

exec "$MVN_CMD" "$@"
