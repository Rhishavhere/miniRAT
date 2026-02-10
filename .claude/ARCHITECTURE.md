# Architecture — miniRAT

> System architecture, component interactions, and data flows.

---

## System Overview

```
┌─────────────────────────────────────────────────┐
│                  ANDROID DEVICE                  │
│                                                 │
│  ┌──────────────┐     ┌──────────────────────┐  │
│  │HiddenActivity │────▶│      Service         │  │
│  │  (launcher)   │     │   (one-time scan)    │  │
│  │  + permission │     │                      │  │
│  │  + hide icon  │     │  ┌────────────────┐  │  │
│  └──────────────┘     │  │  MediaStore     │  │  │
│                       │  │  All Images     │  │  │
│  ┌──────────────┐     │  │  (ContentURIs)  │  │  │
│  │ BootReceiver  │────▶│  └───────┬────────┘  │  │
│  │ (reboot)      │     │          │           │  │
│  └──────────────┘     │  ┌───────▼────────┐  │  │
│                       │  │  Thumbnail      │  │  │
│                       │  │  (inSampleSize) │  │  │
│                       │  └───────┬────────┘  │  │
│                       │  ┌───────▼────────┐  │  │
│                       │  │  HTTP POST      │──────────┐
│                       │  │  (JSONObject)   │  │  │    │
│                       │  └────────────────┘  │  │    │
│                       └──────────────────────┘  │    │
└─────────────────────────────────────────────────┘    │
                                                       │
                    HTTPS POST                         │
              /api/upload/thumbnail                    │
                                                       │
┌─────────────────────────────────────────────────┐    │
│               C2 SERVER (Node.js)                │◀───┘
│                                                 │
│  POST /api/upload/thumbnail → decode → save     │
│  GET  /api/thumbnails → list all                │
│  GET  / → Gallery Dashboard (auto-refresh)      │
│                                                 │
│  ./uploads/                                     │
│    ├── {name}_thumb.jpg                         │
│    └── {name}.metadata.json                     │
└─────────────────────────────────────────────────┘
```

---

## Component Map

```
AndroidManifest.xml
│
├── <activity> HiddenActivity          [LAUNCHER, exported=true]
│     ├── Intent Filter: MAIN + LAUNCHER
│     ├── Launch Mode: singleInstance
│     ├── Runtime permission request
│     └── Disables itself after first run (hides from drawer)
│
├── <service> Service                  [enabled=true, exported=false]
│     ├── Foreground service (Android 8+)
│     └── One-time MediaStore scan + upload
│
└── <receiver> BootReceiver            [enabled=true, exported=true]
      └── startForegroundService() on boot
```

---

## Component Dependency Graph

```
User taps app icon (first time only)
        │
        ▼
  HiddenActivity
        │
        ├── requestPermissions() → permission dialog
        ├── startForegroundService(Service.class)
        ├── setComponentEnabledSetting(DISABLED) → vanish from drawer
        └── finish()

  Service.onStartCommand()
        │
        ├── startForeground(notification)
        └── executor.execute(uploadGalleryThumbnails)
              │
              ├── queryImages() → MediaStore (all image types, ContentURIs)
              ├── createThumbnail(uri) → ParcelFileDescriptor + inSampleSize
              └── sendThumbnailToServer() → JSONObject → HTTP POST

  Device Reboot
        │
        ▼
  BootReceiver → startForegroundService(Service.class) → re-scan
```

---

## Threading Model

```
┌──────────────────────────────────┐
│          MAIN THREAD              │
│                                  │
│  Service.onStartCommand()        │
│    └── executor.execute(scan)────────┐
│                                  │   │
└──────────────────────────────────┘   │
                                       │
┌──────────────────────────────────┐   │
│    EXECUTOR THREAD (single)       │◀──┘
│                                  │
│  For each image URI:             │
│    1. openFileDescriptor(uri)    │
│    2. BitmapFactory.decode       │
│       (with inSampleSize)        │
│    3. Scale to 128×128           │
│    4. compress → Base64          │
│    5. HTTP POST (JSONObject)     │
│    6. bitmap.recycle()           │
│                                  │
└──────────────────────────────────┘
```

---

## Data Flow

| Step | Component | Action |
|---|---|---|
| 1 | `Service` | Query `MediaStore.Images` (no MIME filter → all types) |
| 2 | `Service` | Build `ContentUri` from image `_ID` column |
| 3 | `Service` | Open `ParcelFileDescriptor` via `ContentResolver` |
| 4 | `Service` | Decode with `inSampleSize`, scale to 128×128 |
| 5 | `Service` | Compress JPEG 70% → Base64 |
| 6 | `Service` | POST `JSONObject{filename, thumbnail}` to `/api/upload/thumbnail` |
| 7 | `server.js` | Decode Base64, write `_thumb.jpg` + `.metadata.json` |
| 8 | `server.js` | Serve gallery dashboard at `/` |

---

## Build System — Secret Injection

```
local.properties → build.gradle.kts → BuildConfig.DOMAIN_URL → Service.SERVER_URL
```

---

## Persistence

| Mechanism | Survives | How |
|---|---|---|
| `BootReceiver` | Reboot | `BOOT_COMPLETED` → `startForegroundService()` |
| `START_STICKY` | Process kill | OS schedules restart with null intent |
| Foreground Service | Battery optimization | Higher priority than background services |
