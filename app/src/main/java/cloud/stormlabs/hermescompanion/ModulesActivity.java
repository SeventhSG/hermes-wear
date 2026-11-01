package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Settings › Modules: switch optional parts of the app on or off and set them up (same rows as the first-run step). */
public final class ModulesActivity extends Activity {
    private LinearLayout list;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.legacyWindowInsets(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 8), 0);
        ImageButton back = Ui.icon(this, R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 56)));
        TextView title = Ui.text(this, "Modules", 22, Ui.TEXT, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar);
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 24));
        TextView intro = Ui.text(this, "Base Hermes chat always works. Everything else is optional: switch it off and the app leaves it out; tap a row to set it up.", 13, Ui.SUB, false);
        intro.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 8));
        col.addView(intro);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(Ui.card(this, 1));
        list.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        col.addView(list);
        scroll.addView(col);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        Ui.fitInsets(root, root);
    }

    @Override protected void onResume() { super.onResume(); list.removeAllViews(); Modules.render(this, list, null); } // statuses may have changed in system settings
}
