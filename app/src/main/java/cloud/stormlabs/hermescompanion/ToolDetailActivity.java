package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ToolDetailActivity extends Activity {
    public static final String EXTRA_NAME="name", EXTRA_ARGS="args", EXTRA_RESULT="result", EXTRA_STATUS="status";
    private int dp(int n){return Ui.dp(this,n);} private LinearLayout.LayoutParams match(int h){return new LinearLayout.LayoutParams(-1,h<0?h:dp(h));}
    @Override public void onCreate(Bundle b){super.onCreate(b);Ui.legacyWindowInsets(this);getWindow().setStatusBarColor(Ui.BAR);getWindow().setNavigationBarColor(Ui.BG);String name=getIntent().getStringExtra(EXTRA_NAME);String args=n(getIntent().getStringExtra(EXTRA_ARGS),"{}");String result=n(getIntent().getStringExtra(EXTRA_RESULT),"");String status=n(getIntent().getStringExtra(EXTRA_STATUS),"completed");build(name,args,result,status);}
    private void build(String name,String args,String result,String status){LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Ui.BG);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(4),0,dp(4),0);bar.setBackgroundColor(Ui.BAR);ImageButton back=Ui.icon(this,R.drawable.ic_back,"Back");back.setOnClickListener(v->onBackPressed());bar.addView(back,new LinearLayout.LayoutParams(dp(52),dp(56)));LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);TextView title=Ui.text(this,label(name),18,Ui.TEXT,true);titles.addView(title);TextView sub=Ui.text(this,status+" · Hermes tool call",12,"failed".equals(status)?Ui.RED:Ui.GREEN,false);titles.addView(sub);bar.addView(titles,new LinearLayout.LayoutParams(0,-2,1));ImageButton copy=Ui.icon(this,R.drawable.ic_copy,"Copy all");copy.setOnClickListener(v->copy(args+"\n\n"+result));bar.addView(copy,new LinearLayout.LayoutParams(dp(52),dp(56)));root.addView(bar,match(56));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(14),dp(16),dp(14),dp(28));
        if("terminal".equals(name)){JSONObject a=obj(args);String command=a.optString("command",args);section(body,"COMMAND",command,true);String work=a.optString("workdir","");if(!work.isEmpty())meta(body,"Working directory",work);JSONObject r=obj(result);String output=r.has("output")?r.optString("output"):result;section(body,"OUTPUT",output.isEmpty()?"(no output)":output,true);if(r.has("exit_code"))meta(body,"Exit code",String.valueOf(r.optInt("exit_code")));if(!r.isNull("error")&&!r.optString("error").isEmpty())section(body,"ERROR",r.optString("error"),true);
        }else{section(body,"INPUT",pretty(args),true);section(body,"RESULT",result.isEmpty()?"Waiting for result…":pretty(result),true);}
        TextView id=Ui.text(this,"Tap and hold inside any block to select text",12,Ui.FAINT,false);id.setPadding(dp(4),dp(8),0,0);body.addView(id);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);Ui.fitInsets(root, root);}
    private void section(LinearLayout parent,String label,String content,boolean mono){LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);TextView l=Ui.text(this,label,11,Ui.BLUE,true);l.setLetterSpacing(.08f);head.addView(l,new LinearLayout.LayoutParams(0,-2,1));TextView c=Ui.text(this,"COPY",11,Ui.SUB,true);c.setPadding(dp(10),dp(8),dp(4),dp(8));c.setOnClickListener(v->copy(content));head.addView(c);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.setMargins(dp(4),dp(10),dp(4),dp(4));parent.addView(head,hp);TextView block=Ui.text(this,content,13,Ui.TEXT,false);if(mono)block.setTypeface(Typeface.MONOSPACE);block.setTextIsSelectable(true);block.setLineSpacing(0,1.2f);block.setPadding(dp(14),dp(13),dp(14),dp(13));block.setBackground(Ui.stroke(this,Ui.SURFACE,Ui.LINE,12));parent.addView(block,match(-2));}
    private void meta(LinearLayout p,String k,String v){LinearLayout row=new LinearLayout(this);row.setPadding(dp(4),dp(8),dp(4),0);row.addView(Ui.text(this,k,12,Ui.SUB,false),new LinearLayout.LayoutParams(0,-2,1));TextView value=Ui.text(this,v,12,Ui.TEXT,true);value.setTypeface(Typeface.MONOSPACE);row.addView(value);p.addView(row,match(-2));}
    private void copy(String s){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Hermes tool call",s));Toast.makeText(this,"Copied",Toast.LENGTH_SHORT).show();}
    private static JSONObject obj(String s){try{return new JSONObject(s);}catch(Exception e){return new JSONObject();}}
    private static String pretty(String s){try{return s.trim().startsWith("[")?new JSONArray(s).toString(2):new JSONObject(s).toString(2);}catch(Exception e){return s;}}
    private static String label(String n){if(n==null||n.isEmpty())return "Tool call";String x=n.replace('_',' ');return Character.toUpperCase(x.charAt(0))+x.substring(1);}
    private static String n(String s,String f){return s==null?f:s;}
    @Override public void onBackPressed() { Ui.back(this); }
}
