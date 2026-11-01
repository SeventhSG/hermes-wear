package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.TouchDelegate;
import android.view.WindowInsets;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;

public final class Ui {
    /** Return to the caller, or open chat when a direct entry has no caller. */
    public static void back(Activity activity) {
        if (activity.isTaskRoot()) activity.startActivity(new Intent(activity, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_HOME, true));
        activity.finish();
    }
    // Colours come from the active Concept (Settings › Appearance); Graphite is the default.
    // BLUE is the concept's accent ("interactive"), whatever its hue.
    public static int BG, BAR, SURFACE, RAISED, LINE, TEXT, SUB, FAINT;
    public static int BLUE, BLUE_DARK, GREEN, AMBER, RED;
    public static int LINK, CODE, CODE_BG, CODE_TEXT, MINE_TOP, MINE_BOTTOM, MINE_META;
    public static Concept concept;
    /** Navigation structure (Settings › Appearance › Layout); Classic is the default. */
    public static Layout layout = Layout.CLASSIC;
    /** Semibold word face of the active style (Space Grotesk Medium in Console, system bold in Refined). */
    public static Typeface BOLD;
    /** Word faces: regular, semibold, hero (700, one per screen at most). MONO/MONO_SEMI: JetBrains Mono for numbers, ids, paths, code. */
    public static Typeface WORD, WORD_SEMI, WORD_HERO, MONO, MONO_SEMI;
    private static Typeface sgReg, sgMed, sgBold, jbReg, jbMed;
    private static boolean fontsLoaded;
    static { use(Concept.DEFAULT); }
    private Ui(){}
    /** Copy a concept's palette and tokens into the shared fields. */
    static void use(Concept k){
        concept=k;BG=k.bg;BAR=k.bar;SURFACE=k.surface;RAISED=k.raised;LINE=k.line;TEXT=k.text;SUB=k.sub;FAINT=k.faint;
        BLUE=k.blue;BLUE_DARK=k.blueDark;GREEN=k.green;AMBER=k.amber;RED=k.red;
        LINK=k.link;CODE=k.code;CODE_BG=k.codeBg;CODE_TEXT=k.codeText;MINE_TOP=k.mineTop;MINE_BOTTOM=k.mineBottom;MINE_META=k.mineMeta;
        boolean console=k.style==Concept.Style.CONSOLE;
        WORD=console&&sgReg!=null?sgReg:Typeface.DEFAULT;
        WORD_SEMI=console&&sgMed!=null?sgMed:Typeface.DEFAULT_BOLD;
        WORD_HERO=console&&sgBold!=null?sgBold:Typeface.DEFAULT_BOLD;
        MONO=jbReg!=null?jbReg:Typeface.MONOSPACE;
        MONO_SEMI=jbMed!=null?jbMed:Typeface.create(Typeface.MONOSPACE,Typeface.BOLD);
        BOLD=k.mono?Typeface.create(Typeface.MONOSPACE,Typeface.BOLD):WORD_SEMI;
    }
    /** Loads the bundled fonts once (res.getFont is API 26+, our minSdk); a failure leaves the system faces in place. */
    private static void fonts(Context c){
        if(fontsLoaded)return;
        fontsLoaded=true;
        try{
            android.content.res.Resources r=c.getResources();
            sgReg=r.getFont(R.font.spacegrotesk_regular);sgMed=r.getFont(R.font.spacegrotesk_medium);sgBold=r.getFont(R.font.spacegrotesk_bold);
            jbReg=r.getFont(R.font.jetbrainsmono_regular);jbMed=r.getFont(R.font.jetbrainsmono_medium);
        }catch(Exception ignored){}
    }
    /** Word typeface for a concept's style (used by previews that show a style other than the active one). */
    static Typeface wordFont(Concept k){return k.style==Concept.Style.CONSOLE&&sgReg!=null?sgReg:Typeface.DEFAULT;}
    /** Load the stored concept (cheap: SharedPreferences are cached). */
    public static void apply(Context c){SettingsStore s=new SettingsStore(c);fonts(c);use(Concept.byId(s.uiConcept()));accent(s.uiAccent());layout=Layout.byId(s.uiLayout());}
    /** Re-colour the interactive tokens with the user's accent (0 = keep the theme's own). Call right after {@link #use}. */
    static void accent(int argb){
        if(argb==0||concept==null)return;
        int a=Accent.readableOn(argb,BG,4.5);
        BLUE=a;BLUE_DARK=Accent.darker(a,.3f);
        LINK=a;
        if(Accent.luminance(MINE_TOP)<.4){MINE_TOP=Accent.fillForWhiteText(argb);MINE_BOTTOM=Accent.darker(MINE_TOP,.12f);MINE_META=Accent.lighter(argb,.6f);}
        else{MINE_TOP=MINE_BOTTOM=Accent.mix(argb,concept.bg,.82f);}
    }
    /** Pick an accent and rebuild every open screen. */
    public static void switchAccent(Context c,int argb){
        new SettingsStore(c).uiAccent(argb);use(concept);accent(argb);
        for(Activity a:new java.util.ArrayList<>(screens.keySet()))if(a!=null&&!a.isFinishing()&&!a.isDestroyed())a.recreate();
    }
    /** Switch layout and start over at its home screen in a fresh task. */
    public static void switchLayout(Activity a,Layout l){
        new SettingsStore(a).uiLayout(l.id);layout=l;
        a.startActivity(new Intent(a,MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_HOME,true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
    private static final java.util.Map<Activity,Boolean> screens=new java.util.WeakHashMap<>();
    /** Switch concept and rebuild every open screen so the change shows at once. */
    public static void switchTo(Context c,Concept k){
        SettingsStore st=new SettingsStore(c);st.uiConcept(k.id);fonts(c);use(k);accent(st.uiAccent());
        for(Activity a:new java.util.ArrayList<>(screens.keySet()))if(a!=null&&!a.isFinishing()&&!a.isDestroyed())a.recreate();
    }
    /**
     * Called first thing in every screen's onCreate: applies the concept (theme for text fields and
     * the monospace font, light system bars for Paper) and remembers the screen for switchTo.
     * TargetSdk-35 devices enforce edge-to-edge; opt back into inset-fitted bars for our fixed-chrome layout.
     */
    public static void legacyWindowInsets(Activity a){
        apply(a);screens.put(a,true);
        if(themed(a)&&concept.light)a.setTheme(R.style.AppTheme_Paper);
        if(concept.light){
            int flags=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if(Build.VERSION.SDK_INT>=27)flags|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            View decor=a.getWindow().getDecorView();decor.setSystemUiVisibility(decor.getSystemUiVisibility()|flags);
        }
        if(android.os.Build.VERSION.SDK_INT>=35)a.getWindow().setDecorFitsSystemWindows(true);
    }
    /** Only screens on the app theme are re-themed; translucent ones keep theirs. */
    private static boolean themed(Activity a){
        try{return a.getPackageManager().getActivityInfo(a.getComponentName(),0).getThemeResource()==R.style.AppTheme;}catch(Exception e){return false;}
    }
    /** A Refined-Dark-tuned literal colour, or its concept-token stand-in elsewhere (keeps Refined Dark pixel-identical). */
    public static int pick(int graphite,int token){return concept==Concept.REFINED_DARK?graphite:token;}
    /** Padding/margin in dp scaled by the concept's density (Paper roomier, Midnight/Terminal tighter). */
    public static int space(Context c,int v){return dp(c,Math.round(v*concept.density));}
    public static int dp(Context c,int v){return Math.round(v*c.getResources().getDisplayMetrics().density);}
    public static boolean motionEnabled(){return ValueAnimator.areAnimatorsEnabled();}
    public static void fadeIn(View v){
        if(!motionEnabled()){v.setAlpha(1f);return;}
        v.animate().cancel();v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
    }
    public static void riseIn(View v){
        if(!motionEnabled()){v.setAlpha(1f);v.setTranslationY(0);return;}
        v.animate().cancel();v.setAlpha(0f);v.setTranslationY(dp(v.getContext(),12));
        v.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(EASE_OUT).start();
    }
    public static void showFade(View v){
        if(v.getVisibility()==View.VISIBLE)return;
        v.setVisibility(View.VISIBLE);fadeIn(v);
    }
    public static void swapIcon(ImageButton v,int res,String description){
        v.setContentDescription(description);
        if(java.util.Objects.equals(v.getTag(),res))return;
        v.setTag(res);
        v.animate().cancel();
        if(!motionEnabled()){v.setImageResource(res);v.setAlpha(1f);v.setScaleX(1f);v.setScaleY(1f);return;}
        v.animate().alpha(0f).scaleX(.88f).scaleY(.88f).setDuration(80)
                .withEndAction(()->{v.setImageResource(res);v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(160).setInterpolator(EASE_OUT).start();}).start();
    }
    public static void appearing(ViewGroup group){
        if(!motionEnabled())return;
        LayoutTransition t=new LayoutTransition();
        t.disableTransitionType(LayoutTransition.CHANGING);
        t.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        t.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        t.setDuration(180);
        t.setInterpolator(LayoutTransition.APPEARING,new DecelerateInterpolator());
        t.setInterpolator(LayoutTransition.DISAPPEARING,new DecelerateInterpolator());
        group.setLayoutTransition(t);
    }

    /** Light tick for toggles and primary buttons. */
    public static void tick(View v){v.performHapticFeedback(Build.VERSION.SDK_INT>=30?HapticFeedbackConstants.CONFIRM:HapticFeedbackConstants.VIRTUAL_KEY);}

    /** Runs an infinite reversing animation only while the view is attached (and motion is on). */
    private static void loop(View v,long ms,PropertyValuesHolder... props){
        if(!motionEnabled())return;
        ObjectAnimator a=ObjectAnimator.ofPropertyValuesHolder(v,props);
        a.setDuration(ms);a.setRepeatCount(ValueAnimator.INFINITE);a.setRepeatMode(ValueAnimator.REVERSE);
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View x){if(motionEnabled()&&!a.isStarted())a.start();}
            public void onViewDetachedFromWindow(View x){a.cancel();x.setAlpha(1f);x.setScaleX(1f);x.setScaleY(1f);}
        });
        if(v.isAttachedToWindow())a.start();
    }
    /** Gentle breathing for "running/online" status dots. */
    public static void pulse(View v){
        loop(v,1100,PropertyValuesHolder.ofFloat(View.SCALE_X,1f,1.3f),PropertyValuesHolder.ofFloat(View.SCALE_Y,1f,1.3f),PropertyValuesHolder.ofFloat(View.ALPHA,1f,.55f));
    }
    /** Shimmer (soft alpha sweep) for loading placeholders. */
    public static void shimmer(View v){loop(v,800,PropertyValuesHolder.ofFloat(View.ALPHA,1f,.4f));}
    /** N shimmering placeholder rows (an avatar dot plus two bars), for loading lists. */
    public static View skeletonRows(Context c,int n){
        LinearLayout box=new LinearLayout(c);box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c,16),dp(c,12),dp(c,16),dp(c,12));
        box.setContentDescription("Loading");box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        int[] w={70,55,80,45,65};
        for(int i=0;i<n;i++){
            LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);
            View dot=new View(c);dot.setBackground(circle(RAISED));
            row.addView(dot,new LinearLayout.LayoutParams(dp(c,36),dp(c,36)));
            LinearLayout col=new LinearLayout(c);col.setOrientation(LinearLayout.VERTICAL);col.setPadding(dp(c,12),0,0,0);
            View a=new View(c);a.setBackground(round(c,RAISED,6));
            View b=new View(c);b.setBackground(round(c,RAISED,6));
            col.addView(a,new LinearLayout.LayoutParams(-1,dp(c,12)));
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(c,100+w[i%w.length]),dp(c,10));bp.topMargin=dp(c,8);
            col.addView(b,bp);
            row.addView(col,new LinearLayout.LayoutParams(0,-2,1));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(c,14);
            box.addView(row,lp);
        }
        shimmer(box);
        return box;
    }

    /** The one entrance/settle curve: fast start, long soft landing. */
    public static final Interpolator EASE_OUT=new PathInterpolator(.2f,0f,0f,1f);
    private static final java.util.Map<ViewGroup,Boolean> staggered=new java.util.WeakHashMap<>();
    /** First time a group has children: fade and slide them in with a small per-item delay. */
    public static void stagger(ViewGroup g){
        if(!motionEnabled()||g.getChildCount()==0||staggered.containsKey(g))return;
        staggered.put(g,true);
        int rise=dp(g.getContext(),14);
        for(int i=0;i<g.getChildCount()&&i<6;i++){
            View v=g.getChildAt(i);
            v.setAlpha(0f);v.setTranslationY(rise);
            v.animate().alpha(1f).translationY(0).setStartDelay(i*35L).setDuration(320).setInterpolator(EASE_OUT).start();
        }
    }

    private static final java.util.Set<String> counted=new java.util.HashSet<>();
    /** Counts the first integer in the text up from 0 the first time this key is shown. */
    public static void countUp(TextView t,String key){
        if(!motionEnabled()||!counted.add(key))return;
        String full=t.getText().toString();
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\d[\\d,]*").matcher(full);
        if(!m.find())return;
        long target;
        try{target=Long.parseLong(m.group().replace(",",""));}catch(NumberFormatException e){return;}
        if(target<2)return;
        String pre=full.substring(0,m.start()),post=full.substring(m.end());
        boolean commas=m.group().indexOf(',')>=0;
        ValueAnimator a=ValueAnimator.ofFloat(0f,1f);a.setDuration(700);a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(x->{long n=(long)(target*(float)x.getAnimatedValue());t.setText(pre+(commas?String.format(java.util.Locale.US,"%,d",n):String.valueOf(n))+post);});
        a.addListener(new android.animation.AnimatorListenerAdapter(){public void onAnimationEnd(android.animation.Animator x){t.setText(full);}});
        a.start();
    }

    /** Ui.BG with two very faint accent glows drifting slowly; static when motion is off, paused while detached. */
    public static void ambient(View v){
        AmbientDrawable d=new AmbientDrawable();
        v.setBackground(d);
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View x){d.run(true);}
            public void onViewDetachedFromWindow(View x){d.run(false);}
        });
        if(v.isAttachedToWindow())d.run(true);
    }
    private static final class AmbientDrawable extends Drawable{
        private final Paint a=new Paint(Paint.ANTI_ALIAS_FLAG),b=new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator anim;private float phase;private float r;private long last;
        void run(boolean on){
            if(anim!=null){anim.cancel();anim=null;}
            if(!on||!motionEnabled())return;
            anim=ValueAnimator.ofFloat(0f,(float)(2*Math.PI));anim.setDuration(16000);
            anim.setRepeatCount(ValueAnimator.INFINITE);anim.setInterpolator(new LinearInterpolator());
            // ~12 fps is indistinguishable for a 26 s drift and keeps full-screen redraws off the scroll path.
            anim.addUpdateListener(x->{long now=android.os.SystemClock.uptimeMillis();if(now-last<80)return;last=now;phase=(float)x.getAnimatedValue();invalidateSelf();});
            anim.start();
        }
        // Views call setVisible(false) when their window goes away (screen off, another app on top): stop animating then.
        @Override public boolean setVisible(boolean visible,boolean restart){boolean changed=super.setVisible(visible,restart);if(changed)run(visible);return changed;}
        @Override protected void onBoundsChange(Rect bb){
            r=Math.max(bb.width(),bb.height())*.6f;
            if(r<=0)return;
            a.setShader(new RadialGradient(0,0,r,(BLUE&0xffffff)|0x38000000,BLUE&0xffffff,Shader.TileMode.CLAMP));
            b.setShader(new RadialGradient(0,0,r,(0x8b6aae&0xffffff)|0x30000000,0x8b6aae,Shader.TileMode.CLAMP));
        }
        @Override public void draw(Canvas c){
            Rect bb=getBounds();c.drawColor(BG);
            if(r<=0)return;
            float w=bb.width(),h=bb.height();
            int s=c.save();c.translate(w*(.25f+.2f*(float)Math.sin(phase)),h*(.2f+.12f*(float)Math.cos(phase)));c.drawCircle(0,0,r,a);c.restoreToCount(s);
            s=c.save();c.translate(w*(.8f-.2f*(float)Math.cos(phase)),h*(.85f-.12f*(float)Math.sin(phase)));c.drawCircle(0,0,r,b);c.restoreToCount(s);
        }
        @Override public void setAlpha(int x){}
        @Override public void setColorFilter(ColorFilter f){}
        @Override public int getOpacity(){return PixelFormat.OPAQUE;}
    }
    public static void pressFeedback(View v){
        v.setOnTouchListener((target,event)->{
            int action=event.getActionMasked();
            if((action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)
                    && !(target.getBackground() instanceof RippleDrawable)){
                float scale=action==MotionEvent.ACTION_DOWN&&target.isEnabled()&&motionEnabled()?.97f:1f;
                target.animate().scaleX(scale).scaleY(scale).setDuration(150)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
            return false;
        });
    }
    public static TextView text(Context c,String value,float size,int color,boolean bold){TextView v=new TextView(c);v.setText(value);v.setTextSize(size*concept.type);v.setTextColor(color);v.setTypeface(bold?BOLD:concept.mono?Typeface.MONOSPACE:WORD);return v;}
    /** Shared reminder on both landing screens; dismissal lasts until screen control connects again. */
    public static View screenControlReminder(Activity a, SettingsStore store) {
        LinearLayout row = new LinearLayout(a); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(a, 12), dp(a, 8), dp(a, 4), dp(a, 8));
        row.setBackground(stroke(a, SURFACE, (AMBER&0xffffff)|0x66000000, 12));
        LinearLayout.LayoutParams margins = new LinearLayout.LayoutParams(-1, -2);
        margins.setMargins(dp(a, 9), dp(a, 9), dp(a, 9), 0); row.setLayoutParams(margins);
        TextView message = text(a, "Screen control was turned off (usually after an update).", 12, TEXT, false);
        row.addView(message, new LinearLayout.LayoutParams(0, -2, 1));
        TextView action = text(a, "Turn it on", 12, BLUE, true);
        action.setGravity(Gravity.CENTER); action.setContentDescription("Turn on screen control in Accessibility settings");
        action.setBackground(ripple(a, Color.TRANSPARENT, 10));
        action.setOnClickListener(v -> a.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        row.addView(action, new LinearLayout.LayoutParams(dp(a, 78), dp(a, 48)));
        TextView close = text(a, "×", 24, SUB, false);
        close.setGravity(Gravity.CENTER); close.setContentDescription("Dismiss screen control reminder");
        close.setBackground(ripple(a, Color.TRANSPARENT, 10));
        close.setOnClickListener(v -> { store.dismissScreenControlReminder(); row.setVisibility(View.GONE); });
        row.addView(close, new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));
        row.setVisibility(View.GONE);
        return row;
    }
    public static GradientDrawable round(Context c,int fill,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(c,radius)*concept.radius);return d;}
    public static GradientDrawable stroke(Context c,int fill,int stroke,int radius){GradientDrawable d=round(c,fill,radius);d.setStroke(dp(c,1),stroke);return d;}
    public static GradientDrawable gradient(Context c,int top,int bottom,int radius){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{top,bottom});d.setCornerRadius(dp(c,radius)*concept.radius);return d;}
    public static Drawable ripple(Context c,int fill,int radius){return new RippleDrawable(ColorStateList.valueOf((BLUE&0xffffff)|0x22000000),round(c,fill,radius),null);}
    /** Bordered surface with a ripple clipped to its shape: the look of every tappable card/button in chat. */
    public static Drawable press(Context c,int fill,int stroke,int radius){return new RippleDrawable(ColorStateList.valueOf((BLUE&0xffffff)|0x33000000),stroke(c,fill,stroke,radius),round(c,Color.WHITE,radius));}
    /** A 40dp-high pill button: optional tinted icon + label, ripple, concept-scaled corners. */
    public static TextView pillButton(Context c,String label,int iconRes,int fill,int stroke,int color){
        TextView b=text(c,label,13,color,true);b.setGravity(Gravity.CENTER);b.setMinHeight(dp(c,40));b.setMinWidth(dp(c,40));
        b.setPadding(dp(c,14),0,dp(c,14),0);b.setBackground(press(c,fill,stroke,20));b.setClickable(true);
        if(iconRes!=0){Drawable d=c.getDrawable(iconRes).mutate();d.setTint(color);d.setBounds(0,0,dp(c,16),dp(c,16));b.setCompoundDrawablesRelative(d,null,null,null);b.setCompoundDrawablePadding(dp(c,6));}
        return b;
    }
    // ---------------------------------------------------------------- buttons: one primary, one secondary, one ghost
    private static Drawable buttonBg(Context c,int fill,int stroke,int ripple){
        GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(controlRadius(c));if(stroke!=0)d.setStroke(dp(c,1),stroke);
        GradientDrawable m=new GradientDrawable();m.setColor(Color.WHITE);m.setCornerRadius(controlRadius(c));
        return new RippleDrawable(ColorStateList.valueOf(ripple),d,m);
    }
    private static TextView button(Context c,String label,int color,Drawable bg){
        TextView b=styled(c,label,14,color,WORD_SEMI);b.setGravity(Gravity.CENTER);b.setMinHeight(dp(c,48));b.setMinWidth(dp(c,48));
        b.setPadding(dp(c,16),0,dp(c,16),0);b.setBackground(bg);b.setClickable(true);b.setSingleLine(true);pressFeedback(b);return b;
    }
    /** The one filled action in a group (accent fill, white label, 48dp). */
    public static TextView primaryButton(Context c,String label){return primaryButton(c,label,BLUE);}
    /** Primary in a signal colour (e.g. RED for a destructive confirm). */
    public static TextView primaryButton(Context c,String label,int fill){return button(c,label,Color.WHITE,buttonBg(c,fill,0,0x33ffffff));}
    /** Secondary: raised tier fill, hairline, normal text. */
    public static TextView secondaryButton(Context c,String label){return button(c,label,TEXT,buttonBg(c,tier(2),LINE,(BLUE&0xffffff)|0x22000000));}
    /** Ghost: no fill, coloured label (cancel, deny, dismiss). */
    public static TextView ghostButton(Context c,String label,int color){return button(c,label,color,buttonBg(c,Color.TRANSPARENT,0,(BLUE&0xffffff)|0x22000000));}
    public static GradientDrawable circle(int fill){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(fill);return d;}
    public static ImageButton icon(Context c,int res,String description){ImageButton b=new ImageButton(c);b.setImageResource(res);b.setContentDescription(description);b.setBackgroundColor(Color.TRANSPARENT);b.setImageTintList(ColorStateList.valueOf(SUB));b.setPadding(dp(c,10),dp(c,10),dp(c,10),dp(c,10));pressFeedback(b);return b;}
    /** Expand a small control's hit area in an ancestor without changing its layout. An ancestor holds one TouchDelegate, so one control per ancestor. */
    public static void touchTarget(View target, ViewGroup ancestor) {
        target.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (target.getWidth() == 0 || target.getHeight() == 0) return;
            Rect bounds = new Rect(0, 0, target.getWidth(), target.getHeight());
            ancestor.offsetDescendantRectToMyCoords(target, bounds);
            int size = dp(target.getContext(), 48);
            bounds.inset(-Math.max(0, (size - bounds.width() + 1) / 2), -Math.max(0, (size - bounds.height() + 1) / 2));
            if (bounds.left < 0) bounds.offset(-bounds.left, 0);
            if (bounds.right > ancestor.getWidth()) bounds.offset(ancestor.getWidth() - bounds.right, 0);
            if (bounds.top < 0) bounds.offset(0, -bounds.top);
            if (bounds.bottom > ancestor.getHeight()) bounds.offset(0, ancestor.getHeight() - bounds.bottom);
            ancestor.setTouchDelegate(new TouchDelegate(bounds, target));
        });
    }
    public static TextView avatar(Context c,String title,int color,int size){String clean=title==null?"H":title.trim();String initial=clean.isEmpty()?"H":clean.substring(0,1).toUpperCase(java.util.Locale.ROOT);TextView v=text(c,initial,size*.34f,Color.WHITE,true);v.setGravity(Gravity.CENTER);v.setBackground(circle(color));return v;}
    /**
     * Branded Hermes mark: custom-drawn winged H on its own filled blue circle. Fixed brand blues
     * (not Concept colours) so it reads as a badge on every theme, including Paper's light background.
     * Path/shader are built once per size in onSizeChanged, not per frame.
     */
    public static View hermesLogo(Context c,int size){return new HermesLogoView(c,size);}
    /** Icon V5e (matches the launcher icon): glossy blue orb, stencil H, tilted orbit with one node. Drawn in the 108-unit icon grid. */
    private static final class HermesLogoView extends View {
        private static final android.graphics.Path H=new android.graphics.Path(),BACK=new android.graphics.Path(),FRONT=new android.graphics.Path();
        static {
            for(float[] r:new float[][]{{38,34,47,51},{38,57,47,74},{61,34,70,51},{61,57,70,74},{49.5f,50.5f,58.5f,57.5f}})H.addRect(r[0],r[1],r[2],r[3],android.graphics.Path.Direction.CW);
            android.graphics.RectF orbit=new android.graphics.RectF(14,40,94,68);BACK.addArc(orbit,180,180);FRONT.addArc(orbit,0,180);
        }
        private final android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RadialGradient orb=new android.graphics.RadialGradient(41,30,68.5f,new int[]{0xff86c6ff,0xff3f86e6,0xff163f82},new float[]{0,.5f,1},android.graphics.Shader.TileMode.CLAMP);
        HermesLogoView(Context c,int size){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);setContentDescription("Hermes mark");setMinimumWidth(dp(c,size));setMinimumHeight(dp(c,size));}
        @Override protected void onDraw(android.graphics.Canvas canvas){super.onDraw(canvas);float s=Math.min(getWidth(),getHeight());if(s<=0)return;
            canvas.save();canvas.translate((getWidth()-s)/2f,(getHeight()-s)/2f);canvas.scale(s/108f,s/108f);
            p.setStyle(android.graphics.Paint.Style.FILL);p.setShader(orb);p.setShadowLayer(8,0,5,0x55000000);canvas.drawCircle(54,54,50.76f,p);p.clearShadowLayer();p.setShader(null);
            p.setStyle(android.graphics.Paint.Style.STROKE);p.setStrokeWidth(2.7f);p.setColor(0x66d9eeff);canvas.drawCircle(54,54,48.6f,p);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            canvas.save();canvas.rotate(-22,54,54);p.setStrokeWidth(3);p.setColor(0x73d6ebff);canvas.drawPath(BACK,p);canvas.restore();
            p.setStyle(android.graphics.Paint.Style.FILL);p.setColor(Color.WHITE);canvas.drawPath(H,p);
            canvas.save();canvas.rotate(-22,54,54);p.setStyle(android.graphics.Paint.Style.STROKE);p.setStrokeWidth(3.4f);p.setColor(0xffd6ebff);canvas.drawPath(FRONT,p);
            p.setStyle(android.graphics.Paint.Style.FILL);p.setColor(Color.WHITE);canvas.drawCircle(88.64f,61,5.2f,p);canvas.restore();
            canvas.restore();
        }
    }

    /** Lighten a colour per channel (adding to the packed int overflows one channel into the next). */
    public static int lighten(int c, int amount) {
        int r = Math.min(255, ((c >> 16) & 255) + amount), g = Math.min(255, ((c >> 8) & 255) + amount), b = Math.min(255, (c & 255) + amount);
        return (c & 0xff000000) | (r << 16) | (g << 8) | b;
    }
    public static int avatarColor(String id){int[] colors={0xff4f86c6,0xff6f64c2,0xffc06868,0xff4f9a83,0xffb07a47,0xff8b6aae};int h=id==null?0:id.hashCode();return colors[(h&0x7fffffff)%colors.length];}

    /**
     * Fit the layout to the system bars and to the soft keyboard.
     *
     * The app draws edge to edge, so the window is never resized for either: the header sat under the
     * status bar clock, and with the keyboard open the composer and its send button sat behind it —
     * invisible, untappable, with a tap aimed at send landing on the keyboard instead. Padding the
     * column by both insets puts the chrome back where the system expects it.
     *
     * On API 35+ legacyWindowInsets opts the decor back into fitting the windows, so the bars are
     * already accounted for there and only the IME is left to this helper.
     */
    public static void fitInsets(final View root, final View column) {
        final int l = column.getPaddingLeft(), t = column.getPaddingTop(), r = column.getPaddingRight();
        final int fallbackTop = statusBarHeight(column.getContext());
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int rawTop, bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    rawTop = bars.top;
                    // One UI in gesture mode under-reports navigationBars(), so the swipe area is
                    // unioned in: on its own the dock and composer sat under the gesture handle.
                    android.graphics.Insets bottomBars = insets.getInsets(
                            WindowInsets.Type.navigationBars() | WindowInsets.Type.systemGestures());
                    bottom = Math.max(insets.getInsets(WindowInsets.Type.ime()).bottom,
                                      Math.max(bars.bottom, bottomBars.bottom));
                } else {
                    rawTop = insets.getSystemWindowInsetTop();
                    bottom = Math.max(insets.getSystemWindowInsetBottom(), 0);
                }
                // On API 35+ setDecorFitsSystemWindows is ignored for targetSdk 35 apps, so nothing
                // insets our content and the reported top can be zero. Fall back to the real status
                // bar height rather than stacking the header under the clock.
                int top = rawTop > 0 ? rawTop : fallbackTop;
                column.setPadding(l, t + top, r, bottom);
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    /** Real status-bar height from the framework, used when no inset is reported. */
    private static int statusBarHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : dp(c, 24);
    }

    /**
     * Lift a floating sheet (assistant overlay, Ask Hermes) clear of the navigation, gesture area and keyboard.
     *
     * The dock is a sibling of the scrolling column rather than a child of it, so the padding
     * fitInsets applies to the column never reached it and the record button sat under the swipe
     * handle. Gesture mode is unioned in because One UI under-reports navigationBars() there.
     */
    public static void liftAboveSystemBars(final View view) {
        final int l = view.getPaddingLeft(), t = view.getPaddingTop(), r = view.getPaddingRight(), b = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int bottom, left = 0, right = 0;
                if (Build.VERSION.SDK_INT >= 30) {
                    // Side padding from the nav bar only: systemGestures() adds the ~30dp back-swipe zones on both
                    // edges, which squeezed the assistant sheet's rows. The gesture inset still lifts the bottom.
                    android.graphics.Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
                    android.graphics.Insets gestures = insets.getInsets(WindowInsets.Type.systemGestures());
                    // The keyboard too: these are overlays (assistant sheet, Ask Hermes) whose input sat under it.
                    bottom = Math.max(insets.getInsets(WindowInsets.Type.ime()).bottom, Math.max(nav.bottom, gestures.bottom));
                    left = nav.left; right = nav.right;
                } else {
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(l + left, t, r + right, b + bottom);
                return insets;
            }
        });
        view.requestApplyInsets();
    }

    /** Kept as a named call for screens that only care about the keyboard. */
    public static void keepAboveKeyboard(final View root, final View column) {
        fitInsets(root, column);
    }

    // ---------------------------------------------------------------- type scale
    // 11 label (caps) / 14 body / 17 title / 28 metric / 40 hero; nothing else below 12sp.
    private static TextView styled(Context c,String value,float sp,int color,Typeface face){
        TextView v=new TextView(c);v.setText(value);v.setTextSize(sp);v.setTextColor(color);v.setTypeface(face);return v;
    }
    /** 40sp hero word, 700, tight. One per screen at most. */
    public static TextView display(Context c,String value,int color){TextView v=styled(c,value,40,color,WORD_HERO);v.setLetterSpacing(-.02f);return v;}
    /** 17sp semibold heading. */
    public static TextView title(Context c,String value,int color){TextView v=styled(c,value,17,color,WORD_SEMI);v.setLetterSpacing(-.01f);return v;}
    /** 14sp running text. */
    public static TextView body(Context c,String value,int color){return styled(c,value,14,color,WORD);}
    /** 11sp CAPS label, +0.08 tracking, semibold. */
    public static TextView label(Context c,String value,int color){TextView v=styled(c,value,11,color,WORD_SEMI);v.setAllCaps(true);v.setLetterSpacing(.08f);return v;}
    /** 12sp secondary text (the smallest size for anything that is not a label). */
    public static TextView caption(Context c,String value,int color){return styled(c,value,12,color,WORD);}
    /** Number, id, IP, path or model name: JetBrains Mono with tabular figures, at any size (min 12sp). */
    public static TextView num(Context c,String value,float sp,int color){
        TextView v=styled(c,value,Math.max(12f,sp),color,sp>=28?MONO_SEMI:MONO);v.setFontFeatureSettings("tnum");return v;
    }
    /** 28sp metric number. */
    public static TextView metric(Context c,String value,int color){return num(c,value,28,color);}

    // ---------------------------------------------------------------- surfaces
    /** Card background for surface tier 0-3 in the active style: Console = strokeless top-lit gradient + inner top highlight; Refined = tier fill (hairline only in Refined Light). */
    public static Drawable card(Context c,int tier){
        Concept k=concept;int fill=k.tier(tier);float r=dp(c,1)*k.cardRadius;
        if(k.style==Concept.Style.CONSOLE)return new LitCard(fill,r,k.highlightAlpha,Math.max(1,dp(c,1)));
        GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(r);
        if(k.cardStroke)d.setStroke(dp(c,1),LINE);
        return d;
    }
    /** Fill colour of a surface tier (0 ground, 1 card, 2 raised, 3 floating). */
    public static int tier(int t){return concept.tier(t);}
    /** Control corner radius in px (buttons, inputs, tiles). */
    public static float controlRadius(Context c){return dp(c,1)*concept.controlRadius;}
    /** Real elevation by tier (API 28+ adds accent-tinted shadows on dark Console, neutral elsewhere). Needs a background with an outline (Ui.card has one). */
    public static void elevate(View v,int tier){
        float d=v.getResources().getDisplayMetrics().density;
        v.setElevation(d*(tier<=0?0:tier==1?2:tier==2?6:12));
        if(Build.VERSION.SDK_INT>=28){
            int tint=concept.light?0xff3a3226:concept.style==Concept.Style.CONSOLE&&tier>=2?0xff000000|(BLUE&0xffffff):0xff000000;
            v.setOutlineAmbientShadowColor(tint);v.setOutlineSpotShadowColor(tint);
        }
    }
    /** Mixes a colour toward white by a fraction. */
    static int toward(int c,float f){
        int r=(c>>16)&255,g=(c>>8)&255,b=c&255;
        return (c&0xff000000)|(Math.round(r+(255-r)*f)<<16)|(Math.round(g+(255-g)*f)<<8)|Math.round(b+(255-b)*f);
    }
    /** Console card: gradient from fill+6% at the top to fill, plus a 1px white rim that fades out over the first two radii. Shaders are built on bounds change only. */
    private static final class LitCard extends Drawable{
        private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG),rim=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box=new RectF(),edge=new RectF();
        private final int base,hi;private final float r,px;
        LitCard(int base,float r,int highlightAlpha,int px){
            this.base=base;this.r=r;this.px=px;hi=(highlightAlpha<<24)|0xffffff;
            rim.setStyle(Paint.Style.STROKE);rim.setStrokeWidth(px);
        }
        @Override protected void onBoundsChange(Rect b){
            box.set(b);edge.set(b.left+px/2f,b.top+px/2f,b.right-px/2f,b.bottom-px/2f);
            if(b.height()<=0)return;
            fill.setShader(new LinearGradient(0,b.top,0,b.bottom,toward(base,.06f),base,Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(0,b.top,0,b.top+Math.min(b.height(),2*r),hi,hi&0xffffff,Shader.TileMode.CLAMP));
        }
        @Override public void draw(Canvas c){
            c.drawRoundRect(box,r,r,fill);
            if((hi>>>24)!=0)c.drawRoundRect(edge,r-px/2f,r-px/2f,rim);
        }
        @Override public void getOutline(Outline o){o.setRoundRect(getBounds(),r);}
        @Override public void setAlpha(int a){fill.setAlpha(a);rim.setAlpha(a);}
        @Override public void setColorFilter(ColorFilter f){fill.setColorFilter(f);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }

    // ---------------------------------------------------------------- icon tiles
    /** Status colours keep their meaning on an icon tile; anything else (the old rainbow) becomes 0 = neutral. */
    public static int iconColor(int c){return c==RED||c==AMBER||c==GREEN?c:0;}
    /**
     * Square icon tile. semantic == 0: Console = raised tier square with a sub-coloured icon, Refined = raised tier with one muted accent tint.
     * semantic != 0 (critical/watch/ok): that colour on a 16% wash of itself. Size it with the LayoutParams (36-40dp suits the 9dp padding).
     */
    public static ImageView iconTile(Context c,int iconRes,int semantic){
        ImageView v=new ImageView(c);v.setImageResource(iconRes);
        v.setPadding(dp(c,9),dp(c,9),dp(c,9),dp(c,9));
        GradientDrawable bg=new GradientDrawable();bg.setCornerRadius(controlRadius(c));
        int tint;
        if(semantic!=0){tint=semantic;bg.setColor((semantic&0xffffff)|0x29000000);}
        else{tint=concept.style==Concept.Style.CONSOLE?SUB:mixColor(SUB,BLUE,.5f);bg.setColor(concept.tier(2));}
        v.setBackground(bg);v.setImageTintList(ColorStateList.valueOf(tint));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }
    static int mixColor(int a,int b,float t){
        int r=Math.round(((a>>16)&255)*(1-t)+((b>>16)&255)*t),g=Math.round(((a>>8)&255)*(1-t)+((b>>8)&255)*t),bl=Math.round((a&255)*(1-t)+(b&255)*t);
        return 0xff000000|(r<<16)|(g<<8)|bl;
    }
    /** Settings > Appearance > Style row preview: bg, card, accent and an "Aa 42%" sample in the style's fonts. */
    public static View stylePreview(Context c,Concept k){
        fonts(c);
        return new View(c){
            private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF r=new RectF();
            @Override protected void onDraw(Canvas cv){
                float d=getResources().getDisplayMetrics().density,w=getWidth(),h=getHeight(),rad=k.controlRadius*d*.8f;
                p.setStyle(Paint.Style.FILL);p.setColor(k.bg);r.set(0,0,w,h);cv.drawRoundRect(r,rad,rad,p);
                p.setColor(k.tier(1));r.set(4*d,4*d,w-4*d,h-4*d);cv.drawRoundRect(r,rad*.7f,rad*.7f,p);
                if(k.cardStroke||k.light){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d);p.setColor(k.line);cv.drawRoundRect(r,rad*.7f,rad*.7f,p);p.setStyle(Paint.Style.FILL);}
                p.setColor(k.accent);cv.drawCircle(w-11*d,11*d,3*d,p);
                p.setTypeface(wordFont(k));p.setTextSize(17*d);p.setColor(k.text);cv.drawText("Aa",10*d,h/2f+2*d,p);
                p.setTypeface(jbReg!=null?jbReg:Typeface.MONOSPACE);p.setTextSize(12*d);p.setColor(k.ok);cv.drawText("42%",10*d,h-10*d,p);
            }
        };
    }

    // ---------------------------------------------------------------- motion primitives (all gated by motionEnabled)
    /** Press feedback: 0.97 scale in 90ms on touch-down, overshoot release over 180ms. Never consumes the touch. */
    public static void press(View v){
        v.setOnTouchListener((t,e)->{
            int a=e.getActionMasked();
            if(!motionEnabled())return false;
            if(a==MotionEvent.ACTION_DOWN&&t.isEnabled())t.animate().scaleX(.97f).scaleY(.97f).setDuration(90).setInterpolator(EASE_OUT).start();
            else if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL)t.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(new OvershootInterpolator(1.8f)).start();
            return false;
        });
    }
    /**
     * Heartbeat ring for a status dot: call beat() on every real server poll. The ring is drawn in the dot's
     * overlay and grows past its bounds, so the dot's ancestors must not clip children (beat() un-clips the parent).
     */
    public static Heartbeat heartbeat(View dot){return new Heartbeat(dot);}
    public static final class Heartbeat{
        private final View dot;private final Ring ring;private final ValueAnimator anim=ValueAnimator.ofFloat(0f,1f);
        private boolean stalled;
        private Heartbeat(View dot){
            this.dot=dot;ring=new Ring(dp(dot.getContext(),1)*1.5f);
            anim.setDuration(600);anim.setInterpolator(new DecelerateInterpolator(1.5f));
            anim.addUpdateListener(a->{ring.t=(float)a.getAnimatedValue();dot.invalidate();});
            anim.addListener(new android.animation.AnimatorListenerAdapter(){public void onAnimationEnd(android.animation.Animator x){ring.t=-1f;dot.invalidate();}});
            dot.getOverlay().add(ring);
            dot.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
                public void onViewAttachedToWindow(View x){}
                public void onViewDetachedFromWindow(View x){anim.cancel();}
            });
        }
        /** One 600ms ring: scale 1 to 2.2, alpha .5 to 0, decelerate(1.5). No-op while stalled or with animations off. */
        public void beat(){
            if(stalled||!motionEnabled()||!dot.isAttachedToWindow())return;
            if(dot.getParent() instanceof ViewGroup)((ViewGroup)dot.getParent()).setClipChildren(false);
            ring.color=concept.ok;ring.setBounds(0,0,dot.getWidth(),dot.getHeight());
            anim.cancel();anim.start();
        }
        /** Missed polls: stop beating and turn the dot amber; false restores it. */
        public void stalled(boolean s){
            if(s==stalled)return;
            stalled=s;
            if(s){anim.cancel();}
            dot.setBackgroundTintList(s?ColorStateList.valueOf(AMBER):null);
        }
        private static final class Ring extends Drawable{
            private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);float t=-1f;int color;
            Ring(float stroke){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(stroke);}
            @Override public void draw(Canvas c){
                if(t<0f)return;
                Rect b=getBounds();p.setColor(color);p.setAlpha(Math.round(255*.5f*(1f-t)));
                c.drawCircle(b.exactCenterX(),b.exactCenterY(),Math.min(b.width(),b.height())/2f*(1f+1.2f*t),p);
            }
            @Override public void setAlpha(int a){}
            @Override public void setColorFilter(ColorFilter f){}
            @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        }
    }
}
