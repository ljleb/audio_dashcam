package com.example.audiodashcam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class FilePublisher {
    private FilePublisher() {}

    static String publishDirectoryToDownloads(Context context, File sourceDir) throws IOException {
        if (!sourceDir.isDirectory()) throw new IOException("Not a directory: " + sourceDir);

        String sessionName = sourceDir.getName();
        String relativePath = "Download/AudioDashcam/" + sessionName;

        File[] files = sourceDir.listFiles();
        if (files == null || files.length == 0) throw new IOException("No files to publish.");

        if (Build.VERSION.SDK_INT >= 29) {
            for (File file : files) {
                if (file.isFile()) publishOneQPlus(context, file, relativePath);
            }
            return "Files app → Downloads → AudioDashcam → " + sessionName;
        } else {
            File outDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "AudioDashcam/" + sessionName);
            if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("Could not create " + outDir);
            for (File file : files) {
                if (file.isFile()) copyFile(file, new File(outDir, file.getName()));
            }
            return outDir.getAbsolutePath();
        }
    }

    private static void publishOneQPlus(Context context, File file, String relativePath) throws IOException {
        ContentResolver resolver = context.getContentResolver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(file.getName()));
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        values.put(MediaStore.MediaColumns.SIZE, file.length());

        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        Uri uri = resolver.insert(collection, values);
        if (uri == null) throw new IOException("Could not create MediaStore entry for " + file.getName());

        try (InputStream in = new FileInputStream(file);
             OutputStream out = resolver.openOutputStream(uri, "w")) {
            if (out == null) throw new IOException("Could not open output stream for " + uri);
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            throw e;
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
    }

    private static String mimeFor(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".raw")) return "application/octet-stream";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }
}
