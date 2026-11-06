package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Share-sheet target: whatever you share from another app becomes an Essentials capture.
 *
 * Registered for SEND and SEND_MULTIPLE. Text goes up as a note; a stream is copied into the cache
 * directory first (the backend infers image/audio/video from the stored file) and deleted again
 * afterwards. The user confirms the preview before anything is uploaded.
 */
public class ShareActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView status, setupButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.legacyWindowInsets(this);
        Intent intent = getIntent();
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            finish();
            return;
        }
        try {
            buildUi();
            handle(intent);
        } catch (Throwable t) {
            CrashReporter.show(this, t);
        }
    }

    private void handle(Intent intent) {
        String action = intent == null ? null : intent.getAction();
        String text = intent == null ? null : intent.getStringExtra(Intent.EXTRA_TEXT);
        String subject = intent == null ? null : intent.getStringExtra(Intent.EXTRA_SUBJECT);

        final ArrayList<Uri> uris = new ArrayList<>();
        if (intent != null) {
            if (Intent.ACTION_SEND.equals(action)) {
                Uri one = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (one != null) uris.add(one);
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (many != null) uris.addAll(many);
            }
        }

        // The capture lane's own settings only. Falling back to the chat lane's endpoint/key here
        // would quietly file captures against an API that knows nothing about them.
        SettingsStore store = new SettingsStore(this);
        final String base = store.essentialsBase();
        final String token = store.essentialsToken();
        if (base == null || base.trim().isEmpty() || token == null || token.trim().isEmpty()) {
            setupButton.setVisibility(View.VISIBLE);
            fail("Essentials isn't set up yet.\n\nAdd the capture URL and token, then share again.");
            return;
        }

        final String body = text == null ? null : text.trim();
        final boolean hasText = body != null && !body.isEmpty();
        if (!hasText && uris.isEmpty()) {
            fail("Nothing to save — this share carried no text and no file.");
            return;
        }

        // Provider queries (Drive etc.) can be slow: build the preview off the UI thread.
        io.submit(() -> {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < Math.min(uris.size(), 3); i++) names.add(fileName(uris.get(i)));
            String source = sourceLabel();
            String preview = (source == null ? "" : "From " + source + "\n\n")
                    + SharePreview.text(body, names, uris.size());
            runOnUiThread(() -> {
                if (isFinishing()) return;
                say("Review this share before saving.");
                Sheets.confirm(this, "Save to Essentials?", preview, "Save", false, () -> {
                    say(hasText && uris.isEmpty() ? "Saving note…"
                            : "Saving " + uris.size() + " file" + (uris.size() == 1 ? "" : "s") + "…");
                    io.submit(() -> {
                        List<File> copied = new ArrayList<>();
                        try {
                            for (Uri uri : uris) {
                                File local = copyIn(uri);
                                if (local != null) copied.add(local);
                            }
                            new EssentialsApi(base.trim(), token.trim())
                                    .upload(UUID.randomUUID().toString(),
                                            copied.isEmpty() ? "text" : "file",
                                            hasText ? body : null,
                                            subject,
                                            copied,
                                            null);
                            runOnUiThread(() -> {
                                Toast.makeText(this, "Saved to Essentials", Toast.LENGTH_SHORT).show();
                                finish();
                            });
                        } catch (Throwable t) {
                            runOnUiThread(() -> CrashReporter.show(this, t));
                        } finally {
                            for (File f : copied) {
                                try { f.delete(); } catch (Throwable ignored) { }
                            }
                        }
                    });
                }, this::finish);
            });
        });
    }

    private String fileName(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (column >= 0 && cursor.getString(column) != null) return cursor.getString(column);
                }
            } catch (RuntimeException ignored) { }
        }
        String name = uri.getLastPathSegment();
        return name == null || name.isEmpty() ? "Shared file" : name;
    }

    private String sourceLabel() {
        String source = getCallingPackage();
        Uri referrer = getReferrer();
        if (source == null && referrer != null && "android-app".equals(referrer.getScheme()))
            source = referrer.getHost();
        if (source == null) return null;
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(source, 0)));
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    /** Copy a shared stream into our cache dir, keeping an extension the server can classify. */
    private File copyIn(Uri uri) {
        String mime = getContentResolver().getType(uri);
        File out = new File(getCacheDir(), "share-" + UUID.randomUUID() + extension(mime, uri));
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream to = new FileOutputStream(out)) {
            if (in == null) return null;
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) to.write(buf, 0, n);
            return out;
        } catch (Throwable t) {
            out.delete();
            throw new IllegalStateException("couldn't read a shared file: " + t);
        }
    }

    private static String extension(String mime, Uri uri) {
        String m = mime == null ? "" : mime.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("jpeg")) return ".jpg";
        if (m.contains("png")) return ".png";
        if (m.contains("webp")) return ".webp";
        if (m.contains("gif")) return ".gif";
        if (m.contains("heic")) return ".heic";
        if (m.contains("mpeg")) return ".mp3";
        if (m.contains("mp4") || m.contains("m4a")) return ".m4a";
        if (m.contains("wav") || m.contains("x-wav")) return ".wav";
        if (m.contains("ogg") || m.contains("opus")) return ".ogg";
        if (m.contains("pdf")) return ".pdf";
        if (m.startsWith("text/")) return ".txt";
        String path = uri == null || uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
        int dot = path.lastIndexOf('.');
        return dot > 0 && path.length() - dot <= 6 ? path.substring(dot) : ".bin";
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.BAR);
        ImageButton back = Ui.icon(this, R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(56)));
        bar.addView(Ui.text(this, "Share to Essentials", 19, Ui.TEXT, true));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));
        LinearLayout center = new LinearLayout(this);
        center.setGravity(Gravity.CENTER);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(20), dp(22), dp(20));
        card.setBackground(Ui.stroke(this, Ui.SURFACE, Ui.LINE, 16));
        card.addView(Ui.text(this, "ESSENTIALS", 11, Ui.BLUE, true));
        status = Ui.text(this, "Saving…", 15, Ui.TEXT, false);
        status.setPadding(0, dp(8), 0, 0);
        card.addView(status);
        setupButton = Ui.text(this, "SET UP", 13, Ui.BLUE, true);
        setupButton.setGravity(Gravity.CENTER);
        setupButton.setMinHeight(dp(48));
        setupButton.setBackground(Ui.ripple(this, Ui.RAISED, 10));
        setupButton.setContentDescription("Set up Essentials");
        setupButton.setVisibility(View.GONE);
        setupButton.setOnClickListener(v -> {
            startActivity(new Intent(this, ConnectionActivity.class));
            finish();
        });
        LinearLayout.LayoutParams setupParams = new LinearLayout.LayoutParams(-1, -2);
        setupParams.topMargin = dp(16);
        card.addView(setupButton, setupParams);
        center.addView(card, new LinearLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.78f), -2));
        root.addView(center, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override public void onBackPressed() { finish(); }

    private void say(String message) {
        if (status != null) status.setText(message);
    }

    private void fail(String message) {
        // A share that could not be filed is an expected outcome (not configured, nothing attached),
        // so it is stated in the card rather than thrown at the crash screen.
        if (status != null) {
            status.setText(message);
            status.setTextColor(Ui.RED);
        }
        if (setupButton.getVisibility() != View.VISIBLE)
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::finish, 3500);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
