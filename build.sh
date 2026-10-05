#!/bin/bash

set -e

echo "🚀 Inizio build di Book Radar Android..."

export JAVA_HOME="/home/ar3ac/.android_toolchain/jdk-17"
export PATH="$JAVA_HOME/bin:/home/ar3ac/.android_toolchain/gradle-8.5/bin:$PATH"
export ANDROID_HOME="/home/ar3ac/.android_toolchain/sdk"

echo "📦 Compilazione dell'APK in corso..."
gradle assembleDebug

if [ -d "/home/Dropbox" ]; then
    echo "📂 Copia del nuovo APK in /home/Dropbox..."
    cp app/build/outputs/apk/debug/app-debug.apk /home/Dropbox/book-radar-debug.apk
fi

echo "🎉 Build completata con successo!"
