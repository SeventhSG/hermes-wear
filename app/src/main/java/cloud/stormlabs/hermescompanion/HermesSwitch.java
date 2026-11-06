package cloud.stormlabs.hermescompanion;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** A tiny Telegram-style switch, independent of OEM widget themes. */
public final class HermesSwitch extends View {
    public interface Listener { void onChanged(boolean checked); }
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); private boolean checked; private float progress; private Listener listener;
    public HermesSwitch(Context c){super(c);setClickable(true);setContentDescription("Toggle");setOnClickListener(v->{Ui.tick(v);setChecked(!checked,true);});}
    public void setListener(Listener l){listener=l;} public boolean isChecked(){return checked;}
    public void setChecked(boolean value){setChecked(value,false);}
    public void setChecked(boolean value,boolean animate){if(checked==value&&progress==(value?1f:0f))return;checked=value;float to=value?1f:0f;if(!animate){progress=to;invalidate();}else{ValueAnimator a=ValueAnimator.ofFloat(progress,to);a.setDuration(180);a.setInterpolator(new DecelerateInterpolator());a.addUpdateListener(x->{progress=(float)x.getAnimatedValue();invalidate();});a.start();}if(listener!=null)listener.onChanged(value);}
    @Override protected void onMeasure(int w,int h){setMeasuredDimension(Ui.dp(getContext(),44),Ui.dp(getContext(),28));}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float d=getResources().getDisplayMetrics().density;float cy=getHeight()/2f;paint.setColor(blend(0xff3a4049,Ui.BLUE,progress));c.drawRoundRect(0,cy-10*d,getWidth(),cy+10*d,10*d,10*d,paint);paint.setShadowLayer(2*d,0,d,0x66000000);setLayerType(LAYER_TYPE_SOFTWARE,paint);paint.setColor(0xfff8f9fa);float x=12*d+progress*(getWidth()-24*d);c.drawCircle(x,cy,9*d,paint);paint.clearShadowLayer();}
    private static int blend(int a,int b,float t){int ar=(a>>16)&255,ag=(a>>8)&255,ab=a&255,br=(b>>16)&255,bg=(b>>8)&255,bb=b&255;return 0xff000000|((int)(ar+(br-ar)*t)<<16)|((int)(ag+(bg-ag)*t)<<8)|(int)(ab+(bb-ab)*t);}
}
