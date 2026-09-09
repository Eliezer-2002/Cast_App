#!/bin/sh
# Run this script once to download the Gradle wrapper JAR
# Required before first build

echo "Downloading Gradle wrapper JAR..."
curl -L "https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar" \
     -o gradle/wrapper/gradle-wrapper.jar \
     --create-dirs

echo "Done. Now run: ./gradlew assembleDebug"
