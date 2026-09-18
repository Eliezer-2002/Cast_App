# TCCA - Tamil Christian Casting App — APK Build Guide

## Prerequisites

| Tool | Version | Download |
|------|---------|----------|
| Android Studio | Latest (Hedgehog+) | https://developer.android.com/studio |
| JDK | 11 or 17 | Bundled with Android Studio |
| Android SDK | API 35 | Via SDK Manager in Android Studio |

---

## Method 1: Android Studio (Recommended)

### Step 1 — Open Project
1. Launch Android Studio
2. Click **File → Open**
3. Navigate to and select the `TheBook` folder
4. Click **OK** and wait for Gradle sync to complete

### Step 2 — Accept SDK Licenses (if prompted)
```
Tools → SDK Manager → SDK Tools → Accept All
```

### Step 3 — Build Debug APK
```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```
APK location: `TheBook/app/build/outputs/apk/debug/app-debug.apk`

### Step 4 — Build Release APK (for distribution)
1. **Build → Generate Signed Bundle / APK**
2. Choose **APK**
3. Create a new keystore or use existing
4. Set alias, passwords, validity (25+ years)
5. Choose **release** build variant
6. Click **Finish**

APK location: `TheBook/app/build/outputs/apk/release/app-release.apk`

---

## Method 2: Command Line

### Prerequisites
```bash
# Install Java 11+
sudo apt install openjdk-11-jdk   # Linux
# or download from https://adoptium.net

# Set ANDROID_HOME
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools
```

### Build Commands
```bash
cd TheBook

# First time: download Gradle wrapper
./gradlew wrapper --gradle-version=8.9

# Build debug APK
./gradlew assembleDebug

# Build release APK (unsigned)
./gradlew assembleRelease

# Clean build
./gradlew clean assembleDebug
```

### Sign the APK (for distribution)
```bash
# Create keystore (one time)
keytool -genkey -v -keystore thebook.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias thebook

# Sign APK
jarsigner -verbose -sigalg SHA256withRSA \
  -digestalg SHA-256 \
  -keystore thebook.jks \
  app/build/outputs/apk/release/app-release-unsigned.apk thebook

# Verify
jarsigner -verify -verbose app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## Install on Android Device

### Via USB (ADB)
```bash
# Enable Developer Options on phone:
# Settings → About Phone → tap Build Number 7 times
# Settings → Developer Options → USB Debugging: ON

# Connect phone via USB, then:
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Via File Transfer
1. Copy the `.apk` file to your Android device
2. Open the file manager on your phone
3. Tap the APK file
4. Allow "Install from unknown sources" if prompted
5. Tap **Install**

---

## Troubleshooting

### Gradle sync fails
- Check internet connection (first build downloads dependencies)
- File → Invalidate Caches → Restart

### "SDK not found"
- Open SDK Manager: Tools → SDK Manager
- Install Android SDK Platform 35
- Install Android Build Tools 35.0.0

### Build error: "duplicate class"
- Add to `app/build.gradle` under `android {}`:
  ```groovy
  configurations.all {
      resolutionStrategy.force 'androidx.core:core-ktx:1.13.0'
  }
  ```

### WebView shows blank screen
- Check `file:///android_asset/index.html` path
- Ensure `assets/` folder contains `index.html`
- Check `allowUniversalAccessFromFileURLs = true` in MainActivity

### Bible API not loading
- Confirm internet permission in AndroidManifest.xml
- `MIXED_CONTENT_ALWAYS_ALLOW` is set in WebSettings
- Try loading `https://bolls.life/get-text/KJV/43/3/` in a browser to test

### Tamil font not displaying
- Confirm `fonts/NotoSansTamil-Regular.woff2` exists in `assets/fonts/`
- The `@font-face` in index.html references `fonts/NotoSansTamil-Regular.woff2`

---

## App Structure

```
TheBook/
├── app/
│   └── src/main/
│       ├── assets/
│       │   ├── index.html          ← ENTIRE APP (HTML + CSS + JS)
│       │   └── fonts/
│       │       ├── NotoSansTamil-Regular.woff2
│       │       ├── NotoSansTamil-Bold.woff2
│       │       └── NotoSansTamil-Latin.woff2
│       ├── java/com/tamilsongbook/
│       │   └── MainActivity.java   ← WebView wrapper
│       ├── res/
│       │   ├── mipmap-*/           ← App icons
│       │   └── values/             ← Theme colors
│       └── AndroidManifest.xml
├── build.gradle
├── settings.gradle
└── gradle/wrapper/
```

---

## Cast System Usage

1. **Screen Mirror Setup**: Mirror your phone to TV/laptop via your Android's wireless display (Settings → Connected Devices → Cast / Screen Mirror).

2. **Open Cast**: Tap the **Cast** button in the bottom nav.

3. **Gap Screen**: Shows clock, custom text, or blank between songs.

4. **Songs Tab**: Search song → tap song → tap stanza → phone switches to fullscreen Presenter (what TV shows).

5. **Bible Tab**: Select version/book/chapter → Load → tap any verse → TV shows that verse.

6. **Exit Presenter**: Triple-tap the top-left corner.

7. **Navigate**: Swipe left/right in Presenter to go next/prev stanza or verse.

---

## Data Backup

Songs are stored in **localStorage** in the WebView.

**Export**: Settings → Export Songs → Share JSON file (or copy to clipboard)  
**Import**: Settings → Import Songs → paste JSON  

Keep your exported JSON safe — it's your only backup if you reinstall!

---

## Minimum Requirements

- Android 5.0 (API 21) or higher
- ~15 MB storage
- Internet for loading new Bible chapters (cached permanently after first load)
- John 1, John 3, Psalm 23 (Tamil OVR + KJV) are pre-bundled for offline use
