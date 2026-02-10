# Code Analysis — miniRAT

> Comprehensive code quality audit, bug catalog, security review, and improvement recommendations.

---

## Summary

| Category | Count | Severity |
|---|---|---|
| Critical Bugs | 3 | 🔴 Breaks core functionality |
| Security Vulnerabilities | 4 | 🟠 Exploitable risks |
| Code Quality Issues | 5 | 🟡 Maintainability |
| Performance Issues | 3 | 🟡 Resource waste / crashes |
| Android Compatibility | 3 | 🔴 Affects modern devices |
| Server-Side Issues | 3 | 🟠 Security / correctness |
| Dead Code | 4 | ⚪ Cleanup needed |

---

## Critical Bugs

### BUG-001: URL Path Mismatch — Uploads Always 404

**Severity**: 🔴 Critical  
**Files**: `Service.java` L209, `server.js` L34

Client sends to:
```java
URL url = new URL(SERVER_URL + "/upload/thumbnail");
```

Server listens on:
```javascript
app.post('/api/upload/thumbnail', ...);
```

**Impact**: Every upload returns 404. The entire exfiltration pipeline is broken.

**Fix**: Add `/api` prefix in `Service.java` or remove it from `server.js`.

---

### BUG-002: Service Crashes on Android 8+ (API 26+)

**Severity**: 🔴 Critical  
**Files**: `Service.java` L51, `BootReceiver.java` L22-23

- `startForeground()` is **commented out** in `ParasiteService`
- `BootReceiver` uses `startService()` instead of `startForegroundService()`

**Impact**: Crashes with `IllegalStateException` on ~85% of active Android devices.

**Fix**: Uncomment `startForeground()`, use `startForegroundService()` with API level check in boot receiver.

---

### BUG-003: Class Name vs File Name Mismatch

**Severity**: 🔴 High  
**File**: `Service.java`

File is `Service.java` but class is `ParasiteService`. Java requires the public class name to match the filename. The manifest refers to `.Service` but the class is `ParasiteService`.

**Fix**: Rename file to `ParasiteService.java`, update manifest to `android:name=".ParasiteService"`.

---

## Security Vulnerabilities

### SEC-001: JSON Injection via Filenames

**File**: `Service.java` L218-219

Filenames concatenated directly into JSON without escaping:
```java
String jsonPayload = "{\"filename\":\"" + fileName + "\",\"thumbnail\":\"" + thumbnailBase64 + "\"}";
```

**Fix**: Use `org.json.JSONObject` for proper JSON construction.

### SEC-002: No Certificate Pinning

**File**: `Service.java` L210

Standard `HttpURLConnection` without certificate pinning. MITM attacks can intercept uploads.

### SEC-003: Server Path Traversal Risk

**File**: `server.js` L117-131

`/api/fullsize/:filename` uses raw user input in `path.join()`. Request to `../../server.js` could read arbitrary files.

**Fix**: Validate resolved path stays within `uploadDir`.

### SEC-004: No Authentication on Server

**File**: `server.js`

No authentication on any route. Anyone discovering the URL can view all exfiltrated thumbnails or upload arbitrary data.

---

## Code Quality Issues

### CQ-001: No Resource Cleanup for Bitmaps

**File**: `Service.java` L156-188

Full-resolution bitmaps decoded but never `recycle()`'d. Causes memory leaks during gallery processing.

### CQ-002: No Error Propagation / Status Reporting

**File**: `Service.java`

All errors silently logged. No retry mechanism, no failure tracking, no operator notification of client health.

### CQ-003: Hardcoded Values

Multiple hardcoded values that should be configurable:
- Scan interval: `30000` ms
- Thumbnail size: `128×128`
- JPEG quality: `70`
- Server port: `3000`

### CQ-004: Deprecated MediaStore.Images.Media.DATA

**File**: `Service.java` L122, L144

The `DATA` column is deprecated on Android 10+. Returns `null` or inaccessible paths under scoped storage.

### CQ-005: No Cursor Null Safety

**File**: `Service.java` L134-149

Column values can be `null` on modern Android, leading to null file paths passed to `BitmapFactory.decodeFile()`.

---

## Performance Issues

### PERF-001: Full Bitmap Decoding Before Thumbnailing

**File**: `Service.java` L158

Full-resolution images loaded into memory before scaling. A 50MP photo = ~183 MB RAM.

**Fix**: Use `BitmapFactory.Options.inSampleSize` for efficient pre-scaling.

### PERF-002: No Upload Deduplication

**File**: `Service.java` L88-114

Every 30s cycle re-uploads the entire gallery. 1000 photos = 1000 HTTP POST requests per cycle.

**Fix**: Track uploaded files via `SharedPreferences` or in-memory `Set<String>`.

### PERF-003: Synchronous HTTP without Connection Pooling

**File**: `Service.java` L207-237

Each upload creates a new `HttpURLConnection`. Use OkHttp for connection reuse.

---

## Android Compatibility Issues

### COMPAT-001: Scoped Storage (Android 10+)

`targetSdk = 36` enforces scoped storage. `MediaStore.Images.Media.DATA` returns inaccessible paths. Must use content URIs with `ContentResolver.openInputStream()`.

### COMPAT-002: Runtime Permissions (Android 6+)

`READ_EXTERNAL_STORAGE` requires runtime permission dialog. App never calls `requestPermissions()` and `HiddenActivity` finishes instantly. Gallery queries return **zero results**.

### COMPAT-003: READ_MEDIA Permissions (Android 13+)

`READ_EXTERNAL_STORAGE` does nothing on Android 13+. Must declare `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO`.

---

## Server-Side Issues

### SRV-001: Multer Configured but Unused

**File**: `server.js` L23-31

Multer imported and configured but no route uses it. Dead dependency.

### SRV-002: No Request Size Limits

**File**: `server.js` L10

`express.json()` without body size limit. Malicious clients can exhaust server memory.

**Fix**: `app.use(express.json({ limit: '1mb' }));`

### SRV-003: Synchronous File Operations

**File**: `server.js` L44-53

`writeFileSync` blocks the event loop during uploads. Use `fs.promises.writeFile()`.

---

## Dead Code & Unused Resources

| Item | File | Status |
|---|---|---|
| `MainActivity.java` | `MainActivity.java` | Dead — finishes immediately, never referenced |
| `createNotification()` | `Service.java` L255-264 | Dead — never called |
| `WRITE_EXTERNAL_STORAGE` | `AndroidManifest.xml` L8 | Declared but unused |
| `WAKE_LOCK` | `AndroidManifest.xml` L9 | Declared but unused |
| Compose dependencies | `build.gradle.kts` | All Compose deps unused (pure Java app) |
| Theme files (Kotlin) | `ui/theme/*.kt` | Compose theme files, never used |
| Test templates | `androidTest/`, `test/` | Template tests, not implemented |
| Multer config | `server.js` L23-31 | Configured but unused |

---

## Recommended Fixes (Priority Order)

### P1 — Make It Work
1. Fix URL mismatch (BUG-001)
2. Rename `Service.java` → `ParasiteService.java` (BUG-003)
3. Update manifest service reference
4. Enable foreground service (BUG-002)

### P2 — Modern Android Support
5. Add runtime permission handling
6. Add scoped storage support
7. Declare `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`

### P3 — Robustness
8. Fix JSON injection (SEC-001)
9. Add upload deduplication (PERF-002)
10. Fix memory issues with `inSampleSize` (PERF-001)
11. Add bitmap recycling (CQ-001)
12. Add server authentication (SEC-004)
13. Add path traversal protection (SEC-003)

### P4 — Cleanup
14. Remove dead code (`MainActivity`, unused permissions, Compose deps)
15. Remove unused Multer from server
16. Add request body size limits
17. Convert server to async file operations
