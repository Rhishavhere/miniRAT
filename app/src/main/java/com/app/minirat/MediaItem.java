package com.app.minirat;

import android.net.Uri;

/**
 * Represents a media item from the device's gallery.
 */
public class MediaItem {
    public final long id;
    public final Uri uri;
    public final String displayName;

    public MediaItem(long id, Uri uri, String displayName) {
        this.id = id;
        this.uri = uri;
        this.displayName = displayName;
    }
}
