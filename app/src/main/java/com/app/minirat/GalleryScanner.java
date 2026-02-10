package com.app.minirat;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Handles MediaStore queries, thumbnail creation, and full image reading.
 */
public class GalleryScanner {
    private static final String TAG = "GalleryScanner";
    private final ContentResolver resolver;

    public GalleryScanner(ContentResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Queries MediaStore for all images on the device.
     * Returns newest first.
     */
    public ArrayList<MediaItem> getAllImages() {
        ArrayList<MediaItem> items = new ArrayList<>();
        Cursor cursor = null;
        try {
            String[] projection = {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE
            };

            cursor = resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC"
            );

            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    String name = cursor.getString(nameCol);
                    Uri uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    items.add(new MediaItem(id, uri, name));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying images", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return items;
    }

    /**
     * Finds a MediaItem by display name.
     * Returns null if not found.
     */
    public MediaItem findByName(String displayName) {
        Cursor cursor = null;
        try {
            String[] projection = {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME
            };

            cursor = resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    MediaStore.Images.Media.DISPLAY_NAME + " = ?",
                    new String[]{displayName},
                    null
            );

            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
                Uri uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                return new MediaItem(id, uri, displayName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding image by name: " + displayName, e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    /**
     * Creates a 128x128 JPEG thumbnail of the given content URI.
     * Returns Base64-encoded string, or null on failure.
     */
    public String createThumbnail(Uri contentUri) throws IOException {
        // First pass: decode bounds only
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;

        ParcelFileDescriptor pfd = resolver.openFileDescriptor(contentUri, "r");
        if (pfd == null) return null;
        BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, opts);
        pfd.close();

        int targetSize = 128;
        int inSampleSize = 1;
        if (opts.outHeight > targetSize || opts.outWidth > targetSize) {
            int halfH = opts.outHeight / 2;
            int halfW = opts.outWidth / 2;
            while ((halfH / inSampleSize) >= targetSize
                    && (halfW / inSampleSize) >= targetSize) {
                inSampleSize *= 2;
            }
        }

        // Second pass: decode with inSampleSize
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = inSampleSize;

        pfd = resolver.openFileDescriptor(contentUri, "r");
        if (pfd == null) return null;
        Bitmap bitmap = BitmapFactory.decodeFileDescriptor(
                pfd.getFileDescriptor(), null, opts);
        pfd.close();

        if (bitmap == null) return createPlaceholder();

        // Scale to 128x128
        float scale = Math.min(
                (float) targetSize / bitmap.getWidth(),
                (float) targetSize / bitmap.getHeight());

        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);

        Bitmap thumbnail = Bitmap.createBitmap(bitmap, 0, 0,
                bitmap.getWidth(), bitmap.getHeight(), matrix, true);

        if (thumbnail != bitmap) bitmap.recycle();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        thumbnail.compress(Bitmap.CompressFormat.JPEG, 70, baos);
        byte[] bytes = baos.toByteArray();
        baos.close();
        thumbnail.recycle();

        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    /**
     * Reads the full image from a content URI.
     * Returns Base64-encoded string, or null on failure.
     */
    public String readFullImage(Uri contentUri) {
        try {
            ParcelFileDescriptor pfd = resolver.openFileDescriptor(contentUri, "r");
            if (pfd == null) return null;

            // Decode full bitmap (no downsampling)
            Bitmap bitmap = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
            pfd.close();

            if (bitmap == null) return null;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
            byte[] bytes = baos.toByteArray();
            baos.close();
            bitmap.recycle();

            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Error reading full image", e);
            return null;
        }
    }

    private String createPlaceholder() {
        Bitmap placeholder = Bitmap.createBitmap(128, 128, Bitmap.Config.RGB_565);
        placeholder.eraseColor(0xFFCCCCCC);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        placeholder.compress(Bitmap.CompressFormat.JPEG, 70, baos);
        byte[] bytes = baos.toByteArray();
        try { baos.close(); } catch (IOException ignored) {}

        placeholder.recycle();
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
}
