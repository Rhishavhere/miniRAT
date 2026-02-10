# Architecture — miniRAT

> System architecture, component interactions, and data flows.

---

## System Overview

```
┌───────────────────────────────────────────────────────────┐
│                      ANDROID DEVICE                       │
│                                                           │
│  ┌──────────────┐     ┌────────────────────────────────┐  │
│  │HiddenActivity │────▶│          Service                │  │
│  │  (launcher)   │     │  (lifecycle + scan scheduling) │  │
│  └──────────────┘     │                                │  │
│                       │  ┌─────────┐ ┌──────────────┐  │  │
│  ┌──────────────┐     │  │ Gallery │ │  Upload      │  │  │
│  │ BootReceiver  │────▶│  │ Scanner │ │  Tracker     │  │  │
│  │ (reboot)      │     │  └────┬────┘ └──────┬───────┘  │  │
│  └──────────────┘     │       │              │          │  │
│                       │  ┌────▼──────────────▼───────┐  │  │
│                       │  │     NetworkManager        │  │  │
│                       │  │  ping | upload | requests │  │  │
│                       │  └───────────┬───────────────┘  │  │
│                       └──────────────┼──────────────────┘  │
└──────────────────────────────────────┼────────────────────┘
                                       │ HTTPS
┌──────────────────────────────────────┼────────────────────┐
│                   C2 SERVER (Node.js) │                    │
│                                      ▼                    │
│  POST /api/upload/thumbnail  → ./uploads/*_thumb.jpg      │
│  POST /api/upload/fullsize   → ./uploads/fullsize/*       │
│  GET  /api/thumbnails        → list all (+ status)        │
│  GET  /api/requests          → pending request queue      │
│  POST /api/request/:file     → add to queue               │
│  DELETE /api/request/:file   → remove from queue          │
│  GET  /                      → Live Gallery Dashboard     │
└───────────────────────────────────────────────────────────┘
```

---

## Scan Cycle

```
Every 15 min:
│
├── 1. HEAD /api/thumbnails → server reachable?
│       └── no → skip entire cycle, retry later
│
├── 2. uploadNewThumbnails()
│       ├── Query MediaStore (all images)
│       ├── Filter: skip IDs in SharedPreferences
│       ├── For each new → thumbnail → POST → mark uploaded
│       └── First failure? → break, retry next cycle
│
└── 3. fulfillFullImageRequests()
        ├── GET /api/requests → list of filenames
        ├── For each → findByName() → read full image
        ├── POST /api/upload/fullsize → upload
        └── DELETE /api/request/:file → mark fulfilled
```

---

## Class Responsibilities

| Class | File | Responsibility |
|---|---|---|
| `Service` | Service.java | Lifecycle, foreground service, Handler-based scan timer |
| `GalleryScanner` | GalleryScanner.java | MediaStore queries, thumbnail creation, full image reading |
| `NetworkManager` | NetworkManager.java | All HTTP: ping, thumbnail upload, fullsize upload, request queue |
| `UploadTracker` | UploadTracker.java | SharedPreferences for tracking uploaded MediaStore IDs |
| `MediaItem` | MediaItem.java | Data class: id, uri, displayName |
| `HiddenActivity` | HiddenActivity.java | First launch: permission → service → hide icon |
| `BootReceiver` | BootReceiver.java | Restart service on device reboot |

---

## Threading Model

```
MAIN THREAD                    BACKGROUND THREAD
───────────                    ─────────────────
Handler.postDelayed(15min)
   └── scanRunnable ──────────▶ runScanCycle()
                                ├── acquireWakeLock()
                                ├── network.isServerReachable()
                                ├── uploadNewThumbnails()
                                ├── fulfillFullImageRequests()
                                └── releaseWakeLock()
```

---

## Persistence

| What | Where | Purpose |
|---|---|---|
| Uploaded IDs | SharedPreferences (`minirat_prefs`) | Deduplication |
| Thumbnails | `./uploads/*_thumb.jpg` | Server disk |
| Full images | `./uploads/fullsize/*` | Server disk |
| Metadata | `./uploads/*.metadata.json` | Timestamps |
| Request queue | `./uploads/requests.json` | Persistent queue |
