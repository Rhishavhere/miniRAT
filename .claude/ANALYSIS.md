# Code Analysis — miniRAT

> Post-fix status. Resolved issues and remaining recommendations.

---

## Resolved Issues ✅

| # | Issue | Fix |
|---|---|---|
| BUG-001 | URL path mismatch | Fixed to `/api/upload/thumbnail` |
| BUG-002 | Foreground service crash (Android 8+) | `startForeground()` with API checks |
| BUG-003 | Class name mismatch | Renamed to `Service` |
| BUG-004 | No foreground service type (Android 14+) | `dataSync` type added |
| BUG-005 | Uploads stop with screen off | WakeLock per scan cycle |
| BUG-006 | Images lost when server down | Server ping before scan |
| BUG-007 | Re-uploads everything on reboot | SharedPreferences dedup |
| BUG-008 | New photos never captured | Periodic 15-min scan |
| BUG-009 | No full-image retrieval | Request queue feature |
| SEC-001 | JSON injection | `org.json.JSONObject` |
| PERF-001 | Full-res bitmap loaded for thumbnails | `inSampleSize` decoding |
| CQ-001 | Bitmaps never recycled | `.recycle()` after use |
| CQ-002 | Monolithic Service.java | Refactored into 5 classes |
| CQ-003 | Server path traversal | `path.basename()` sanitization |

---

## Remaining Issues

| # | Severity | Issue |
|---|---|---|
| SEC-002 | 🟡 Medium | No certificate pinning |
| SEC-003 | 🟠 High | No server authentication |
| SRV-001 | ⚪ Low | Multer configured but unused |
| PERF-002 | 🟡 Medium | `readFullImage()` loads full bitmap into RAM (potential OOM on very large images) |
| CQ-004 | ⚪ Low | Unused Compose dependencies in `build.gradle.kts` |
