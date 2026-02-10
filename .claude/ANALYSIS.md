# Code Analysis — miniRAT

> Post-fix status report. Lists resolved issues and remaining recommendations.

---

## Resolved Issues ✅

| # | Issue | Fix Applied |
|---|---|---|
| BUG-001 | URL path mismatch | Fixed to `/api/upload/thumbnail` |
| BUG-002 | Service crash on Android 8+ | `startForeground()` with API check |
| BUG-003 | Class name mismatch | Class renamed to `Service` |
| BUG-004 | No foreground service type (Android 14+) | Added `dataSync` type |
| BUG-005 | Uploads stop when screen off | `WakeLock` acquired per scan cycle |
| BUG-006 | Images lost when server is down | Server reachability check before scanning |
| BUG-007 | Re-uploads everything on reboot | SharedPreferences deduplication |
| BUG-008 | New photos never captured | Periodic 15-min scan cycle |
| SEC-001 | JSON injection | Uses `org.json.JSONObject` |
| PERF-001 | Full-res bitmap loaded | `inSampleSize` for downsampled decoding |
| CQ-001 | Bitmaps never recycled | `bitmap.recycle()` after use |
| CQ-004 | Deprecated `MediaStore.Images.Media.DATA` | ContentUris + ParcelFileDescriptor |
| CQ-005 | Cursor not closed in `finally` | Proper `finally` cleanup |
| DEAD | `MainActivity.java` unused | Deleted |
| DEAD | Unused permissions | Removed |

---

## Remaining Issues

### Security

| # | Severity | Issue |
|---|---|---|
| SEC-002 | 🟡 Medium | No certificate pinning (MITM risk) |
| SEC-003 | 🟠 High | Server path traversal (`/api/fullsize/:filename`) |
| SEC-004 | 🟠 High | No server authentication |

### Server-Side

| # | Severity | Issue |
|---|---|---|
| SRV-001 | ⚪ Low | Multer configured but unused |
| SRV-002 | 🟡 Medium | No `express.json()` body size limit |
| SRV-003 | ⚪ Low | `writeFileSync` blocks event loop |

### Cleanup

| Item | Status |
|---|---|
| Compose dependencies in `build.gradle.kts` | Unused — could be removed |
| Kotlin theme files (`ui/theme/*.kt`) | Unused Compose theme |
