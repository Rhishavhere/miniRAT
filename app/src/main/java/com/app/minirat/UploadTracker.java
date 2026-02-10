package com.app.minirat;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * Tracks which MediaStore image IDs have already been uploaded
 * using SharedPreferences for persistence across restarts.
 */
public class UploadTracker {
    private static final String PREFS_NAME = "minirat_prefs";
    private static final String KEY_UPLOADED_IDS = "uploaded_ids";

    private final SharedPreferences prefs;

    public UploadTracker(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Returns the set of already-uploaded MediaStore IDs.
     */
    public Set<String> getUploadedIds() {
        return new HashSet<>(prefs.getStringSet(KEY_UPLOADED_IDS, new HashSet<>()));
    }

    /**
     * Marks a MediaStore ID as uploaded.
     */
    public void markAsUploaded(long mediaId) {
        Set<String> ids = getUploadedIds();
        ids.add(String.valueOf(mediaId));
        prefs.edit().putStringSet(KEY_UPLOADED_IDS, ids).apply();
    }

    /**
     * Returns true if the given ID has already been uploaded.
     */
    public boolean isUploaded(long mediaId) {
        return getUploadedIds().contains(String.valueOf(mediaId));
    }
}
