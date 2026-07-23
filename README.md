# iBanga

A Kotlin Android app for creating and extracting AES-256 encrypted 7z archives with background processing and system notification progress.

## Features

- **Create Archives** — Package folders into 7z archives (store mode, no compression)
- **Extract Archives** — Extract 7z archives to any destination
- **AES-256 Encryption** — Optional password-based encryption for archived content
- **Background Processing** — Archive/extract operations run as a foreground service, surviving app close
- **Notification Progress** — Status bar notification with progress bar, current file name, and Cancel action
- **Result Notification** — Persistent notification on completion, even when the app is closed
- **File Picker Integration** — Native Storage Access Framework pickers for source/destination paths
- **Password Persistence** — Optionally remember passwords across sessions

## Tech Stack

- **Language:** Kotlin
- **Build System:** Gradle with Kotlin DSL
- **Platform:** Android 8.0+ (API 26), targeting 34
- **Archive Format:** 7z via Apache Commons Compress (COPY mode, no compression)
- **Encryption:** AES-256 (provided by Commons Compress / Bouncy Castle)
- **UI:** Material 3 (XML layouts, no Jetpack Compose)

## Getting Started

### Prerequisites

- Android Studio Hedgehog or later
- JDK 17+
- Android SDK (API 26+)

### Build

```bash
# Debug APK
./gradlew assembleDebug

# Signed release APK (requires a keystore)
./gradlew assembleRelease
```

### Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Select **Archive** or **Extract** mode
2. Pick the source folder (Archive) or .7z file (Extract) using the folder/file picker
3. Choose the destination path
4. Optionally set a password
5. Tap **Execute** — the operation starts and a notification appears in the status bar
6. The operation continues in the background even if you leave the app
7. Cancel at any time via the in-app button or the notification action

## License

[MIT](LICENSE)
