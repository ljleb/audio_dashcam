package com.example.audiodashcam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

final class FilePublisher {
    private FilePublisher() {}

    static PcmRingBuffer.ExportSession openDownloadsSession(Context context, String sessionName) throws IOException {
        return new MediaStoreSession(context.getContentResolver(), sessionName);
    }

    private static final class MediaStoreSession implements PcmRingBuffer.ExportSession {
        private final ContentResolver resolver;
        private final String relativePath;
        private final String locationDescription;
        private final List<Uri> uris = new ArrayList<>();
        private boolean committed = false;

        MediaStoreSession(ContentResolver resolver, String sessionName) {
            this.resolver = resolver;
            this.relativePath = Environment.DIRECTORY_DOWNLOADS + "/AudioDashcam/" + sessionName;
            this.locationDescription = "Files app > Downloads > AudioDashcam > " + sessionName;
        }

        @Override public OutputStream openFile(String fileName, String mimeType) throws IOException {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Could not create MediaStore entry for " + fileName);

            OutputStream out = resolver.openOutputStream(uri, "w");
            if (out == null) {
                resolver.delete(uri, null, null);
                throw new IOException("Could not open output stream for " + fileName);
            }

            uris.add(uri);
            return out;
        }

        @Override public void commit() throws IOException {
            if (committed) return;

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);

            for (Uri uri : uris) {
                if (resolver.update(uri, done, null, null) <= 0) {
                    throw new IOException("Could not finalize MediaStore entry: " + uri);
                }
            }

            committed = true;
        }

        @Override public String locationDescription() {
            return locationDescription;
        }

        @Override public void close() {
            if (committed) return;

            for (Uri uri : uris) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Exception ignored) {}
            }
        }
    }
}
