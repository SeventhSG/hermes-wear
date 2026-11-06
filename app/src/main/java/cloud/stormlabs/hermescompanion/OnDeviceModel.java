package cloud.stormlabs.hermescompanion;

import android.content.Context;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

final class OnDeviceModel {
    static final String NAME = "Parakeet 110M (English, int8)";
    static final long DOWNLOAD_SIZE = 104337827L;
    private static final String URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8.tar.bz2";
    private static final String SHA256 = "17f945007b52ccd8b7200ffc7c5652e9e8e961dfdf479cefcabd06cf5703630b";
    private static final String[] FILES = {"model.int8.onnx", "tokens.txt"};

    interface Progress { void update(int percent); }
    static File directory(Context context) { return new File(context.getFilesDir(), "parakeet-110m-en"); }
    static boolean installed(Context context) { return present(directory(context), FILES); }

    static void install(Context context, Progress progress) throws Exception {
        fetch(context, directory(context), URL, SHA256, DOWNLOAD_SIZE, FILES, progress);
        delete(new File(context.getFilesDir(), "whisper-tiny")); // the model before 7.58
    }

    static boolean present(File dir, String[] files) {
        for (String name : files) if (!new File(dir, name).isFile()) return false;
        return true;
    }

    /** Downloads a .tar.bz2, checks its SHA-256 and installs only {@code files} into {@code target}. */
    static void fetch(Context context, File target, String url, String sha256, long size, String[] files, Progress progress) throws Exception {
        File archive = new File(context.getCacheDir(), target.getName() + ".tar.bz2");
        File staging = new File(context.getFilesDir(), target.getName() + ".download");
        delete(staging);
        if (!staging.mkdirs()) throw new IllegalStateException("Cannot create model directory");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            try {
                if (conn.getResponseCode() != 200) throw new IllegalStateException("Model download: HTTP " + conn.getResponseCode());
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long received = 0;
                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(archive)) {
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n); digest.update(buffer, 0, n); received += n;
                        progress.update((int) Math.min(99, received * 100 / size));
                    }
                }
                StringBuilder hash = new StringBuilder();
                for (byte b : digest.digest()) hash.append(String.format(java.util.Locale.US, "%02x", b & 255));
                if (!sha256.equals(hash.toString())) throw new IllegalStateException("Model checksum mismatch");
            } finally { conn.disconnect(); }
            try (TarArchiveInputStream tar = new TarArchiveInputStream(new BZip2CompressorInputStream(new java.io.BufferedInputStream(new FileInputStream(archive), 1 << 16)))) {
                TarArchiveEntry entry; int found = 0;
                while (found < files.length && (entry = tar.getNextTarEntry()) != null) {
                    String name = entry.getName();
                    for (String expected : files) if (name.equals(expected) || name.endsWith("/" + expected)) {
                        if (!entry.isFile()) throw new IllegalStateException("Invalid model file");
                        try (FileOutputStream out = new FileOutputStream(new File(staging, expected))) {
                            byte[] buffer = new byte[65536]; int n;
                            while ((n = tar.read(buffer)) != -1) out.write(buffer, 0, n);
                        }
                        found++;
                    }
                }
            }
            if (!present(staging, files)) throw new IllegalStateException("Model archive is incomplete");
            delete(target);
            if (!staging.renameTo(target)) throw new IllegalStateException("Cannot install model");
            progress.update(100);
        } finally { archive.delete(); delete(staging); }
    }

    static void delete(Context context) { delete(directory(context)); }
    static void delete(File path) {
        if (path.isDirectory()) {
            File[] children = path.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        path.delete();
    }
}
