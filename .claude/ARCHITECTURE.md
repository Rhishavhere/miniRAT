# Architecture — miniRAT

> System architecture, component interactions, and data flows.

---

## System Overview

```
┌──────────────────────────────────────────────────────┐
│                    ANDROID DEVICE                     │
│                                                      │
│  ┌──────────────┐     ┌───────────────────────────┐  │
│  │HiddenActivity │────▶│        Service             │  │
│  │  (launcher)   │     │  (periodic scan cycle)     │  │
│  │  + permission │     │                           │  │
│  └──────────────┘     │  ┌─────────────────────┐  │  │
│                       │  │  Every 15 minutes:   │  │  │
│  ┌──────────────┐     │  │                     │  │  │
│  │ BootReceiver  │────▶│  │  1. Ping server     │  │  │
│  │ (reboot)      │     │  │  2. Query MediaStore│  │  │
│  └──────────────┘     │  │  3. Filter uploaded  │  │  │
│                       │  │  4. Upload new only  │  │  │
│                       │  └─────────┬───────────┘  │  │
│                       │            │              │  │
│                       │  ┌─────────▼───────────┐  │  │
│                       │  │  SharedPreferences   │  │  │
│                       │  │  (uploaded IDs)      │  │  │
│                       │  └─────────────────────┘  │  │
│                       └───────────────────────────┘  │
└──────────────────────────────────────────────────────┘
                          │
                   HTTPS POST / HEAD
              /api/upload/thumbnail
                          │
┌──────────────────────────────────────────────────────┐
│                C2 SERVER (Node.js)                    │
│                                                      │
│  HEAD /api/thumbnails → 200 (reachability check)     │
│  POST /api/upload/thumbnail → decode → save          │
│  GET  /api/thumbnails → list all                     │
│  GET  / → Live Gallery Dashboard (3s refresh)        │
│                                                      │
│  ./uploads/                                          │
│    ├── {name}_thumb.jpg                              │
│    └── {name}.metadata.json                          │
└──────────────────────────────────────────────────────┘
```

---

## Scan Cycle Flow

```
┌─────────────────────────────────────────────┐
│           SCAN CYCLE (every 15 min)          │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │  1. isServerReachable()             │    │
│  │     HEAD /api/thumbnails            │    │
│  │     └─ 200-499? → proceed           │    │
│  │     └─ timeout/error? → skip cycle  │    │
│  └──────────────┬──────────────────────┘    │
│                 │ server is up              │
│  ┌──────────────▼──────────────────────┐    │
│  │  2. getUploadedIds()                │    │
│  │     SharedPreferences → Set<String> │    │
│  └──────────────┬──────────────────────┘    │
│                 │                           │
│  ┌──────────────▼──────────────────────┐    │
│  │  3. getGalleryFiles()               │    │
│  │     MediaStore → all image URIs     │    │
│  └──────────────┬──────────────────────┘    │
│                 │                           │
│  ┌──────────────▼──────────────────────┐    │
│  │  4. Filter: skip uploaded IDs       │    │
│  │     newItems = total - uploaded     │    │
│  └──────────────┬──────────────────────┘    │
│                 │                           │
│  ┌──────────────▼──────────────────────┐    │
│  │  5. For each new image:             │    │
│  │     createThumbnail(uri)            │    │
│  │     sendThumbnailToServer()         │    │
│  │     ├─ success → markAsUploaded(id) │    │
│  │     └─ failure → break (stop cycle) │    │
│  └─────────────────────────────────────┘    │
└─────────────────────────────────────────────┘
```

---

## Threading Model

```
┌──────────────────────────────────┐
│          MAIN THREAD              │
│                                  │
│  Handler.postDelayed(15 min)     │
│    └── scanRunnable ─────────────────┐
│                                  │   │
└──────────────────────────────────┘   │
                                       │
┌──────────────────────────────────┐   │
│    EXECUTOR THREAD (single)       │◀──┘
│                                  │
│  acquireWakeLock()               │
│  ├── isServerReachable()?        │
│  │   └── no → return (idle)      │
│  ├── getUploadedIds()            │
│  ├── getGalleryFiles()           │
│  ├── filter new items            │
│  └── for each new image:        │
│      1. openFileDescriptor(uri)  │
│      2. BitmapFactory.decode     │
│      3. Scale to 128×128         │
│      4. compress → Base64        │
│      5. POST (JSONObject)        │
│      6. markAsUploaded(id)       │
│      7. bitmap.recycle()         │
│  releaseWakeLock()               │
└──────────────────────────────────┘
```

---

## Persistence & Deduplication

| Mechanism | Storage | Purpose |
|---|---|---|
| `SharedPreferences` | `minirat_prefs` | Tracks uploaded MediaStore IDs |
| `BootReceiver` | — | Restarts service on reboot |
| `START_STICKY` | — | OS restarts service if killed |
| `WakeLock` | — | Keeps CPU active during each scan cycle |
| Foreground Service | — | Higher priority, survives battery optimization |

---

## Build System — Secret Injection

```
local.properties → build.gradle.kts → BuildConfig.DOMAIN_URL → Service.SERVER_URL
```
