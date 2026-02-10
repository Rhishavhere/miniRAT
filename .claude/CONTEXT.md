# Project Context — miniRAT

> Developer guide: conventions, codebase navigation, setup, and key design decisions.

---

## Codebase Map

```
miniRAT/
├── app/src/main/
│   ├── AndroidManifest.xml             # Permissions + components
│   └── java/com/app/minirat/
│       ├── HiddenActivity.java         # [ENTRY] Permission → service → hide → finish
│       ├── Service.java                # [CORE] Periodic scan + dedup + upload
│       └── BootReceiver.java           # [PERSISTENCE] Restart on boot
├── server.js                           # C2 server
└── app/local.properties                # DOMAIN_URL config
```

---

## Key Design Decisions

| Decision | Rationale |
|---|---|
| **Periodic 15-min scan** | Catches new photos; retries when server comes online |
| **Server ping before scan** | No wasted battery/CPU when server is unreachable |
| **SharedPreferences dedup** | Prevents re-uploading; survives process restarts |
| **Stop on first failure** | If server goes down mid-scan, don't waste remaining uploads |
| **Thumbnail-only** (128×128) | ~5-15 KB per image; low bandwidth footprint |
| **ContentUris** | Scoped storage compatible on Android 10+ |
| **Single-thread executor** | Sequential uploads; prevents OOM |
| **Per-cycle WakeLock** | Only active during scan; released immediately after |
| **App drawer hiding** | `setComponentEnabledSetting(DISABLED)` after first launch |

---

## Component Roles

| Component | Responsibility | Lifecycle |
|---|---|---|
| `HiddenActivity` | Permission + service start + hide from drawer | First launch only |
| `Service` | Periodic scan → dedup → server check → upload | Runs continuously |
| `BootReceiver` | Restart service after reboot | On boot event |
| `server.js` | Receive thumbnails, serve live dashboard | Always running |

---

## Configuration

| Property | File | Description |
|---|---|---|
| `DOMAIN_URL` | `app/local.properties` | C2 server URL |
| Port `5000` | `server.js` | Server listen port |
| `128×128` px | `Service.java` | Thumbnail resolution |
| JPEG `70%` | `Service.java` | Compression quality |
| `15 min` | `Service.java` | Scan interval (`SCAN_INTERVAL_MS`) |

---

## Conventions

| Area | Convention |
|---|---|
| Error handling | Exceptions caught at method boundaries, logged via `Log.e` |
| Threading | Main thread: lifecycle + Handler timer. Background: single `ExecutorService` |
| JSON | `org.json.JSONObject` for payload construction |
| Bitmap | `inSampleSize` decoding + `recycle()` after use |
| Media access | `ContentUris` + `ParcelFileDescriptor` |
| Deduplication | `SharedPreferences.getStringSet()` for uploaded IDs |
| Server check | HEAD request with 5s timeout |

---

## Gotchas

1. **First launch required** — App must be opened once for `BOOT_COMPLETED` and permissions
2. **Permission denial** — MediaStore returns 0 results (no crash, just no images)
3. **App icon gone** — After first launch, only reinstall or `adb` can re-trigger
4. **SharedPreferences limit** — `StringSet` works fine for ~50K entries; beyond that consider SQLite
5. **`local.properties` domain** — Must be set before building
6. **Server going down mid-scan** — Upload stops, remaining images retry next cycle
7. **OEM battery optimizations** — Some OEMs aggressively kill foreground services
