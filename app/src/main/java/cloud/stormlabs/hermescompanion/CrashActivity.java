package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Debug aid: renders the last uncaught exception so it can be read or copied without ADB. */
public final class CrashActivity extends Activity {
    private int dp(int n){return Ui.dp(this,n);} private LinearLayout.LayoutParams match(int h){return new LinearLayout.LayoutParams(-1,h<0?h:dp(h));}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        Ui.legacyWindowInsets(this);
        getWindow().setStatusBarColor(Ui.BAR); getWindow().setNavigationBarColor(Ui.BG);
        String stack = getIntent().getStringExtra("stack");
        if (stack == null || stack.isEmpty()) stack = CrashReporter.lastCrash(this);
        if (stack == null || stack.isEmpty()) stack = "(no crash recorded)";
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Ui.BG);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(2),0,dp(8),0); bar.setBackgroundColor(Ui.BAR);
        ImageButton back = Ui.icon(this,R.drawable.ic_back,"Back");
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back,new LinearLayout.LayoutParams(dp(52),dp(56)));
        bar.addView(Ui.text(this,"Hermes crashed",18,Ui.TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        final String stackText = stack;
        ImageButton copy = Ui.icon(this,R.drawable.ic_copy,"Copy stack");
        copy.setOnClickListener(v -> {((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("stack",stackText));Toast.makeText(this,"Stack copied — paste it to the chat",Toast.LENGTH_LONG).show();});
        bar.addView(copy,new LinearLayout.LayoutParams(dp(52),dp(56)));
        root.addView(bar,match(56));
        ScrollView scroll = new ScrollView(this);
        TextView body = Ui.text(this,stack,12,Ui.pick(0xfff1a0a0,Ui.RED),false);
        body.setTypeface(Typeface.MONOSPACE); body.setTextIsSelectable(true);
        body.setPadding(dp(14),dp(14),dp(14),dp(24)); body.setLineSpacing(0,1.25f);
        scroll.addView(body); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView hint = Ui.text(this,"Tap Copy, then paste it to Hermes on Telegram.",13,Ui.SUB,false);
        hint.setGravity(Gravity.CENTER); hint.setPadding(0,dp(8),0,dp(6));
        root.addView(hint,match(-2));
        setContentView(root);Ui.fitInsets(root, root);
    }
    @Override public void onBackPressed(){ finish(); } // Ui.back would relaunch a crashing MainActivity
}
