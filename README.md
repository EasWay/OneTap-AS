# OneTap - Android App

A smart productivity Android app built with Kotlin and modern Android architecture.

## Features

- **Quick Actions** -- One-tap shortcuts for common tasks
- **Smart Organization** -- Intelligent categorization and sorting
- **Offline Support** -- Works without internet connection
- **Material Design 3** -- Modern, clean UI following Google's design guidelines
- **Dark Mode** -- Full dark theme support

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin |
| Architecture | MVVM + Clean Architecture |
| UI | Jetpack Compose / XML |
| Database | Room (SQLite) |
| Networking | Retrofit + OkHttp |
| DI | Hilt / Dagger |
| Async | Coroutines + Flow |

## Requirements

- Android Studio Hedgehog or later
- JDK 17+
- Android SDK 34+
- Kotlin 1.9+

## Getting Started

```bash
# Clone the repository
git clone https://github.com/EasWay/OneTap-AS.git

# Open in Android Studio
# File > Open > Select the project folder

# Sync Gradle
# Android Studio will automatically sync dependencies

# Run the app
# Select a device/emulator and click Run
```

## Build Variants

| Variant | Description |
|---------|-------------|
| `debug` | Development build with logging |
| `release` | Production build with ProGuard/R8 |

## Project Structure

```
app/
├── src/
│   ├── main/
│   │   ├── java/com/easway/onetap/
│   │   │   ├── data/        # Data layer (API, DB, repositories)
│   │   │   ├── domain/      # Business logic and models
│   │   │   ├── presentation/ # UI layer (screens, viewmodels)
│   │   │   └── di/          # Dependency injection modules
│   │   └── res/             # Resources (layouts, strings, images)
│   ├── test/                # Unit tests
│   └── androidTest/         # Instrumentation tests
└── build.gradle.kts
```

## Backend

OneTap uses a separate backend server: [OneTap-Server](https://github.com/EasWay/OneTap-Server)

## License

MIT

## Author

**Godfred Fokuo** -- [GitHub](https://github.com/EasWay)
