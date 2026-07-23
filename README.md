# iBanga

A Kotlin-based Android application for creating and extracting 7z archives using the Banga compression library.

## Features

- **Create Archives** — Compress files and folders into 7z format with configurable compression settings
- **Extract Archives** — Extract 7z archives to a destination of your choice
- **File Picker Integration** — Native file picker for selecting source and destination paths
- **Progress Tracking** — Real-time progress indication during archive and extract operations

## Tech Stack

- **Language:** Kotlin
- **Build System:** Gradle with Kotlin DSL
- **Platform:** Android
- **Archive Format:** 7z via the Banga compression library

## Getting Started

### Prerequisites

- Android Studio Hedgehog or later
- JDK 17+
- Android SDK (API 24+)

### Build

```bash
./gradlew assembleDebug
```

### Run

Open the project in Android Studio, select a target device/emulator, and run the `app` module.

## License

[MIT](LICENSE)
