package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Layout-aware navigation: the Tabs bottom bar, the home screen of each layout, and how chats open. */
final class Nav {
    private Nav() {}

    private static final int[] ICONS = {R.drawable.ic_session, R.drawable.ic_assistant, R.drawable.ic_compose, R.drawable.ic_clock, R.drawable.ic_movie, R.drawable.ic_pulse, R.drawable.ic_home, R.drawable.ic_mic};

    /** Appends the bottom bar to a screen's column in the Tabs layout; hidden while the keyboard is up. */
    static void tabBar(Activity a, LinearLayout column, Layout.Tab current) {
        if (Ui.layout != Layout.TABS) return;
        LinearLayout bar = new LinearLayout(a);
        bar.setBackgroundColor(Ui.BAR);
        for (Layout.Tab t : Layout.parseTabs(new SettingsStore(a).uiTabs())) {
            boolean on = t == current;
            LinearLayout item = new LinearLayout(a); item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER);
            item.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6)); item.setMinimumHeight(Ui.dp(a, 56));
            item.setBackground(Ui.ripple(a, Color.TRANSPARENT, 0));
            ImageView icon = new ImageView(a); icon.setImageResource(ICONS[t.ordinal()]);
            icon.setImageTintList(ColorStateList.valueOf(on ? Ui.BLUE : Ui.SUB));
            icon.setPadding(Ui.dp(a, 14), Ui.dp(a, 3), Ui.dp(a, 14), Ui.dp(a, 3));
            if (on) icon.setBackground(Ui.round(a, (Ui.BLUE & 0xffffff) | 0x2e000000, 99));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 54), Ui.dp(a, 30)));
            TextView label = Ui.text(a, t.label, 12, on ? Ui.BLUE : Ui.SUB, on);
            label.setGravity(Gravity.CENTER); label.setSingleLine(true); label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(Ui.dp(a, 2), Ui.dp(a, 2), Ui.dp(a, 2), 0);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(label, new LinearLayout.LayoutParams(-1, -2));
            item.setSelected(on);
            item.setContentDescription(t.label + " tab" + (on ? ", selected" : ""));
            item.setOnClickListener(v -> go(a, current, t));
            bar.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        View line = new View(a); line.setBackgroundColor(Ui.LINE);
        column.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(a, 1) / 2)));
        column.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        // Ui.fitInsets pads the column by the keyboard: a bar floating above the keyboard only wastes room.
        column.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int want = column.getPaddingBottom() > Ui.dp(a, 150) ? View.GONE : View.VISIBLE;
            if (bar.getVisibility() != want) column.post(() -> { bar.setVisibility(want); line.setVisibility(want); });
        });
    }

    /** Switch tabs: reuse the tab's screen if it exists (no endless stack) and skip the transition. */
    static void go(Activity a, Layout.Tab current, Layout.Tab pressed) {
        Layout.Tab t = Layout.navigate(current, pressed);
        if (t == null) return;
        Intent i;
        switch (t) {
            case CHATS: i = new Intent(a, ChatsActivity.class); break;
            case ASSISTANT: {
                String sid = new SettingsStore(a).sessionId();
                i = sid.isEmpty() ? new Intent(a, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
                        : new Intent(a, ChatActivity.class).putExtra(ChatActivity.EXTRA_SESSION_ID, sid);
                break;
            }
            // Unconfigured, the capture screen shows its own "Not connected · SET UP" hint.
            case CAPTURE: i = new Intent(a, EssentialsActivity.class); break;
            case AGENTS: i = new Intent(a, JobsActivity.class); break;
            case MEDIA: i = new Intent(a, MediaActivity.class); break;
            case HOME: i = new Intent(a, DashboardActivity.class); break;
            case LIVE: i = new Intent(a, LiveNotesActivity.class); break;
            default: i = new Intent(a, StatusActivity.class);
        }
        a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        a.overridePendingTransition(0, 0);
    }

    /** Back on a Tabs root leaves the app rather than cycling through earlier tabs. */
    static boolean leaveFromTab(Activity a, Layout.Tab screen) {
        if (!Layout.backLeavesApp(Ui.layout, screen)) return false;
        a.moveTaskToBack(true);
        return true;
    }

    /** The layout's home screen; Classic and Focus land in the last conversation via MainActivity. */
    static Intent home(Context c) {
        switch (Ui.layout.home) {
            case DASHBOARD: return new Intent(c, DashboardActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            case CHATS: return new Intent(c, ChatsActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            default: return new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_CHAT, true);
        }
    }

    /** A conversation screen: Tabs brings the one Assistant screen forward, the others reuse it from the top. */
    static Intent chat(Context c, String id, String title, String model) {
        Intent i = new Intent(c, ChatActivity.class).putExtra(ChatActivity.EXTRA_SESSION_ID, id)
                .putExtra(ChatActivity.EXTRA_TITLE, title).putExtra(ChatActivity.EXTRA_MODEL, model);
        return Ui.layout == Layout.TABS ? i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                : i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    /** Open a chat from outside the home screen: Dashboard stays underneath so Back returns to it. */
    static void startChat(Activity a, Intent chat) {
        if (Layout.homeUnderChat(Ui.layout)) a.startActivities(new Intent[]{home(a), chat});
        else a.startActivity(chat);
    }
}
