package cloud.stormlabs.hermescompanion;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live pages Hermes writes in an ```html fence (infographics, charts, small tools), like Claude's artifacts: a card in the
 * chat that runs the page, and a full-screen view. The page gets JavaScript and the network (for CDN libraries) but no
 * files, no content providers, no app bridge; links open in the browser.
 */
public final class ArtifactActivity extends Activity {
    static final String EXTRA_HTML = "html";
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private WebView web;

    static boolean live(String lang) {
        return "html".equalsIgnoreCase(lang) || "artifact".equalsIgnoreCase(lang) || "svg".equalsIgnoreCase(lang);
    }

    static String title(String html) {
        Matcher m = TITLE.matcher(html);
        String t = m.find() ? m.group(1).replaceAll("\\s+", " ").trim() : "";
        return t.isEmpty() ? "Interactive" : t;
    }

    /** A fragment or bare SVG becomes a page that fits a phone screen. */
    static String page(String code) {
        String head = code.contains("viewport") ? "" : "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">";
        return head + code;
    }

    @SuppressLint("SetJavaScriptEnabled")
    static WebView web(Activity a, String html) {
        WebView w = new WebView(a);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setSupportMultipleWindows(false);
        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (r.isForMainFrame()) MarkdownView.openUrl(a, r.getUrl().toString());
                return true;
            }
        });
        // A real-looking origin so localStorage works; nothing is ever served from it.
        w.loadDataWithBaseURL("https://artifact.hermes.invalid/", page(html), "text/html", "utf-8", null);
        return w;
    }

    static View card(Activity a, String code, View.OnLongClickListener longClick) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.stroke(a, Ui.CODE_BG, Ui.LINE, 10));
        card.setClipToOutline(true);
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(a, 12), dp(a, 4), dp(a, 6), dp(a, 4));
        TextView name = Ui.text(a, "▶  " + title(code), 12, Ui.SUB, true);
        name.setSingleLine(true);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView source = Ui.pillButton(a, "Code", 0, Ui.RAISED, Ui.LINE, Ui.BLUE);
        TextView open = Ui.pillButton(a, "Open", R.drawable.ic_fullscreen, Ui.RAISED, Ui.LINE, Ui.BLUE);
        open.setContentDescription("Open full screen");
        open.setOnClickListener(v -> a.startActivity(new Intent(a, ArtifactActivity.class).putExtra(EXTRA_HTML, code)));
        head.addView(source);
        head.addView(open);
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        WebView w = web(a, code);
        // Taps and drags inside the page belong to the page, not to the chat's scrolling.
        w.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
        card.addView(w, new LinearLayout.LayoutParams(-1, dp(a, 340)));

        HorizontalScrollView hs = new HorizontalScrollView(a);
        TextView body = Ui.text(a, code, 12.5f, Ui.CODE_TEXT, false);
        body.setTypeface(Typeface.MONOSPACE);
        body.setPadding(dp(a, 12), 0, dp(a, 12), dp(a, 10));
        body.setTextIsSelectable(true);
        if (longClick != null) body.setOnLongClickListener(longClick);
        hs.addView(body);
        hs.setVisibility(View.GONE);
        card.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        source.setOnClickListener(v -> {
            boolean showCode = hs.getVisibility() != View.VISIBLE;
            hs.setVisibility(showCode ? View.VISIBLE : View.GONE);
            w.setVisibility(showCode ? View.GONE : View.VISIBLE);
            source.setText(showCode ? "Preview" : "Code");
            if (showCode) {
                ((ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("code", code));
                Toast.makeText(a, "Code copied", Toast.LENGTH_SHORT).show();
            }
        });
        return card;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String html = getIntent().getStringExtra(EXTRA_HTML);
        if (html == null) { finish(); return; }
        setTitle(title(html));
        getWindow().setStatusBarColor(Ui.BG); getWindow().setNavigationBarColor(Ui.BG);
        web = web(this, html);
        setContentView(web);
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
}
