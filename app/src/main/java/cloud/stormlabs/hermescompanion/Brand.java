package cloud.stormlabs.hermescompanion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Settings › Appearance › Branding: your own name and logo for the Hermes agent. Claude Code and Codex keep theirs. */
final class Brand {
    private Brand() {}

    private static Bitmap logo;
    private static long stamp = -1;

    static File file(Context c) { return new File(c.getFilesDir(), "brand-logo.png"); }

    /** Display name for a harness: the custom name stands in for "Hermes" only. */
    static String name(Context c, String type) { return name(new SettingsStore(c).brandName(), type); }
    static String name(String custom, String type) { return "Hermes".equals(type) && custom != null && !custom.trim().isEmpty() ? custom.trim() : type; }

    /** The picked logo, else the build's res/drawable-nodpi/brand_logo (see gradle.properties), else null for the built-in mark. */
    static synchronized Bitmap logo(Context c) {
        File f = file(c);
        long m = f.exists() ? f.lastModified() : 0;
        if (m != stamp) {
            stamp = m;
            int res = c.getResources().getIdentifier("brand_logo", "drawable", c.getPackageName());
            logo = m != 0 ? BitmapFactory.decodeFile(f.getPath()) : res != 0 ? BitmapFactory.decodeResource(c.getResources(), res) : null;
        }
        return logo;
    }

    /** Center-crop the picked image to a 256 px square and keep it in app storage. Call off the main thread. */
    static void setLogo(Context c, Uri uri) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("Not an image");
        int sample = 1;
        while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= 256) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap b;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(in, null, d); }
        if (b == null) throw new IOException("Not an image");
        int s = Math.min(b.getWidth(), b.getHeight());
        Bitmap sq = Bitmap.createScaledBitmap(Bitmap.createBitmap(b, (b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s), 256, 256, true);
        File tmp = new File(c.getFilesDir(), "brand-logo.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { sq.compress(Bitmap.CompressFormat.PNG, 100, out); }
        if (!tmp.renameTo(file(c))) throw new IOException("Could not save the logo");
    }

    static void clearLogo(Context c) { file(c).delete(); }
}
