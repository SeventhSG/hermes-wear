package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

/**
 * The conversation list: search box, filter chips (incl. "Needs you"), pinned/date groups, message
 * search and the long-press actions. Shared by the chat drawer and the Tabs layout's Chats screen.
 */
final class ConversationList {
    interface Host {
        /** The conversation on screen, highlighted in the list (null for none). */
        String currentSessionId();
        void open(HermesApi.SessionInfo si);
        void openMessage(String sessionId, String title);
        void renamed(String id, String title);
        /** Archived or deleted. */
        void removed(String id);
    }

    private final Activity a;
    private final AgentBackend api;
    private final String apiProfile;
    private final ExecutorService network;
    private final Host host;
    private final SettingsStore store;
    private final RunManager runs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<HermesApi.SessionInfo> sessions = new ArrayList<>();
    /** all, needs, chats (this app / API), auto (cron jobs), other (messaging platforms, CLI). */
    private String filter = "all";
    private LinearLayout list, chips;
    private EditText search;
    private List<HubApi.SearchResult> messages = new ArrayList<>();
    private String searchState = "";
    private Runnable searchTask;
    /** Rows by session id, reused while typing; cleared when the list reloads. */
    private final java.util.Map<String, View> rowCache = new java.util.HashMap<>();
    private final Runnable typed = () -> render(query());
    private boolean searching, searchError, closed, everythingFailed;
    /** Bumped per load(): rename/pin/archive/retry each reload, and only the newest answer may be applied. */
    private int loadSeq;
    private final Set<String> expanded = new java.util.HashSet<>();

    ConversationList(Activity a, AgentBackend api, String apiProfile, ExecutorService network, Host host) {
        this.a = a; this.api = api; this.apiProfile = apiProfile; this.network = network; this.host = host;
        store = new SettingsStore(a); runs = RunManager.get(a);
    }

    private int dp(int n) { return Ui.dp(a, n); }
    private LinearLayout.LayoutParams match(int h) { return new LinearLayout.LayoutParams(-1, h < 0 ? h : dp(h)); }
    private void toast(String s) { Toast.makeText(a, s == null ? "Done" : s, Toast.LENGTH_SHORT).show(); }

    /** Adds the search box, the chips and the list (filling the rest) to a vertical column. */
    void addTo(LinearLayout root) {
        search = new EditText(a); search.setTextColor(Ui.TEXT); search.setHintTextColor(Ui.FAINT);
        search.setHint("Search conversations"); search.setTextSize(14); search.setSingleLine(true);
        search.setPadding(dp(14), 0, dp(14), 0); search.setBackground(Ui.round(a, Ui.tier(2), 12));
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0); search.setCompoundDrawablePadding(dp(10));
        search.setMinHeight(dp(48));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int b, int c) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { searchState = ""; messages.clear(); ui.removeCallbacks(typed); ui.postDelayed(typed, 120); }
            public void afterTextChanged(Editable e) {}
        });
        FrameLayout searchWrap = new FrameLayout(a); searchWrap.setPadding(dp(16), dp(6), dp(16), dp(4));
        searchWrap.addView(search, new FrameLayout.LayoutParams(-1, -2));
        root.addView(searchWrap, match(-2));
        HorizontalScrollView chipScroll = new HorizontalScrollView(a); chipScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(a); chips.setPadding(dp(16), dp(6), dp(16), dp(4));
        Ui.appearing(chips);
        chipScroll.addView(chips); root.addView(chipScroll, match(-2));
        ScrollView scroll = new ScrollView(a); list = new LinearLayout(a); list.setOrientation(LinearLayout.VERTICAL);
        list.addView(Ui.skeletonRows(a, 6)); scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    /** Stop pending message searches (the drawer closed or the screen went away). */
    void close() { closed = true; ui.removeCallbacks(typed); if (searchTask != null) ui.removeCallbacks(searchTask); searchState = ""; }

    private String query() { return search == null ? "" : search.getText().toString(); }

    private boolean everythingLoaded, loadingEverything;

    /** The list starts with the newest 150; the first search (or "Show older conversations") pulls in the rest, up to 1500. */
    private void loadEverythingForSearch() {
        if (everythingLoaded || loadingEverything || everythingFailed || sessions.isEmpty()) return;
        loadingEverything = true;
        network.submit(() -> {
            try {
                List<HermesApi.SessionInfo> more = new ArrayList<>();
                for (int offset = 150; offset < 1500; offset += 200) {
                    List<HermesApi.SessionInfo> page = api.listSessions(200, offset);
                    more.addAll(page);
                    if (page.isEmpty()) break;
                }
                a.runOnUiThread(() -> {
                    java.util.Set<String> have = new java.util.HashSet<>();
                    for (HermesApi.SessionInfo si : sessions) have.add(si.id);
                    for (HermesApi.SessionInfo si : more) if (!emptyCronRun(si) && have.add(si.id)) sessions.add(si);
                    everythingLoaded = true; loadingEverything = false;
                    if (!closed) render(query());
                });
            } catch (Exception e) {
                a.runOnUiThread(() -> { loadingEverything = false; everythingFailed = true; if (!closed) render(query()); });
            }
        });
    }

    void load() {
        everythingLoaded = false; everythingFailed = false;
        final int seq = ++loadSeq;
        network.submit(() -> {
            try {
                // Filtered pages can come back thin (empty cron runs), so keep paging until there is a screenful.
                List<HermesApi.SessionInfo> all = new ArrayList<>();
                for (int offset = 0; offset < 1500 && all.size() < 60; offset += 150) {
                    List<HermesApi.SessionInfo> page = api.listSessions(150, offset);
                    for (HermesApi.SessionInfo si : page) if (!emptyCronRun(si)) all.add(si);
                    if (page.isEmpty()) break;
                }
                a.runOnUiThread(() -> { if (closed || !isLatestLoad(seq, loadSeq)) return; sessions.clear(); sessions.addAll(all); rowCache.clear(); renderChips(); render(query()); });
            } catch (Exception e) {
                a.runOnUiThread(() -> {
                    if (list == null || closed || !isLatestLoad(seq, loadSeq)) return;
                    list.removeAllViews();
                    TextView err = Ui.body(a, "Couldn't load your conversations. Tap to retry.\n" + HermesApi.friendly(e), Ui.RED);
                    err.setPadding(dp(18), dp(18), dp(18), 0);
                    err.setOnClickListener(v -> load());
                    list.addView(err);
                });
            }
        });
    }

    /** Overlapping reloads finish in any order; only the answer to the newest request may replace the list. */
    static boolean isLatestLoad(int requested, int latest) { return requested == latest; }

    /** Hermes records an empty session for every no-agent cron run; hundreds a day would bury real chats. */
    static boolean emptyCronRun(HermesApi.SessionInfo si) {
        return si.messageCount == 0 && "cron".equals(si.source);
    }

    static String kindOf(HermesApi.SessionInfo si) {
        String src = si.source == null ? "" : si.source.toLowerCase(Locale.ROOT);
        if (src.isEmpty() || src.equals("api_server") || src.equals("api")) return "chats";
        if (src.contains("cron") || src.contains("job") || src.contains("schedul")) return "auto";
        return "other";
    }

    /** One scheduled job's runs, newest first. */
    static final class JobGroup {
        final String key, name; final List<HermesApi.SessionInfo> runs = new ArrayList<>();
        JobGroup(String key, String name) { this.key = key; this.name = name; }
        HermesApi.SessionInfo latest() { return runs.get(0); }
    }

    /** "oxd-change-scout · Oct 01 08:09" -> "oxd-change-scout"; titles without the date suffix are returned as is. */
    static String jobName(String title) {
        String t = title == null ? "" : title.trim();
        int at = t.lastIndexOf(" · ");
        return at > 0 ? t.substring(0, at).trim() : t;
    }

    /** Cron session ids look like cron_<jobId>_<date>_<time>; "" when the id carries no job id. */
    static String jobId(String sessionId) {
        if (sessionId == null || !sessionId.startsWith("cron_")) return "";
        int end = sessionId.indexOf('_', 5);
        return end > 5 ? sessionId.substring(5, end) : "";
    }

    /** Group key: the job id when the session id has one, else the stripped title. */
    static String groupKey(HermesApi.SessionInfo si) {
        String id = jobId(si.id);
        return id.isEmpty() ? jobName(si.displayTitle()) : id;
    }

    /** Scheduled-job sessions grouped by job in first-seen order (a latest-first input stays latest-first); other sessions are ignored. */
    static List<JobGroup> groupJobs(List<HermesApi.SessionInfo> all) {
        java.util.Map<String, JobGroup> by = new java.util.LinkedHashMap<>();
        for (HermesApi.SessionInfo si : all) {
            if (!kindOf(si).equals("auto")) continue;
            String key = groupKey(si);
            JobGroup g = by.get(key);
            if (g == null) by.put(key, g = new JobGroup(key, jobName(si.displayTitle())));
            g.runs.add(si);
        }
        return new ArrayList<>(by.values());
    }

    /** "Needs you": unread, waiting on an approval, or holding an unanswered proposal. */
    static boolean needsYou(HermesApi.SessionInfo si, boolean approvalPending, Set<String> pendingProposals) {
        return si.unread || approvalPending || pendingProposals.contains(si.id);
    }

    /** The "Needs you" subset in list order; approvalPending answers by session id. */
    static List<HermesApi.SessionInfo> needsYou(List<HermesApi.SessionInfo> all, Predicate<String> approvalPending, Set<String> pendingProposals) {
        List<HermesApi.SessionInfo> out = new ArrayList<>();
        for (HermesApi.SessionInfo si : all) if (needsYou(si, approvalPending.test(si.id), pendingProposals)) out.add(si);
        return out;
    }

    /** Approval pending on a live run of this profile. */
    static boolean approvalPending(RunManager runs, String profile, String id) {
        RunManager.LiveRun run = runs.active(profile, id);
        return run != null && run.approval != null;
    }

    private void renderChips() {
        if (chips == null) return;
        chips.removeAllViews();
        int[] counts = new int[4];
        for (HermesApi.SessionInfo si : sessions) { counts[0]++; String k = kindOf(si); counts[k.equals("chats") ? 1 : k.equals("auto") ? 2 : 3]++; }
        // {key, label, index into counts (all, chats, auto, other) or -1 for no count}; "Needs you" sits next to All.
        String[][] defs = {{"all", "All", "0"}, {"needs", "Needs you", "-1"}, {"chats", "Chats", "1"}, {"auto", "Auto", "2"}, {"other", "Other", "3"}};
        for (String[] def : defs) {
            int ci = Integer.parseInt(def[2]);
            if (ci > 0 && counts[ci] == 0) continue;
            if (ci > 0 && counts[ci] == counts[0]) continue; // "Other 60" next to "All 60" is the same list twice
            final String key = def[0];
            boolean on = key.equals(filter);
            TextView c = Ui.text(a, def[1] + (ci >= 0 && counts[ci] > 0 ? "  " + counts[ci] : ""), 12.5f, on ? Color.WHITE : Ui.SUB, on);
            c.setGravity(Gravity.CENTER); c.setPadding(dp(10), 0, dp(10), 0);
            c.setBackground(Ui.round(a, on ? Ui.BLUE_DARK : Ui.tier(2), 99)); // filled segments, no outlined ovals
            c.setOnClickListener(v -> { filter = key; renderChips(); render(query()); });
            c.setMinHeight(dp(32));
            c.setSelected(on);
            LinearLayout target = new LinearLayout(a);
            target.setGravity(Gravity.CENTER_VERTICAL);
            target.addView(c, new LinearLayout.LayoutParams(-2, -2));
            Ui.touchTarget(c, target);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.setMargins(0, 0, dp(6), 0);
            target.setMinimumHeight(dp(48));
            chips.addView(target, p);
            if (on) {
                HorizontalScrollView scroll = (HorizontalScrollView) chips.getParent();
                scroll.post(() -> scroll.smoothScrollTo(target.getLeft() - dp(16), 0));
            }
        }
    }

    static String dateBucket(double ts) {
        if (ts <= 0) return "EARLIER";
        long diff = System.currentTimeMillis() - (long) (ts * 1000);
        String d = ChatActivity.day(ts);
        if ("Today".equals(d)) return "TODAY";
        if ("Yesterday".equals(d)) return "YESTERDAY";
        if (diff < 7L * 86_400_000) return "THIS WEEK";
        if (diff < 31L * 86_400_000) return "THIS MONTH";
        return "EARLIER";
    }

    private void render(String query) {
        if (list == null) return;
        list.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<HermesApi.SessionInfo> inbox = new ArrayList<>(), pinnedList = new ArrayList<>(), rest = new ArrayList<>();
        Set<String> pendingProposals = store.pendingProposals();
        for (HermesApi.SessionInfo si : sessions) {
            if (!q.isEmpty() && SessionSearch.score(si.displayTitle(), si.preview, q) == SessionSearch.NO_MATCH) continue;
            if ("needs".equals(filter)) { if (!needsYou(si, approvalPending(runs, apiProfile, si.id), pendingProposals)) continue; }
            else if (!"all".equals(filter) && !kindOf(si).equals(filter)) continue;
            if (isInboxSession(si)) inbox.add(si); else (si.pinned ? pinnedList : rest).add(si);
        }
        if (!q.isEmpty()) {
            // Searching: best title matches first, newest first within a score, no date groups in the way.
            List<HermesApi.SessionInfo> found = new ArrayList<>(pinnedList);
            found.addAll(rest);
            final String fq = q;
            java.util.Collections.sort(found, (x, y) -> {
                int c = Integer.compare(SessionSearch.score(y.displayTitle(), y.preview, fq), SessionSearch.score(x.displayTitle(), x.preview, fq));
                return c != 0 ? c : Double.compare(y.lastActive, x.lastActive);
            });
            if (!found.isEmpty()) { header("MATCHING CONVERSATIONS · " + found.size()); for (HermesApi.SessionInfo si : found) list.addView(cachedRow(si), match(70)); }
            pinnedList.clear(); rest.clear();
            loadEverythingForSearch();
        }
        if (!pinnedList.isEmpty()) { header("PINNED"); for (HermesApi.SessionInfo si : pinnedList) list.addView(cachedRow(si), match(70)); }
        String bucket = "";
        java.util.Map<String, JobGroup> groups = new java.util.HashMap<>();
        for (JobGroup g : groupJobs(rest)) if (g.runs.size() > 1) groups.put(g.key, g);
        for (HermesApi.SessionInfo si : rest) {
            JobGroup g = kindOf(si).equals("auto") ? groups.get(groupKey(si)) : null;
            if (g != null && g.latest() != si) continue;
            String b = dateBucket(si.lastActive);
            if (!b.equals(bucket)) { header(b); bucket = b; }
            if (g != null) list.addView(groupView(g, !q.isEmpty()), match(-2)); else list.addView(cachedRow(si), match(70));
        }
        if (!inbox.isEmpty()) { header("ESSENTIALS"); for (HermesApi.SessionInfo si : inbox) list.addView(cachedRow(si), match(70)); }
        if (q.isEmpty() && !everythingLoaded && !sessions.isEmpty()) {
            TextView older = Ui.text(a, loadingEverything ? "Loading older conversations…" : "Show older conversations", 13, Ui.BLUE, true);
            older.setPadding(dp(18), dp(14), dp(18), dp(18)); older.setBackground(Ui.ripple(a, Color.TRANSPARENT, 0));
            older.setOnClickListener(v -> { older.setText("Loading older conversations…"); loadEverythingForSearch(); });
            list.addView(older, match(-2));
        }
        if (inbox.isEmpty() && pinnedList.isEmpty() && rest.isEmpty() && list.getChildCount() == 0 && (q.length() < 3 || "needs".equals(filter))) {
            TextView t = Ui.text(a, "needs".equals(filter) ? "Nothing needs you right now" : q.isEmpty() ? "Nothing here yet" : "No matching conversations", 13, Ui.SUB, false);
            t.setPadding(dp(18), dp(20), 0, 0); list.addView(t);
        }
        int localCount = inbox.size() + pinnedList.size() + rest.size() + (q.isEmpty() ? 0 : Math.max(0, list.getChildCount() - 1));
        if (everythingFailed && !q.isEmpty()) header("Older conversations couldn't be loaded · search covers recent ones only");
        if (q.length() >= 3 && localCount < 5 && !"needs".equals(filter)) {
            if (!q.equals(searchState)) {
                searchState = q; messages.clear(); searchError = false; searching = true;
                if (searchTask != null) ui.removeCallbacks(searchTask);
                searchTask = () -> network.submit(() -> {
                    try {
                        List<HubApi.SearchResult> found = new HubApi(store.hubUrl(), store.hubKey()).search(q, 30);
                        a.runOnUiThread(() -> { if (q.equals(searchState) && !closed) { messages = found; searching = false; render(q); } });
                    } catch (Exception e) {
                        a.runOnUiThread(() -> { if (q.equals(searchState) && !closed) { searchError = true; searching = false; render(q); } });
                    }
                });
                ui.postDelayed(searchTask, 400);
            }
            header("MESSAGES");
            if (searching || searchError) {
                TextView state = Ui.text(a, searching ? "Searching…" : "Message search unavailable", 12, Ui.FAINT, false);
                state.setPadding(dp(18), dp(8), 0, dp(10)); list.addView(state);
            } else if (messages.isEmpty()) {
                TextView empty = Ui.text(a, "No matching messages", 12, Ui.FAINT, false);
                empty.setPadding(dp(18), dp(8), 0, dp(10)); list.addView(empty);
            } else for (HubApi.SearchResult result : messages) list.addView(messageRow(result, q), match(-2));
        } else {
            if (searchTask != null) ui.removeCallbacks(searchTask);
            searchState = ""; messages.clear(); searching = false;
        }
    }

    private View messageRow(HubApi.SearchResult result, String q) {
        LinearLayout row = new LinearLayout(a); row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(18), dp(9), dp(18), dp(9)); row.setBackground(Ui.ripple(a, Color.TRANSPARENT, 0));
        TextView heading = Ui.body(a, result.title.isEmpty() ? "Conversation" : result.title, Ui.TEXT); heading.setTypeface(Ui.WORD_SEMI);
        heading.setSingleLine(true); heading.setEllipsize(TextUtils.TruncateAt.END); row.addView(heading);
        row.addView(Ui.caption(a, result.role + " · " + ChatActivity.relTime(result.ts), Ui.FAINT));
        String snippet = result.snippet; SpannableString marked = new SpannableString(snippet);
        String lower = snippet.toLowerCase(Locale.ROOT); int from = 0, at;
        while ((at = lower.indexOf(q, from)) >= 0) {
            marked.setSpan(new StyleSpan(Typeface.BOLD), at, at + q.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            marked.setSpan(new BackgroundColorSpan((Ui.BLUE & 0xffffff) | 0x66000000), at, at + q.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = at + q.length();
        }
        TextView body = Ui.text(a, "", 13, Ui.SUB, false); body.setText(marked); body.setMaxLines(3);
        body.setEllipsize(TextUtils.TruncateAt.END); row.addView(body);
        row.setOnClickListener(v -> host.openMessage(result.sessionId, result.title));
        return row;
    }

    private void header(String t) { TextView h = Ui.label(a, t, Ui.FAINT); h.setPadding(dp(18), dp(12), 0, dp(2)); list.addView(h, match(-2)); }

    private View cachedRow(HermesApi.SessionInfo si) {
        View v = rowCache.get(si.id);
        if (v == null) rowCache.put(si.id, v = row(si));
        if (v.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) v.getParent()).removeView(v);
        return v;
    }

    /** One collapsible row per scheduled job: latest run time, run count and last preview; tap to show the runs inline. */
    private View groupView(JobGroup g, boolean searchHit) {
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout head = new LinearLayout(a); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(dp(8), dp(8), dp(12), dp(8));
        head.setBackground(Ui.card(a, 1)); head.setMinimumHeight(dp(64));
        Ui.press(head);
        HermesApi.SessionInfo last = g.latest();
        boolean open = searchHit || expanded.contains(g.key);
        String runs = g.runs.size() + " runs";
        head.setContentDescription(g.name + ", scheduled job, " + runs + ", latest " + ChatActivity.relTime(last.lastActive) + (open ? ", expanded" : ", collapsed"));
        TextView ic = Ui.text(a, "⏱", 16, Ui.SUB, false); ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.circle(Ui.tier(2))); ic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        head.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout mid = new LinearLayout(a); mid.setOrientation(LinearLayout.VERTICAL); mid.setPadding(dp(12), 0, dp(8), 0);
        TextView name = Ui.title(a, g.name, Ui.TEXT); name.setTextSize(15); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END); mid.addView(name);
        TextView prev = Ui.body(a, ChatActivity.scheduledPreview(previewOf(last)), Ui.SUB); prev.setTextSize(12.5f);
        prev.setSingleLine(true); prev.setEllipsize(TextUtils.TruncateAt.END); mid.addView(prev);
        head.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout right = new LinearLayout(a); right.setOrientation(LinearLayout.VERTICAL); right.setGravity(Gravity.END);
        TextView when = Ui.num(a, ChatActivity.relTime(last.lastActive), 12, Ui.FAINT); when.setGravity(Gravity.END); right.addView(when);
        TextView count = Ui.num(a, runs + (open ? " ▴" : " ▾"), 12, Ui.SUB); count.setGravity(Gravity.END); right.addView(count);
        head.addView(right, new LinearLayout.LayoutParams(-2, -2));
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout body = new LinearLayout(a); body.setOrientation(LinearLayout.VERTICAL);
        for (HermesApi.SessionInfo si : g.runs) body.addView(cachedRow(si), match(70));
        box.addView(body, new LinearLayout.LayoutParams(-1, -2));
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        head.setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            if (show) expanded.add(g.key); else expanded.remove(g.key);
            count.setText(runs + (show ? " ▴" : " ▾"));
            head.setContentDescription(g.name + ", scheduled job, " + runs + ", latest " + ChatActivity.relTime(last.lastActive) + (show ? ", expanded" : ", collapsed"));
            animateBody(body, show);
        });
        return box;
    }

    private static void animateBody(View body, boolean show) {
        body.animate().cancel();
        if (!Ui.motionEnabled()) { body.setVisibility(show ? View.VISIBLE : View.GONE); return; }
        if (show) {
            body.setVisibility(View.VISIBLE); body.setAlpha(0f); body.setTranslationY(-body.getResources().getDisplayMetrics().density * 8);
            body.animate().alpha(1f).translationY(0).setDuration(220).setInterpolator(Ui.EASE_OUT).start();
        } else {
            body.animate().alpha(0f).setDuration(140).setInterpolator(Ui.EASE_OUT).withEndAction(() -> { body.setVisibility(View.GONE); body.setAlpha(1f); }).start();
        }
    }

    private View row(HermesApi.SessionInfo si) {
        LinearLayout row = new LinearLayout(a); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(14), dp(4), dp(12), dp(4));
        boolean active = si.id.equals(host.currentSessionId());
        boolean working = runs.active(apiProfile, si.id) != null;
        android.graphics.drawable.RippleDrawable rr = new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf((Ui.BLUE & 0xffffff) | 0x22000000),
                active ? Ui.round(a, Ui.tier(2), 12) : null, Ui.round(a, Color.WHITE, 12));
        row.setBackground(new android.graphics.drawable.InsetDrawable(rr, dp(8), 0, dp(8), 0));
        Ui.press(row);
        row.setOnLongClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); showActions(si); return true; });
        row.setOnClickListener(v -> host.open(si));
        row.setContentDescription(si.displayTitle() + (active ? ", current conversation" : "") + (si.pinned ? ", pinned" : "") + (si.unread ? ", unread" : "")
                + (working ? ", " + Backends.type(api) + " is working" : "") + ", " + previewOf(si));
        row.setMinimumHeight(dp(60)); // no letter avatars: title and preview carry the row, as in Claude and ChatGPT
        LinearLayout mid = new LinearLayout(a); mid.setOrientation(LinearLayout.VERTICAL); mid.setPadding(dp(6), 0, dp(8), 0);
        TextView name = Ui.body(a, si.displayTitle(), Ui.TEXT); name.setTypeface(Ui.WORD_SEMI);
        name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END); mid.addView(name);
        String kind = kindOf(si);
        // A bridge's sessions all come from its own CLI, so naming the source there says nothing.
        String prefix = kind.equals("chats") ? "" : kind.equals("auto") ? "⏱ " : "Hermes".equals(Backends.type(api)) ? si.source + " · " : "";
        TextView prev = Ui.caption(a, working ? Backends.type(api) + " is working…" : prefix + (kind.equals("auto") ? ChatActivity.scheduledPreview(previewOf(si)) : previewOf(si)), working ? Ui.AMBER : Ui.SUB);
        prev.setSingleLine(true); prev.setEllipsize(TextUtils.TruncateAt.END); mid.addView(prev);
        row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout right = new LinearLayout(a); right.setOrientation(LinearLayout.VERTICAL); right.setGravity(Gravity.END);
        TextView when = Ui.num(a, ChatActivity.relTime(si.lastActive), 12, si.unread ? Ui.BLUE : Ui.FAINT); when.setGravity(Gravity.END); right.addView(when);
        if (si.unread) {
            View dot = new View(a); dot.setBackground(Ui.circle(Ui.BLUE));
            dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(dp(8), dp(8)); dp.gravity = Gravity.END; dp.topMargin = dp(6);
            right.addView(dot, dp);
        }
        row.addView(right, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    /** First letter or digit ("[CORRECTION REVIEW JOB] …" gives "C", not "["). */
    static String init(String t) {
        String c = t == null ? "" : t.trim();
        for (int i = 0; i < c.length(); i = c.offsetByCodePoints(i, 1)) {
            int cp = c.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) return new String(Character.toChars(cp)).toUpperCase(Locale.ROOT);
        }
        return c.isEmpty() ? "?" : c.substring(0, 1);
    }
    private static String n(String s) { return s == null ? "" : s; }
    /** Sessions the Essentials inbox owns: follow-ups on captures, not the user's own conversations. */
    static boolean isInboxSession(HermesApi.SessionInfo si) { return si != null && n(si.displayTitle()).trim().toLowerCase(Locale.ROOT).startsWith("essentials"); }
    static String previewOf(HermesApi.SessionInfo si) { String p = DeviceContext.stripScreen(n(si.preview)).trim(); String cap = ChatLogic.captureLabel(p); if (cap != null) return cap; if (!p.isEmpty()) return p; String m = n(si.model).trim(); return m.isEmpty() ? "No messages yet" : "Model · " + m; }

    private void showActions(final HermesApi.SessionInfo si) {
        Capabilities c = api.capabilitiesForUi();
        List<Sheets.Item> items = new ArrayList<>();
        // Same layout and wording as the in-chat menu (ChatActivity.showMore): quick tiles, then rows, Delete last.
        items.add(Sheets.Item.of(R.drawable.ic_edit, Ui.BLUE_DARK, "Rename", null, () -> Sheets.input(a, "Rename conversation", "Changes apply to the server-side conversation.", si.displayTitle(), "Enter a title", value -> network.submit(() -> {
            HermesApi.Result r = api.renameSession(si.id, value); a.runOnUiThread(() -> { toast(r.text); if (r.ok) host.renamed(si.id, value); load(); });
        }))));
        items.add(Sheets.Item.of(R.drawable.ic_pin, Ui.BLUE_DARK, si.pinned ? "Unpin" : "Pin", null, () -> network.submit(() -> {
            HermesApi.Result r = api.pinSession(si.id, !si.pinned); a.runOnUiThread(() -> { toast(r.text); load(); });
        })));
        items.add(Sheets.Item.of(R.drawable.ic_info, Ui.BLUE_DARK, si.unread ? "Mark read" : "Mark unread", null, () -> network.submit(() -> {
            HermesApi.Result r = api.markUnread(si.id, !si.unread); a.runOnUiThread(() -> { toast(r.text); load(); });
        })));
        if (c.forks) items.add(Sheets.Item.of(R.drawable.ic_fork, Ui.BLUE_DARK, "Fork", null, () -> Sheets.input(a, "Fork conversation", "Makes a copy to take in a new direction; this conversation stays as it is.", si.displayTitle() + " fork", "Name for the copy", value -> network.submit(() -> {
            try { api.forkSession(si.id, value); a.runOnUiThread(() -> { toast("Forked"); load(); }); }
            catch (Exception e) { a.runOnUiThread(() -> toast("Fork failed: " + HermesApi.friendly(e))); }
        }), "Fork")));
        items.add(Sheets.Item.of(R.drawable.ic_archive, Ui.BLUE_DARK, "Archive", "Hide it, keep the transcript", () -> network.submit(() -> {
            HermesApi.Result r = api.archiveSession(si.id, true);
            a.runOnUiThread(() -> { toast(r.text); if (r.ok) host.removed(si.id); load(); });
        })));
        if (c.deletes) items.add(Sheets.Item.danger(R.drawable.ic_delete, "Delete", "Remove the conversation and its transcript", () -> Sheets.confirm(a, "Delete conversation?",
                "This permanently removes the server-side session and transcript.", "Delete", true, () -> network.submit(() -> {
                    HermesApi.Result r = api.deleteSession(si.id);
                    a.runOnUiThread(() -> { toast(r.text); if (r.ok) host.removed(si.id); load(); });
                }))));
        List<Sheets.Item> tiles = new ArrayList<>(), rows = new ArrayList<>();
        for (Sheets.Item item : items) (ChatLogic.overflowGroup(item.title) == 0 ? tiles : rows).add(item);
        Sheets.grid(a, si.displayTitle(), tiles.toArray(new Sheets.Item[0]), rows.toArray(new Sheets.Item[0]));
    }
}
