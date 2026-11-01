package cloud.stormlabs.hermescompanion;

import java.util.List;
import java.util.Locale;

/**
 * Plain screen data, shared by accessibility formatting, target lookup and the Jev fast-step
 * table: one {@link Element} model for both describeScreen() and the fast-step snapshot, so a
 * short id ("e1", "e2"…) assigned for one snapshot means the same node in both places.
 */
final class ScreenNodes {
    static final int LIMIT = 6000;

    /** An actionable element captured from the accessibility tree: plain data, on-screen bounds
     *  from the snapshot that produced it (no Android dependency, testable). */
    static final class Element {
        final String id, text, description, hint, viewId, className, label;
        final boolean clickable, longClickable, editable, scrollable, checkable, checked, focused, password;
        final int left, top, right, bottom;
        Element(String id, String text, String description, String hint, String viewId, String className,
                boolean clickable, boolean longClickable, boolean editable, boolean scrollable, boolean checkable,
                boolean checked, boolean focused, boolean password, int left, int top, int right, int bottom) {
            this(id, text, description, hint, viewId, className, clickable, longClickable, editable,
                    scrollable, checkable, checked, focused, password, left, top, right, bottom, "");
        }

        Element(String id, String text, String description, String hint, String viewId, String className,
                boolean clickable, boolean longClickable, boolean editable, boolean scrollable, boolean checkable,
                boolean checked, boolean focused, boolean password, int left, int top, int right, int bottom,
                String label) {
            this.id = id; this.text = clean(text); this.description = clean(description); this.hint = clean(hint);
            this.viewId = shortId(viewId); this.className = shortClass(clean(className));
            this.label = clean(label);
            this.clickable = clickable; this.longClickable = longClickable; this.editable = editable;
            this.scrollable = scrollable; this.checkable = checkable; this.checked = checked; this.focused = focused;
            this.password = password;
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }

        /** Any node worth listing/acting on: clickable, long-clickable, editable, scrollable or checkable. */
        boolean actionable() { return clickable || longClickable || editable || scrollable || checkable; }

        /** One line for Jev's "target" choice criteria and the state's element table. */
        String describe() {
            StringBuilder b = new StringBuilder();
            if (!text.isEmpty()) b.append("text=").append(text);
            if (!description.isEmpty()) b.append(b.length() > 0 ? " " : "").append("desc=").append(description);
            if (!label.isEmpty()) b.append(b.length() > 0 ? " " : "").append("label=").append(label);
            if (!hint.isEmpty()) b.append(b.length() > 0 ? " " : "").append("hint=").append(hint);
            if (!viewId.isEmpty()) b.append(b.length() > 0 ? " " : "").append("id=").append(viewId);
            if (!className.isEmpty()) b.append(b.length() > 0 ? " " : "").append("class=").append(className);
            if (clickable) b.append(" clickable");
            if (longClickable) b.append(" long-clickable");
            if (editable) b.append(" editable");
            if (scrollable) b.append(" scrollable");
            if (checkable) b.append(checked ? " checked" : " checkable");
            if (focused) b.append(" focused");
            return b.length() == 0 ? "(unlabeled)" : b.toString().trim();
        }
    }

    /** describeScreen()'s text: one line per element, its short id first, text masked for passwords. */
    static String format(String pkg, List<Element> elements) {
        return format(pkg, elements, 0);
    }

    /** {@code omitted} counts actionable nodes beyond the snapshot's node cap. */
    static String format(String pkg, List<Element> elements, int omitted) {
        StringBuilder out = new StringBuilder("App: ").append(clean(pkg)).append('\n');
        for (int i = 0; i < elements.size(); i++) {
            Element e = elements.get(i);
            StringBuilder line = new StringBuilder("- id=").append(e.id);
            if (!e.text.isEmpty() && !e.password) line.append(" text=").append(e.text);
            if (!e.description.isEmpty() && !e.password) line.append(" desc=").append(e.description);
            if (!e.label.isEmpty() && !e.password) line.append(" label=").append(e.label);
            if (!e.hint.isEmpty() && !e.password) line.append(" hint=").append(e.hint);
            if (!e.viewId.isEmpty()) line.append(" view=").append(e.viewId);
            if (e.clickable) line.append(" clickable");
            if (e.longClickable) line.append(" long-clickable");
            if (e.editable) line.append(" editable");
            if (e.scrollable) line.append(" scrollable");
            if (e.checkable) line.append(e.checked ? " checked" : " checkable");
            if (e.focused) line.append(" focused");
            if (e.password) line.append(" password");
            line.append('\n');
            int remaining = elements.size() - i - 1 + omitted;
            if (out.length() + line.length() + moreElements(remaining).length() > LIMIT) {
                out.append(moreElements(remaining + 1));
                return out.toString();
            }
            out.append(line);
        }
        if (omitted > 0) out.append(moreElements(omitted));
        return out.toString();
    }

    private static String moreElements(int count) {
        return count == 0 ? "" : "(+" + count + " more elements not shown; scroll to see more)\n";
    }

    /** Visible descendant text, in screen order, shortened for a parent row label. */
    static String descendantLabel(List<String> texts) {
        StringBuilder label = new StringBuilder();
        for (String text : texts) {
            String part = clean(text);
            if (part.isEmpty()) continue;
            if (label.length() > 0) {
                if (label.length() + 3 >= 80) break;
                label.append(" · ");
            }
            label.append(part, 0, Math.min(part.length(), 80 - label.length()));
            if (label.length() == 80) break;
        }
        return label.toString();
    }

    /** Exact (case-insensitive) match against text, description, hint, view id or the element's own short id:
     *  a fallback for {@code target} when the model didn't (or couldn't) send an {@code id}. */
    static boolean matches(Element e, String target) {
        String q = clean(target).toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return false;
        return q.equals(e.text.toLowerCase(Locale.ROOT)) || q.equals(e.description.toLowerCase(Locale.ROOT))
                || q.equals(e.hint.toLowerCase(Locale.ROOT)) || q.equals(e.viewId.toLowerCase(Locale.ROOT))
                || q.equals(e.id.toLowerCase(Locale.ROOT));
    }

    static boolean canType(Element e) { return e.editable; }

    static Element findById(List<Element> elements, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Element e : elements) if (e.id.equals(id)) return e;
        return null;
    }

    /** {@code type}'s fallback when the requested target is missing or generic: the focused
     *  editable field, else the only editable field on screen, else null (nothing safe to guess). */
    static Element typeFallback(List<Element> elements) {
        Element onlyEditable = null;
        int editableCount = 0;
        for (Element e : elements) {
            if (!e.editable) continue;
            if (e.focused) return e;
            editableCount++;
            onlyEditable = e;
        }
        return editableCount == 1 ? onlyEditable : null;
    }

    private static String shortClass(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static String shortId(String id) {
        String value = clean(id);
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private static String clean(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return cleaned.length() > 180 ? cleaned.substring(0, 180) + "…" : cleaned;
    }
}
