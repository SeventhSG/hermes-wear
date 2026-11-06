package cloud.stormlabs.hermescompanion;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;

/**
 * Shizuku user service, running as the shell user (like adb or scrcpy). Only the shell user may create a
 * trusted virtual display other apps can be launched on, so Hermes' hidden screen lives here: apps run on it
 * while the person keeps using the real screen. Same display flags as scrcpy's --new-display.
 */
public class AgentDisplayService extends IAgentDisplay.Stub {
    private final Context base;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Image last;

    public AgentDisplayService() { this(null); }
    public AgentDisplayService(Context context) { base = context; }

    @Override public synchronized int create(int width, int height, int dpi) {
        if (display != null) return display.getDisplay().getDisplayId();
        try {
            thread = new HandlerThread("agent-display"); thread.start();
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            reader.setOnImageAvailableListener(r -> {
                Image img = r.acquireLatestImage();
                if (img == null) return;
                synchronized (AgentDisplayService.this) { if (last != null) last.close(); last = img; }
            }, new Handler(thread.getLooper()));
            int flags = 1 /*PUBLIC*/ | 8 /*OWN_CONTENT_ONLY*/ | 64 /*SUPPORTS_TOUCH*/ | 128 /*ROTATES_WITH_CONTENT*/ | 256 /*DESTROY_CONTENT_ON_REMOVAL*/;
            if (Build.VERSION.SDK_INT >= 33) flags |= (1 << 10) /*TRUSTED*/ | (1 << 11) /*OWN_DISPLAY_GROUP*/ | (1 << 12) /*ALWAYS_UNLOCKED*/ | (1 << 13) /*TOUCH_FEEDBACK_DISABLED*/;
            if (Build.VERSION.SDK_INT >= 34) flags |= (1 << 14) /*OWN_FOCUS*/ | (1 << 15) /*DEVICE_DISPLAY_GROUP*/;
            Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            DisplayManager dm = ctor.newInstance(new ShellContext(base));
            display = dm.createVirtualDisplay("hermes-agent", width, height, dpi, reader.getSurface(), flags);
            if (display == null) return -1;
            int id = display.getDisplay().getDisplayId();
            fullscreen(id);
            return id;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Samsung treats extra displays like DeX and opens apps as pop-up windows; ask for full-screen apps instead. */
    private static void fullscreen(int displayId) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "window");
            Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
            Object wm = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            wm.getClass().getMethod("setWindowingMode", int.class, int.class).invoke(wm, displayId, 1 /*WINDOWING_MODE_FULLSCREEN*/);
        } catch (Throwable ignored) { /* not available: apps stay in pop-up windows, which still work */ }
    }

    @Override public String exec(String command) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            StringBuilder b = new StringBuilder();
            try (InputStream in = p.getInputStream(); InputStream err = p.getErrorStream()) {
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) > 0 && b.length() < 64_000) b.append(new String(buf, 0, n));
                while ((n = err.read(buf)) > 0 && b.length() < 64_000) b.append(new String(buf, 0, n));
            }
            p.waitFor();
            return b.toString();
        } catch (Throwable t) { return "error: " + t.getMessage(); }
    }

    @Override public synchronized byte[] screenshot(int quality) {
        if (last == null) return new byte[0];
        try {
            Image.Plane plane = last.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int w = last.getWidth(), h = last.getHeight(), rowPadding = plane.getRowStride() - plane.getPixelStride() * w;
            Bitmap bmp = Bitmap.createBitmap(w + rowPadding / plane.getPixelStride(), h, Bitmap.Config.ARGB_8888);
            buffer.rewind(); bmp.copyPixelsFromBuffer(buffer);
            Bitmap crop = Bitmap.createBitmap(bmp, 0, 0, w, h);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            crop.compress(Bitmap.CompressFormat.JPEG, Math.max(30, Math.min(95, quality)), out);
            return out.toByteArray();
        } catch (Throwable t) { return new byte[0]; }
    }

    @Override public synchronized void release() {
        if (display != null) { display.release(); display = null; }
        if (last != null) { last.close(); last = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (thread != null) { thread.quitSafely(); thread = null; }
    }

    @Override public void destroy() { release(); System.exit(0); }

    /**
     * Manual check from adb, as the shell user like Shizuku runs it:
     * adb shell CLASSPATH=$(pm path com.stormlabs.hermescompanion | cut -d: -f2) app_process / cloud.stormlabs.hermescompanion.AgentDisplayService
     */
    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
        AgentDisplayService s = new AgentDisplayService(system);
        int id = s.create(720, 1560, 320);
        System.out.println("display " + id);
        System.out.println(s.exec("am start --display " + id + " --windowingMode 1 -n com.android.settings/.Settings"));
        Thread.sleep(3000);
        System.out.println("tap: " + s.exec("input -d " + id + " tap 360 700"));
        Thread.sleep(2500);
        byte[] jpeg = s.screenshot(70);
        if (jpeg.length > 0) try (java.io.FileOutputStream f = new java.io.FileOutputStream("/data/local/tmp/agent-display.jpg")) { f.write(jpeg); }
        System.out.println("screenshot bytes " + jpeg.length);
        Thread.sleep(args.length > 0 ? Long.parseLong(args[0]) : 3000);
        s.release();
        System.out.println("released");
        System.exit(0);
    }

    /** A context that speaks for the shell package, so system services accept calls from this uid. */
    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) { super(base); }
        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public Context getApplicationContext() { return this; }
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
        }
        @Override public int getDeviceId() { return 0; }
    }
}
