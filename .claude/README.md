# miniRAT

> A minimal Android Remote Access Trojan for educational and security research purposes. Demonstrates covert gallery thumbnail exfiltration to a remote C2 server.

---

## Overview

| Component | Technology | Description |
|---|---|---|
| **Android Client** | Java, Android SDK (API 21–36) | Stealth app that periodically scans the device gallery, checks if the C2 server is reachable, and uploads only new thumbnail previews. Hides from app drawer after first use. |
| **C2 Server** | Node.js, Express.js | Receives uploaded thumbnails, stores on disk, and serves a live gallery dashboard with 3-second auto-refresh. |

---

## Features

### Android Client
- **Zero-UI Launch** — Invisible activity: requests permission, starts service, hides from drawer, finishes.
- **Periodic Scan** — Every 15 minutes, checks for new photos using a Handler-based timer.
- **Server-Aware** — Pings the server (HEAD request) before scanning. If unreachable, stays idle and retries next cycle.
- **Deduplication** — Tracks uploaded MediaStore IDs in SharedPreferences. Never re-uploads the same image.
- **Smart Failure Handling** — If upload fails mid-scan, stops immediately and retries remaining images next cycle.
- **All Image Formats** — Queries MediaStore without MIME filter (JPEG, PNG, WEBP, GIF, HEIC, BMP, etc.)
- **Memory-Efficient** — `inSampleSize` for downsampled decoding + `bitmap.recycle()`.
- **WakeLock** — Keeps CPU active during scan even with screen off. Released immediately after.
- **Foreground Service** — With `dataSync` type for Android 14+ compatibility.
- **Boot Persistence** — `BootReceiver` + `START_STICKY` for maximum uptime.

### C2 Server
- **Thumbnail Receiver** — Accepts Base64-encoded thumbnails via POST, saves as JPEG.
- **Live Dashboard** — Dark-themed gallery with 3-second auto-refresh and live status indicator.
- **Metadata Tracking** — JSON metadata with original filename and upload timestamp.

---

## How It Works

```
Every 15 minutes:
  1. HEAD /api/thumbnails → Server reachable?
     ├── NO  → Skip cycle, retry in 15 min
     └── YES → Continue
  2. Load uploaded IDs from SharedPreferences
  3. Query MediaStore for all images
  4. Filter: new images = total - already uploaded
  5. For each new image:
     ├── Create 128×128 thumbnail (JPEG 70%)
     ├── POST { filename, thumbnail } to server
     ├── Success → mark ID as uploaded
     └── Failure → stop, retry next cycle
```

---

## Permissions

| Permission | API Range | Usage |
|---|---|---|
| `INTERNET` | All | Upload thumbnails to C2 |
| `WAKE_LOCK` | All | CPU active during scan |
| `ACCESS_NETWORK_STATE` | All | Connectivity check |
| `READ_EXTERNAL_STORAGE` | 21–32 | Gallery access via MediaStore |
| `READ_MEDIA_IMAGES` | 33+ | Gallery access on Android 13+ |
| `RECEIVE_BOOT_COMPLETED` | All | Restart on reboot |
| `FOREGROUND_SERVICE` | 26+ | Foreground service |
| `FOREGROUND_SERVICE_DATA_SYNC` | 34+ | Foreground service type |

---

## API Reference

| Method | Endpoint | Description |
|---|---|---|
| `HEAD` | `/api/thumbnails` | Reachability check (used by client) |
| `POST` | `/api/upload/thumbnail` | Upload `{ filename, thumbnail }` |
| `GET` | `/api/thumbnails` | List all thumbnails (newest first) |
| `GET` | `/api/fullsize/:filename` | Serve full-size file |
| `GET` | `/` | Live gallery dashboard |

---

## Disclaimer

> **⚠️ Educational and authorized security research only.**
> Only install on devices you own or have explicit authorization to test.
