# Code Analysis — miniRAT

> Post-fix status report. Lists resolved issues and remaining recommendations.

---

## Resolved Issues ✅

| # | Issue | Fix Applied |
|---|---|---|
| BUG-001 | URL path mismatch (`/upload/thumbnail` vs `/api/upload/thumbnail`) | Fixed to `/api/upload/thumbnail` |
| BUG-002 | Service crash on Android 8+ (no foreground service) | `startForeground()` enabled with API check |
| BUG-003 | Class name `ParasiteService` ≠ file name `Service.java` | Class renamed to `Service` |
| SEC-001 | JSON injection via string concatenation | Uses `org.json.JSONObject` |
| PERF-001 | Full-res bitmap loaded before thumbnailing | `inSampleSize` for downsampled decoding |
| CQ-001 | Bitmaps never recycled | `bitmap.recycle()` after use |
| CQ-004 | Deprecated `MediaStore.Images.Media.DATA` column | Uses `ContentUris` + `ParcelFileDescriptor` |
| CQ-005 | Cursor not closed in `finally` block | Proper `finally` cleanup |
| COMPAT-001 | Scoped storage broken on Android 10+ | ContentUri-based access |
| COMPAT-002 | No runtime permission handling | `HiddenActivity` requests at launch |
| COMPAT-003 | Missing `READ_MEDIA_IMAGES` for Android 13+ | Added to manifest |
| DEAD | `MainActivity.java` unused | Deleted |
| DEAD | `WRITE_EXTERNAL_STORAGE`, `WAKE_LOCK` permissions | Removed |
| — | Only queried JPEG + MP4 | Queries all image types (no MIME filter) |
| — | Periodic 30s scan re-uploaded everything | One-time scan per service start |
| — | App visible in drawer permanently | Hides after first launch |

---

## Remaining Issues

### Security

| # | Issue | Severity | Notes |
|---|---|---|---|
| SEC-002 | No certificate pinning | 🟡 Medium | MITM can intercept uploads over untrusted networks |
| SEC-003 | Server path traversal risk (`/api/fullsize/:filename`) | 🟠 High | User input in `path.join()` without validation |
| SEC-004 | No authentication on server | 🟠 High | Anyone can view/upload to the C2 |

### Performance

| # | Issue | Severity | Notes |
|---|---|---|---|
| PERF-002 | No upload deduplication | 🟡 Medium | Reboots re-upload entire gallery |
| PERF-003 | No connection pooling (raw HttpURLConnection) | ⚪ Low | OkHttp would be more efficient |

### Server-Side

| # | Issue | Severity | Notes |
|---|---|---|---|
| SRV-001 | Multer configured but unused | ⚪ Low | Dead dependency |
| SRV-002 | No request body size limit | 🟡 Medium | DoS via large payloads |
| SRV-003 | `writeFileSync` blocks event loop | ⚪ Low | Should use async `fs.promises` |

### Cleanup

| Item | Status |
|---|---|
| Compose dependencies in `build.gradle.kts` | Unused — could be removed to shrink APK |
| Kotlin theme files (`ui/theme/*.kt`) | Unused Compose theme — could be deleted |
| Kotlin plugin applied but no Kotlin source | Could switch to pure Java build |
