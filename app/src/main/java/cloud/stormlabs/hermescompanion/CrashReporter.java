package cloud.stormlabs.hermescompanion;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Catches launch crashes and surfaces the stack on screen so it can be reported without ADB. */
public final class CrashReporter {
    private static volatile boolean installed = false;
    private CrashReporter() {}

    public static void install(final Context context) {
        if (installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable throwable) {
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                final String stack = sw.toString();
                try {
                    context.getSharedPreferences("crash", Context.MODE_PRIVATE).edit()
                            .putString("last", stack).commit();
                    Intent i = new Intent(context, CrashActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .putExtra("stack", stack);
                    context.startActivity(i);
                    // CrashActivity runs in its own process (:crash). Without this the main thread is dead but the
                    // process lives on as a frozen window; end it like the system would.
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                } catch (Throwable ignored) {
                    // Last resort: keep the default behaviour.
                    if (prev != null) prev.uncaughtException(thread, throwable);
                    else android.os.Process.killProcess(android.os.Process.myPid());
                }
            }
        });
    }

    public static String lastCrash(Context c) {
        return c.getSharedPreferences("crash", Context.MODE_PRIVATE).getString("last", "");
    }

    /** Surface a non-fatal failure (bad settings, a layout that could not build) on screen.
     *  A window whose content never rendered shows the bare theme background, which is
     *  indistinguishable from a hang — never let that be the only evidence. */
    public static void show(Context context, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String stack = sw.toString();
        try {
            context.getSharedPreferences("crash", Context.MODE_PRIVATE).edit()
                    .putString("last", stack).apply();
        } catch (Throwable ignored) { }
        try {
            context.startActivity(new Intent(context, CrashActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("stack", stack));
        } catch (Throwable ignored) {
            // Nothing left to do: the stack is already in settings for a later read.
        }
    }
}
