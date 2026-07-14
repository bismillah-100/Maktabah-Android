# Maktabah Android

Maktabah is an Android application for reading and managing collections of classical Islamic books. This project is a port of the iOS version, featuring cross-platform synchronization via CloudKit.

[![Sponsor](https://img.shields.io/badge/Sponsor-SociaBuzz-FF6B6B?style=for-the-badge&logo=heart&logoColor=white)](https://sociabuzz.com/ghoysmawahib/donate)

> We are currently raising funds for the **Google Play Console** registration fee so that the app can be officially distributed via the Play Store. Any support, no matter how small, means a lot — see the [Support](#support) section below. Donors will be displayed inside the App when it is released on the Play Store.

## Features

- **Book Library** — Browse and download book collections from a centralized repository
- **Arabic Text Reader** — Comfortable Arabic text rendering with full RTL support
- **Search** — Search text across the entire book collection with Arabic letter normalization
- **Annotations** — Add notes and highlights to text
- **Reading History** — Track the last read page
- **CloudKit Sync** — Sync annotations with iOS via Firebase FCM + CloudKit bridge
- **Automatic Updates** — Check and download the latest version directly from within the app

## Requirements

- Android **8.0 (API 26)** or higher
- Android Studio **Meerkat** or newer
- JDK **17**
- CMake **3.22.1** (for building native SQLite via JNI)

## Installation & Build

### Clone & Open in Android Studio

```bash
git clone https://github.com/bismillah-100/Maktabah-Android.git
```

Open the project in Android Studio, wait for Gradle sync to complete, and run using `Shift + F10`.

### Build via Command Line

```bash
# Debug build
./gradlew assembleDebug

# Install to device/emulator
./gradlew installDebug
```

### `local.properties` Configuration

Create or update the `local.properties` file in the root of the project with the following configuration:

```properties
# Signing (for release build)
signing.storeFile=app/release-key.jks
signing.storePassword=YOUR_STORE_PASSWORD
signing.keyAlias=YOUR_KEY_ALIAS
signing.keyPassword=YOUR_KEY_PASSWORD

# Backend URLs
cloudflare.worker.url=https://your-worker.workers.dev
firebase.rtdb.url=https://your-project-default-rtdb.firebaseio.com

# CloudKit
cloudkit.container.id=iCloud.Maktabah
cloudkit.debug.token=YOUR_DEBUG_TOKEN
cloudkit.release.token=YOUR_RELEASE_TOKEN

# GitHub (default is provided, change only if necessary)
github.kitab.index.url=https://raw.githubusercontent.com/bismillah-100/Kitab/main/index.json
github.kitab.version.url=https://raw.githubusercontent.com/bismillah-100/Kitab/main/version.txt
github.release.base.url=https://github.com/bismillah-100/Kitab/releases/download
github.app.repo=bismillah-100/Maktabah-Android
```

> **Note**: `local.properties` is already added to `.gitignore`. Do not commit this file to the repository.

## Project Structure

```
Maktabah-Android/
├── app/
│   └── src/main/
│       ├── cpp/                  # Native SQLite via JNI (C++)
│       ├── java/com/maktabah/
│       │   ├── cloudKit/         # FCM sync & CloudKit bridge
│       │   ├── database/         # SQLite data access
│       │   ├── downloader/       # Book download & decompression (OkHttp + Zstd)
│       │   ├── manager/          # Library data manager
│       │   ├── models/           # Domain models & data classes
│       │   ├── search/           # Arabic text search logic
│       │   ├── ui/               # Compose screens (library, reader, search, settings, etc.)
│       │   ├── update/           # Automatic app updates
│       │   ├── utils/            # Common utilities (Arabic normalization, etc.)
│       │   └── MainActivity.kt
│       └── res/                  # Resources (layouts, strings, drawables, etc.)
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Architecture

The project uses the **MVVM** (Model-View-ViewModel) pattern:

| Layer | Technology |
|---|---|
| **UI** | Jetpack Compose + Material 3 |
| **State Management** | ViewModel + StateFlow |
| **Database** | SQLite native via JNI |
| **Concurrency** | Kotlin Coroutines |
| **Networking** | OkHttp 5 |
| **Synchronization** | Firebase FCM + CloudKit bridge |
| **Decompression** | Zstd-jni |

## Contribution

1. Fork this repository
2. Create a feature branch: `git checkout -b feature/feature-name`
3. Commit your changes: `git commit -m 'feat: short description'`
4. Push to the branch: `git push origin feature/feature-name`
5. Open a Pull Request

Read [RULES.md](RULES.md) for coding and project architecture guidelines.

## Support

Maktabah is developed and maintained independently, without corporate sponsorship.

Currently, donation funds are prioritized for:

- **Google Play Console Registration** — a one-time fee to officially release this App on the Play Store
- Infrastructure costs (Android-iOS CloudKit synchronization)

Support via SociaBuzz would be greatly appreciated:

[![Sponsor](https://img.shields.io/badge/Donate-SociaBuzz-FF6B6B?style=for-the-badge&logo=heart&logoColor=white)](https://sociabuzz.com/ghoysmawahib/donate)

## License

GPL 3.0. See the `LICENSE` file for details.

## Contact & Technical Support

Found a bug or have questions? Open an [Issue](https://github.com/bismillah-100/Maktabah-Android/issues).