package cloud.stormlabs.hermescompanion;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Rect;
import android.util.Base64;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class HermesAccessibilityService extends AccessibilityService {
    private static volatile HermesAccessibilityService active;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Every actionable element from the most recent describeScreen() call, so a later action can
     *  refer to one by its short id ("e1", "e2"…) and act on the exact node/bounds that produced it. */
    private List<ScreenNodes.Element> lastSnapshot = Collections.emptyList();
    private static final int NODE_LIMIT = 150;

    static boolean enabled() { return active != null; }

    /** The hidden display Hermes works on in background mode (AgentDisplay), or -1 for the real screen. */
    private static volatile int hiddenDisplay = -1;
    /** Hermes works on the hidden display until 10 minutes after its last step there; then the real screen again. */
    private static volatile long hiddenUntil;
    static void agentDisplay(int id) { hiddenDisplay = id; if (id <= 0) hiddenUntil = 0; }
    static void useHiddenDisplay() { hiddenUntil = System.currentTimeMillis() + 10 * 60_000L; }
    static void useRealScreen() { hiddenUntil = 0; }
    static int agentDisplayId() { return hiddenDisplay > 0 && System.currentTimeMillis() < hiddenUntil ? hiddenDisplay : -1; }
    /** Turned on in Android settings, even before the service has connected (cold start). */
    static boolean listed(android.content.Context c) {
        String on = android.provider.Settings.Secure.getString(c.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return active != null || (on != null && on.contains(c.getPackageName() + "/"));
    }
    static String currentApp() { return call(service -> service.app(), "Screen control is off"); }
    static String describeScreen() { return call(HermesAccessibilityService::describe, "Screen control is off"); }
    static String act(String action, String target, String text, Rect explicitBounds, boolean submit, String id) {
        return call(service -> service.perform(action, target, text, explicitBounds, submit, id), "Screen control is off");
    }
    /** Up to {@code limit} clickable/editable/scrollable elements on screen, for Jev fast steps;
     *  empty off the main thread's 2s wait or when screen control is off. */
    static List<ScreenNodes.Element> elementSnapshot(int limit) {
        HermesAccessibilityService service = active;
        if (service == null) return Collections.emptyList();
        if (Looper.myLooper() == Looper.getMainLooper()) return service.elements(limit);
        java.util.concurrent.atomic.AtomicReference<List<ScreenNodes.Element>> result = new java.util.concurrent.atomic.AtomicReference<>(Collections.emptyList());
        CountDownLatch done = new CountDownLatch(1);
        service.main.post(() -> { try { if (active == service) result.set(service.elements(limit)); } finally { done.countDown(); } });
        try { if (!done.await(2, TimeUnit.SECONDS)) return Collections.emptyList(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return Collections.emptyList(); }
        return result.get();
    }
    static boolean isPasswordTarget(String target, String id) {
        return "true".equals(call(service -> {
            if (!id.isEmpty()) {
                ScreenNodes.Element e = ScreenNodes.findById(service.lastSnapshot, id);
                if (e != null) return String.valueOf(e.password);
            }
            AccessibilityNodeInfo root = service.screenRoot();
            AccessibilityNodeInfo node = root == null ? null : service.find(root, target);
            return String.valueOf(node != null && node.isPassword());
        }, "false"));
    }
    /** On-screen bounds of {@code target}, for the agent overlay's pre-tap highlight; null if not found. */
    static Rect boundsOf(String target) {
        String packed = call(service -> {
            AccessibilityNodeInfo root = service.screenRoot();
            AccessibilityNodeInfo node = root == null ? null : service.find(root, target);
            if (node == null) return "";
            Rect r = new Rect(); node.getBoundsInScreen(r);
            return r.isEmpty() ? "" : r.left + "," + r.top + "," + r.right + "," + r.bottom;
        }, "");
        return unpackRect(packed);
    }
    /** On-screen bounds of element {@code id} from the latest describeScreen() snapshot; null if unknown. */
    static Rect boundsForId(String id) {
        String packed = call(service -> {
            Rect r = service.snapshotBounds(id);
            return r == null ? "" : r.left + "," + r.top + "," + r.right + "," + r.bottom;
        }, "");
        return unpackRect(packed);
    }
    private static Rect unpackRect(String packed) {
        if (packed.isEmpty()) return null;
        try {
            String[] p = packed.split(",");
            return new Rect(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (Exception e) { return null; }
    }

    /** The agent overlay, while a PhoneAgent task is showing one; briefly made non-touchable around a raw gesture. */
    AgentOverlay overlay;
    static void attachAgentOverlay(PhoneAgent agent) {
        HermesAccessibilityService service = active;
        if (service == null) return;
        service.main.post(() -> { if (active == service && PhoneAgent.current() == agent) agent.setListener(new AgentOverlay(service, agent)); });
    }

    static boolean canScreenshot() { return Build.VERSION.SDK_INT >= 30 && enabled(); }

    /**
     * A screenshot for vision models: max 540px wide JPEG ~70 quality data URL, or null (below
     * API 30, screen control off, or any failure). Hides the agent overlay while the frame is
     * captured so it never appears in the picture.
     */
    static void screenshot(Consumer<String> callback) {
        HermesAccessibilityService service = active;
        if (service == null || Build.VERSION.SDK_INT < 30) { callback.accept(null); return; }
        if (agentDisplayId() > 0) { // background mode: the frame comes from the hidden display's own surface
            new Thread(() -> {
                byte[] jpeg = AgentDisplay.screenshot();
                callback.accept(jpeg.length == 0 ? null : "data:image/jpeg;base64," + android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP));
            }, "agent-shot").start();
            return;
        }
        service.main.post(() -> service.captureScreenshot(callback));
    }

    @android.annotation.TargetApi(30)
    private void captureScreenshot(Consumer<String> callback) {
        if (Build.VERSION.SDK_INT < 30) { callback.accept(null); return; } // matches the API-30 APIs used below
        if (active != this) { callback.accept(null); return; }
        AgentOverlay ov = overlay;
        if (ov != null) ov.hideForCapture();
        // Give the compositor a frame to redraw without the overlay before capturing.
        main.postDelayed(() -> {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult result) {
                        String url = encodeScreenshot(result);
                        if (ov != null) ov.showAfterCapture();
                        callback.accept(url);
                    }
                    @Override public void onFailure(int errorCode) {
                        if (ov != null) ov.showAfterCapture();
                        callback.accept(null);
                    }
                });
            } catch (Throwable t) {
                if (ov != null) ov.showAfterCapture();
                callback.accept(null);
            }
        }, 60);
    }

    @android.annotation.TargetApi(30)
    private static String encodeScreenshot(ScreenshotResult result) {
        Bitmap hardware = null, bmp = null, scaled = null;
        try {
            hardware = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
            if (hardware == null) return null;
            bmp = hardware.copy(Bitmap.Config.ARGB_8888, false);
            if (bmp == null) return null;
            int w = bmp.getWidth(), h = bmp.getHeight();
            int targetW = Math.min(540, w);
            scaled = targetW == w || targetW <= 0 ? bmp : Bitmap.createScaledBitmap(bmp, targetW, Math.round(h * (targetW / (float) w)), true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out);
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        } finally {
            if (scaled != null && scaled != bmp) scaled.recycle();
            if (bmp != null) bmp.recycle();
            if (hardware != null) hardware.recycle();
            try { result.getHardwareBuffer().close(); } catch (Throwable ignored) { }
        }
    }
    static void switchAfterPanel(Context context, String target, boolean on, String title) {
        HermesAccessibilityService service = active;
        if (service == null) return;
        service.main.postDelayed(() -> service.switchInPanel(context.getApplicationContext(), target, on, title, 2), 700);
    }

    /**
     * After a messaging deep link opened a chat with the text prefilled: press its Send button, like Google
     * Assistant's WhatsApp integration. Gives up (and logs it) if the app or the button doesn't show up.
     */
    static void sendAfterOpen(Context context, String pkg, String title) {
        HermesAccessibilityService service = active;
        if (service == null) return;
        service.main.postDelayed(() -> service.pressSend(context.getApplicationContext(), pkg, title, 10), 900);
    }

    private void pressSend(Context context, String pkg, String title, int retries) {
        if (active != this) return;
        if (new SettingsStore(context).agentPaused()) return;
        AccessibilityNodeInfo root = screenRoot();
        String now = root == null ? "" : string(root.getPackageName());
        AccessibilityNodeInfo send = now.equals(pkg) ? sendButton(root) : null;
        if (send == null) {
            if (retries > 0) { main.postDelayed(() -> pressSend(context, pkg, title, retries - 1), 500); return; }
            ActivityLog.log(context, "action", title, now.equals(pkg) ? "Chat opened, but no Send button found; the message is waiting in the box"
                    : "The app didn't open the chat", false, null, null);
            return;
        }
        boolean ok = send.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        ActivityLog.log(context, "action", title, ok ? "Sent" : "Could not press Send", ok, null, null);
    }

    /** Press the first button labelled one of {@code labels} once {@code pkg} is in front (e.g. Clock's Dismiss). */
    static void pressAfterOpen(Context context, String pkg, String[] labels, String title) {
        HermesAccessibilityService service = active;
        if (service == null) return;
        service.main.postDelayed(() -> service.pressLabelled(context.getApplicationContext(), pkg, labels, title, 10, 0), 900);
    }

    private void pressLabelled(Context context, String pkg, String[] labels, String title, int retries, int pressed) {
        if (active != this) return;
        AccessibilityNodeInfo root = screenRoot();
        String now = root == null ? "" : string(root.getPackageName());
        AccessibilityNodeInfo button = now.equals(pkg) ? labelled(root, labels) : null;
        if (button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            // Several timers can be ringing at once: keep pressing while another one shows up.
            main.postDelayed(() -> pressLabelled(context, pkg, labels, title, 2, pressed + 1), 700);
            return;
        }
        if (retries > 0) { main.postDelayed(() -> pressLabelled(context, pkg, labels, title, retries - 1, pressed), 500); return; }
        ActivityLog.log(context, "action", title, pressed > 0 ? "Stopped " + pressed : "No running timer or alarm to stop", pressed > 0, null, null);
        if (pressed > 0 && agentDisplayId() <= 0) performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private AccessibilityNodeInfo labelled(AccessibilityNodeInfo node, String[] labels) {
        if (node == null || !node.isVisibleToUser()) return null;
        String text = string(node.getText()).trim(), desc = string(node.getContentDescription()).trim();
        for (String l : labels) if ((text.equalsIgnoreCase(l) || desc.equalsIgnoreCase(l)) && node.isEnabled()) {
            AccessibilityNodeInfo target = node;
            while (target != null && !target.isClickable()) target = target.getParent();
            if (target != null) return target;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = labelled(node.getChild(i), labels);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo sendButton(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser()) return null;
        String id = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName().toLowerCase(java.util.Locale.ROOT);
        String desc = string(node.getContentDescription()).trim().toLowerCase(java.util.Locale.ROOT);
        String text = string(node.getText()).trim().toLowerCase(java.util.Locale.ROOT);
        boolean looksLikeSend = id.endsWith(":id/send") || id.endsWith(":id/send_button") || id.endsWith(":id/send_text") || id.endsWith(":id/sendbutton")
                || desc.equals("send") || desc.equals("send message") || text.equals("send");
        if (looksLikeSend && node.isEnabled()) {
            AccessibilityNodeInfo target = node;
            while (target != null && !target.isClickable()) target = target.getParent();
            if (target != null) return target;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = sendButton(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private void switchInPanel(Context context, String target, boolean on, String title, int retries) {
        if (active != this) return;
        if (new SettingsStore(context).agentPaused()) return;
        AccessibilityNodeInfo root = screenRoot();
        String pkg = root == null ? "" : string(root.getPackageName());
        if (!pkg.contains("settings")) {
            if (retries > 0) { main.postDelayed(() -> switchInPanel(context, target, on, title, retries - 1), 600); return; }
            ActivityLog.log(context, "action", title + " switch", "Android settings panel is no longer open", false, null, null);
            return;
        }
        AccessibilityNodeInfo label = root == null ? null : find(root, target);
        if (label == null && root != null) label = find(root, "Use " + target);
        AccessibilityNodeInfo control = label == null ? null : checkable(label);
        if (control == null && label != null) control = checkable(label.getParent());
        if (control == null && retries > 0) {
            main.postDelayed(() -> switchInPanel(context, target, on, title, retries - 1), 600);
            return;
        }
        String result = control == null ? "Switch not found; use Android controls" : control.isChecked() == on ? "Already " + (on ? "on" : "off")
                : control.performAction(AccessibilityNodeInfo.ACTION_CLICK) ? "Switched " + (on ? "on" : "off") : "Could not tap switch";
        ActivityLog.log(context, "action", title + " switch", result, result.startsWith("Already") || result.startsWith("Switched"), null, null);
    }

    private AccessibilityNodeInfo checkable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isCheckable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = checkable(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private interface Work { String run(HermesAccessibilityService service); }
    private static String call(Work work, String unavailable) {
        HermesAccessibilityService service = active;
        if (service == null) return unavailable;
        if (Looper.myLooper() == Looper.getMainLooper()) return work.run(service);
        String[] result = {unavailable};
        CountDownLatch done = new CountDownLatch(1);
        Runnable pending = () -> { try { if (active == service) result[0] = work.run(service); } finally { done.countDown(); } };
        service.main.post(pending);
        try {
            if (!done.await(2, TimeUnit.SECONDS)) {
                service.main.removeCallbacks(pending);
                return "Screen control timed out";
            }
        } catch (InterruptedException e) {
            service.main.removeCallbacks(pending);
            Thread.currentThread().interrupt();
            return "Screen control interrupted";
        }
        return result[0];
    }

    @Override protected void onServiceConnected() { super.onServiceConnected(); active = this; new SettingsStore(this).screenControlConnected(); }
    /** The last app in front that isn't Hermes, the launcher or system UI: where a task started from Hermes resumes. */
    private static volatile String lastOtherApp;
    private String launcher;

    static String lastOtherApp() { return lastOtherApp; }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Only window changes are subscribed; remember the app, nothing else runs until a request.
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (launcher == null) {
            android.content.pm.ResolveInfo home = getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
            launcher = home == null || home.activityInfo == null ? "" : home.activityInfo.packageName;
        }
        if (!pkg.equals(getPackageName()) && !pkg.equals(launcher) && !pkg.equals("com.android.systemui")) lastOtherApp = pkg;
    }
    @Override public void onInterrupt() {}
    @Override public boolean onUnbind(Intent intent) { if (active == this) active = null; return super.onUnbind(intent); }
    @Override public void onDestroy() { if (active == this) active = null; super.onDestroy(); }

    private String app() {
        AccessibilityNodeInfo root = screenRoot();
        return root == null || root.getPackageName() == null ? "Unknown app" : root.getPackageName().toString();
    }

    private String describe() {
        int[] omitted = {0};
        List<ScreenNodes.Element> elements = collectElements(NODE_LIMIT, omitted);
        lastSnapshot = elements;
        return ScreenNodes.format(app(), elements, omitted[0]);
    }

    private List<ScreenNodes.Element> elements(int limit) { return collectElements(limit, null); }

    private List<ScreenNodes.Element> collectElements(int limit, int[] omitted) {
        AccessibilityNodeInfo root = screenRoot();
        List<ScreenNodes.Element> out = new ArrayList<>();
        if (root != null) collectElements(root, out, limit, omitted);
        return out;
    }

    private void collectElements(AccessibilityNodeInfo node, List<ScreenNodes.Element> out, int limit) {
        collectElements(node, out, limit, null);
    }

    private void collectElements(AccessibilityNodeInfo node, List<ScreenNodes.Element> out, int limit, int[] omitted) {
        if (node == null || (out.size() >= limit && omitted == null) || !node.isVisibleToUser()) return;
        boolean clickable = node.isClickable(), longClickable = node.isLongClickable(), editable = node.isEditable(),
                scrollable = node.isScrollable(), checkable = node.isCheckable();
        if (clickable || longClickable || editable || scrollable || checkable) {
            if (out.size() >= limit) omitted[0]++;
            else {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                String nodeText = string(node.getText()), description = string(node.getContentDescription());
                String label = "";
                if ((clickable || longClickable) && nodeText.trim().isEmpty() && description.trim().isEmpty() && !node.isPassword()) {
                    List<String> texts = new ArrayList<>();
                    for (int i = 0; i < node.getChildCount() && texts.size() < 80; i++) collectDescendantTexts(node.getChild(i), texts);
                    label = ScreenNodes.descendantLabel(texts);
                }
                out.add(new ScreenNodes.Element("e" + out.size(), nodeText, description,
                        string(node.getHintText()), node.getViewIdResourceName(), node.getClassName() == null ? "" : node.getClassName().toString(),
                        clickable, longClickable, editable, scrollable, checkable, node.isChecked(), node.isFocused(), node.isPassword(),
                        r.left, r.top, r.right, r.bottom, label));
            }
        }
        for (int i = 0; i < node.getChildCount() && (out.size() < limit || (omitted != null && omitted[0] < 500)); i++)
            collectElements(node.getChild(i), out, limit, omitted);
    }

    private static void collectDescendantTexts(AccessibilityNodeInfo node, List<String> texts) {
        if (node == null || !node.isVisibleToUser() || node.isPassword() || texts.size() >= 80) return;
        String text = string(node.getText());
        if (!text.trim().isEmpty()) texts.add(text);
        for (int i = 0; i < node.getChildCount() && texts.size() < 80; i++) collectDescendantTexts(node.getChild(i), texts);
    }

    private static String string(CharSequence value) { return value == null ? "" : value.toString(); }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String target) {
        if (node == null || !node.isVisibleToUser()) return null;
        if (ScreenNodes.matches(elementOf(node), target)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = find(node.getChild(i), target);
            if (found != null) return found;
        }
        return null;
    }

    /** A single node's data, in the same shape as a snapshot element, for text/hint/view-id matching. */
    private static ScreenNodes.Element elementOf(AccessibilityNodeInfo node) {
        return new ScreenNodes.Element("", string(node.getText()), string(node.getContentDescription()), string(node.getHintText()),
                node.getViewIdResourceName(), "", node.isClickable(), node.isLongClickable(), node.isEditable(),
                node.isScrollable(), node.isCheckable(), node.isChecked(), node.isFocused(), node.isPassword(), 0, 0, 0, 0);
    }

    private Rect snapshotBounds(String id) {
        ScreenNodes.Element e = ScreenNodes.findById(lastSnapshot, id);
        return e == null ? null : new Rect(e.left, e.top, e.right, e.bottom);
    }

    private String perform(String action, String target, String text, Rect explicitBounds, boolean submit, String id) {
        if (agentDisplayId() > 0) useHiddenDisplay(); // each step keeps the task on the hidden screen
        if (agentDisplayId() > 0 && ("back".equals(action) || "home".equals(action))) {
            return AgentDisplay.key("back".equals(action) ? "KEYCODE_BACK" : "KEYCODE_HOME") ? "Done (hidden screen)" : "Hidden screen refused the key";
        }
        if ("back".equals(action) || "home".equals(action) || "recents".equals(action) || "notifications".equals(action)) {
            int kind = "back".equals(action) ? GLOBAL_ACTION_BACK : "home".equals(action) ? GLOBAL_ACTION_HOME
                    : "recents".equals(action) ? GLOBAL_ACTION_RECENTS : GLOBAL_ACTION_NOTIFICATIONS;
            return performGlobalAction(kind) ? "Done" : "Android refused the action";
        }
        AccessibilityNodeInfo root = screenRoot();
        if (root == null) return "No active screen available";

        // An id resolves to the exact on-screen bounds of the latest describeScreen() snapshot —
        // like a Jev fast step's own explicit bounds, this skips (possibly ambiguous) text lookup.
        Rect bounds = explicitBounds != null ? explicitBounds : (id == null || id.isEmpty() ? null : snapshotBounds(id));
        if (bounds == null && explicitBounds == null && id != null && !id.isEmpty()) {
            // The snapshot that issued this id was replaced (another read, a screen change): re-read the same screen once.
            lastSnapshot = collectElements(NODE_LIMIT, null);
            bounds = snapshotBounds(id);
        }
        String label = target.isEmpty() && id != null && !id.isEmpty() ? id : target;

        if ("swipe".equals(action)) return swipe(root, target);
        if ("enter".equals(action)) return pressEnter(root);
        if ("scroll".equals(action)) {
            AccessibilityNodeInfo scroll = bounds != null ? scrollableAt(root, bounds) : scrollable(root);
            int direction = "up".equals(target) ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            return scroll != null && scroll.performAction(direction) ? "Scrolled " + target : "No scrollable area found";
        }
        // A Jev fast step (or an id target) already knows the exact on-screen bounds: tap there
        // directly rather than re-finding by (possibly ambiguous) text/id/description.
        if ("tap".equals(action) && bounds != null) {
            // Click the node itself when it (or a parent) is clickable: a coordinate tap misses when
            // the element is clipped or half off-screen (seen on a web cookie dialog).
            AccessibilityNodeInfo clickable = findByBounds(root, bounds);
            // Clipped elements (e.g. a button inside a scrolled dialog) ignore clicks until shown.
            if (clickable != null) clickable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
            while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tapped " + label;
            return tapAt(bounds, label);
        }
        if ("long_press".equals(action) && bounds != null) return longPress(findByBounds(root, bounds), bounds, label);
        if ("type".equals(action)) return typeInto(root, bounds, target, label, text, submit);

        AccessibilityNodeInfo node = find(root, target);
        if (node == null) return "Target not found: " + label;
        if ("long_press".equals(action)) {
            Rect b = new Rect(); node.getBoundsInScreen(b);
            return longPress(node, b, target);
        }
        AccessibilityNodeInfo clickable = node;
        while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tapped " + target;
        Rect bnds = new Rect(); node.getBoundsInScreen(bnds);
        return tapAt(bnds, target);
    }

    /**
     * {@code type}'s target resolution: the exact node at {@code bounds} (an id or Jev's explicit
     * bounds), else a plain-text match, else — when the target was generic or not found — the
     * focused editable field, then the only editable field on screen. Focuses before setting text.
     */
    private String typeInto(AccessibilityNodeInfo root, Rect bounds, String target, String label, String text, boolean submit) {
        AccessibilityNodeInfo node = bounds != null ? findByBounds(root, bounds) : null;
        String usedLabel = label.isEmpty() ? "the field" : label;
        if ((node == null || !node.isEditable()) && !target.isEmpty()) {
            AccessibilityNodeInfo found = find(root, target);
            if (found != null && found.isEditable()) node = found;
        }
        if (node == null || !node.isEditable()) {
            List<ScreenNodes.Element> current = new ArrayList<>();
            collectElements(root, current, NODE_LIMIT);
            ScreenNodes.Element fallback = ScreenNodes.typeFallback(current);
            AccessibilityNodeInfo fallbackNode = fallback == null ? null : findByBounds(root, new Rect(fallback.left, fallback.top, fallback.right, fallback.bottom));
            if (fallbackNode == null) return "No editable field found" + (target.isEmpty() ? "" : " for " + target);
            node = fallbackNode;
            usedLabel = fallback.focused ? "the focused field" : "the only editable field";
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        if (!node.isFocused()) node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "Could not type into " + usedLabel;
        if (submit) pressImeEnter(node);
        return "Typed into " + usedLabel;
    }

    private String longPress(AccessibilityNodeInfo node, Rect bounds, String label) {
        AccessibilityNodeInfo longClickable = node;
        while (longClickable != null && !longClickable.isLongClickable()) longClickable = longClickable.getParent();
        if (longClickable != null && longClickable.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return "Long-pressed " + label;
        return longPressAt(bounds, label);
    }

    private String swipe(AccessibilityNodeInfo root, String direction) {
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        if (screen.isEmpty()) return "Could not swipe";
        float cx = screen.centerX(), cy = screen.centerY();
        float dy = screen.height() / 6f, dx = screen.width() / 6f;
        Path path = new Path();
        switch (direction == null ? "" : direction) {
            case "up": path.moveTo(cx, cy + dy); path.lineTo(cx, cy - dy); break;
            case "down": path.moveTo(cx, cy - dy); path.lineTo(cx, cy + dy); break;
            case "left": path.moveTo(cx + dx, cy); path.lineTo(cx - dx, cy); break;
            case "right": path.moveTo(cx - dx, cy); path.lineTo(cx + dx, cy); break;
            default: return "Direction must be up, down, left or right";
        }
        GestureDescription gesture = gestureBuilder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 200)).build();
        return dispatchTouch(gesture) ? "Swiped " + direction : "Could not swipe";
    }

    private String pressEnter(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo focused = findFocused(root);
        if (focused == null) return "No focused field";
        if (Build.VERSION.SDK_INT < 30) return "Enter needs a newer version of Android";
        return pressImeEnter(focused) ? "Pressed enter" : "Could not press enter";
    }

    private AccessibilityNodeInfo findFocused(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser()) return null;
        if (node.isFocused()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findFocused(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    @android.annotation.TargetApi(30)
    private boolean pressImeEnter(AccessibilityNodeInfo node) {
        if (Build.VERSION.SDK_INT < 30) return false;
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
    }

    private AccessibilityNodeInfo findByBounds(AccessibilityNodeInfo node, Rect bounds) {
        if (node == null || !node.isVisibleToUser()) return null;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.equals(bounds)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findByBounds(node.getChild(i), bounds);
            if (found != null) return found;
        }
        return null;
    }

    private String tapAt(Rect bounds, String target) {
        if (bounds.isEmpty()) return "Could not tap " + target;
        Path path = new Path(); path.moveTo(bounds.centerX(), bounds.centerY());
        GestureDescription gesture = gestureBuilder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 80)).build();
        return dispatchTouch(gesture) ? "Tapped " + target : "Could not tap " + target;
    }

    private String longPressAt(Rect bounds, String target) {
        if (bounds.isEmpty()) return "Could not long-press " + target;
        Path path = new Path(); path.moveTo(bounds.centerX(), bounds.centerY());
        GestureDescription gesture = gestureBuilder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 600)).build();
        return dispatchTouch(gesture) ? "Long-pressed " + target : "Could not long-press " + target;
    }

    private GestureDescription.Builder gestureBuilder() {
        GestureDescription.Builder b = new GestureDescription.Builder();
        int hidden = agentDisplayId();
        if (hidden > 0 && Build.VERSION.SDK_INT >= 30) b.setDisplayId(hidden);
        return b;
    }

    /** A raw gesture is real screen touch dispatch: let it through the bar window, not into it. */
    private boolean dispatchTouch(GestureDescription gesture) {
        AgentOverlay ov = overlay;
        if (ov != null) ov.beforeGesture();
        return dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { if (ov != null) ov.afterGesture(); }
            @Override public void onCancelled(GestureDescription g) { if (ov != null) ov.afterGesture(); }
        }, main);
    }

    private AccessibilityNodeInfo scrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = scrollable(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    /** The scrollable node under {@code bounds}'s centre, or any scrollable node if none matches. */
    private AccessibilityNodeInfo scrollableAt(AccessibilityNodeInfo root, Rect bounds) {
        AccessibilityNodeInfo hit = scrollableContaining(root, bounds);
        return hit != null ? hit : scrollable(root);
    }

    private AccessibilityNodeInfo scrollableContaining(AccessibilityNodeInfo node, Rect bounds) {
        if (node == null || !node.isVisibleToUser()) return null;
        if (node.isScrollable()) {
            Rect r = new Rect(); node.getBoundsInScreen(r);
            if (r.contains(bounds.centerX(), bounds.centerY())) return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = scrollableContaining(node.getChild(i), bounds);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo screenRoot() {
        if (agentDisplayId() > 0 && Build.VERSION.SDK_INT >= 30) {
            // Background mode: the app Hermes is using lives on its hidden display, not under the person's finger.
            android.util.SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
            List<AccessibilityWindowInfo> windows = all.get(agentDisplayId());
            AccessibilityNodeInfo fallback = null;
            if (windows != null) for (AccessibilityWindowInfo w : windows) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                // Samsung shows apps on extra displays as pop-up windows: skip System UI's caption bar above them.
                String pkg = string(root.getPackageName());
                if (pkg.equals("com.android.systemui") || pkg.equals(getPackageName())) { if (fallback == null) fallback = root; continue; }
                return root;
            }
            return fallback;
        }
        AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
        if (activeRoot == null || activeRoot.getPackageName() == null || !getPackageName().contentEquals(activeRoot.getPackageName())) return activeRoot;
        // A voice interaction window (or, during an agent task, Hermes' own window) can sit above
        // the app the user actually wants: look for a foreign application window instead.
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null && root.getPackageName() != null && !getPackageName().contentEquals(root.getPackageName())) return root;
        }
        // While a PhoneAgent task is running, never hand Hermes its own screen back: "no active
        // screen" is safer than describing, or tapping, Hermes' own UI.
        return PhoneAgent.isTaskActive() ? null : activeRoot;
    }
}
