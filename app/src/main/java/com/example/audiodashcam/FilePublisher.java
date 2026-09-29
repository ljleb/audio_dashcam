package com.example.audiodashcam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;

final class FilePublisher {
    private FilePublisher() {}

    static final class PendingFile implements AutoCloseable {
        private final ContentResolver resolver;
        final Uri uri;
        final String location;
        private boolean committed;

        PendingFile(ContentResolver resolver, Uri uri, String location) {
            this.resolver = resolver;
            this.uri = uri;
            this.location = location;
        }

        OutputStream open() throws IOException {
            OutputStream out = resolver.openOutputStream(uri, "w");
            if (out == null) throw new IOException("Could not open " + uri);
            return out;
        }

        void commit() throws IOException {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) <= 0) {
                throw new IOException("Could not finalize " + uri);
            }
            committed = true;
        }

        @Override public void close() {
            if (!committed) {
                try { resolver.delete(uri, null, null); } catch (Exception ignored) {}
            }
        }
    }

    static PendingFile createAudio(Context context, String fileName, String mimeType) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/AudioDashcam";

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Could not create MediaStore entry for " + fileName);
        return new PendingFile(resolver, uri, "Files app > Downloads > AudioDashcam > " + fileName);
    }

    static void delete(Context context, String uriString) {
        if (uriString == null || uriString.isEmpty()) return;
        try {
            context.getContentResolver().delete(Uri.parse(uriString), null, null);
        } catch (Exception ignored) {}
    }
}
