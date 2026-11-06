package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.app.Dialog;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One visual language for menus, confirms, inputs and information sheets. */
public final class Sheets {
    private Sheets(){}
    public static final class Item {
        final int icon,color; final String title,subtitle; final boolean danger; final Runnable action;
        public java.util.function.BiConsumer<TextView,TextView> bind;
        int avatar;boolean checked;
        /** Draws a coloured initial avatar instead of the icon tile. */
        public Item avatar(int color){avatar=color;return this;}
        /** Marks the row as the current choice with a check. */
        public Item checked(boolean on){checked=on;return this;}
        /** Gets the row's right-hand chip and subtitle views so callers can update them in place. */
        public Item bind(java.util.function.BiConsumer<TextView,TextView> b){bind=b;return this;}
        private Item(int icon,int color,String title,String subtitle,boolean danger,Runnable action){this.icon=icon;this.color=Ui.iconColor(color);this.title=title;this.subtitle=subtitle;this.danger=danger;this.action=action;}
        public static Item of(int icon,int color,String title,String subtitle,Runnable action){return new Item(icon,color,title,subtitle,false,action);}
        public static Item danger(int icon,String title,String subtitle,Runnable action){return new Item(icon,Ui.RED,title,subtitle,true,action);}
    }
    public static void actions(Activity a,String title,String subtitle,Item... items){Dialog d=dialog(a);LinearLayout box=sheet(a,title,subtitle);if(items.length>5){ScrollView sv=new ScrollView(a);LinearLayout rows=new LinearLayout(a);rows.setOrientation(LinearLayout.VERTICAL);for(Item item:items)rows.addView(actionRow(a,d,item),lp(a,-1,64));sv.addView(rows);box.addView(sv,new LinearLayout.LayoutParams(-1,(int)(a.getResources().getDisplayMetrics().heightPixels*.55f)));}else{for(Item item:items)box.addView(actionRow(a,d,item),lp(a,-1,64));}present(a,d,box,false);}
    public static void confirm(Activity a,String title,String message,String confirmText,boolean danger,Runnable onConfirm){confirm(a,title,message,confirmText,danger,onConfirm,null);}
    public static void confirm(Activity a,String title,String message,String confirmText,boolean danger,Runnable onConfirm,Runnable onCancel){confirm(a,title,message,confirmText,"Cancel",danger,onConfirm,onCancel);}
    public static void confirm(Activity a,String title,String message,String confirmText,String cancelText,boolean danger,Runnable onConfirm){confirm(a,title,message,confirmText,cancelText,danger,onConfirm,null);}
    private static void confirm(Activity a,String title,String message,String confirmText,String cancelText,boolean danger,Runnable onConfirm,Runnable onCancel){Dialog d=dialog(a);boolean longText=message!=null&&message.length()>160;LinearLayout box=sheet(a,title,longText?null:message);if(longText){ScrollView ms=new ScrollView(a){@Override protected void onMeasure(int w,int h){super.onMeasure(w,View.MeasureSpec.makeMeasureSpec((int)(a.getResources().getDisplayMetrics().heightPixels*.4f),View.MeasureSpec.AT_MOST));}};TextView mt=Ui.body(a,message,Ui.SUB);mt.setPadding(dp(a,18),dp(a,4),dp(a,18),dp(a,8));ms.addView(mt);box.addView(ms,lp(a,-1,-2));}LinearLayout buttons=new LinearLayout(a);buttons.setGravity(Gravity.END);buttons.setPadding(dp(a,12),dp(a,8),dp(a,12),dp(a,12));boolean[] accepted={false};if(onCancel!=null)d.setOnDismissListener(x->{if(!accepted[0])onCancel.run();});TextView cancel=button(a,cancelText,Ui.SUB,false);cancel.setContentDescription(cancelText);cancel.setOnClickListener(v->d.dismiss());buttons.addView(cancel,lp(a,-2,48));TextView yes=button(a,confirmText,danger?Ui.RED:Ui.BLUE,true);yes.setContentDescription(confirmText);yes.setOnClickListener(v->{accepted[0]=true;d.dismiss();onConfirm.run();});buttons.addView(yes,gapStart(a));box.addView(buttons,lp(a,-1,-2));present(a,d,box,false);}
    public static void input(Activity a,String title,String subtitle,String initial,String hint,Consumer<String> done){input(a,title,subtitle,initial,hint,done,"Save");}
    public static void input(Activity a,String title,String subtitle,String initial,String hint,Consumer<String> done,String action){input(a,title,subtitle,initial,hint,done,action,false);}
    public static void input(Activity a,String title,String subtitle,String initial,String hint,Consumer<String> done,String action,boolean allowEmpty){Dialog d=dialog(a);LinearLayout box=sheet(a,title,subtitle);EditText input=new EditText(a);input.setText(initial);String start=input.getText().toString();d.setOnCancelListener(x->{if(!start.equals(input.getText().toString()))a.getWindow().getDecorView().post(()->confirm(a,"Discard changes?","Your edits have not been saved.","Discard",true,()->{},d::show));});input.setSelection(input.length());input.setHint(hint);input.setTextColor(Ui.TEXT);input.setHintTextColor(Ui.FAINT);input.setTextSize(16);input.setSingleLine(true);input.setPadding(dp(a,14),0,dp(a,14),0);input.setBackground(Ui.stroke(a,Ui.SURFACE,Ui.BLUE,10));LinearLayout.LayoutParams ip=lp(a,-1,54);ip.setMargins(dp(a,16),dp(a,8),dp(a,16),dp(a,8));box.addView(input,ip);LinearLayout buttons=new LinearLayout(a);buttons.setGravity(Gravity.END);buttons.setPadding(dp(a,12),0,dp(a,12),dp(a,12));TextView cancel=button(a,"Cancel",Ui.SUB,false);cancel.setOnClickListener(v->d.cancel());buttons.addView(cancel,lp(a,-2,48));TextView save=button(a,action,Ui.BLUE,true);save.setOnClickListener(v->{String value=input.getText().toString().trim();if(allowEmpty||!value.isEmpty()){d.dismiss();done.accept(value);}});buttons.addView(save,gapStart(a));box.addView(buttons,lp(a,-1,-2));present(a,d,box,false);input.postDelayed(()->{input.requestFocus();((InputMethodManager)a.getSystemService(Activity.INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);},180);}
    /** Caller supplies labelled fields and returns false to keep the sheet open after validation. */
    public static Dialog form(Activity a,String title,LinearLayout fields,String action,BooleanSupplier onSubmit){Dialog d=dialog(a);LinearLayout box=sheet(a,title,null);ScrollView scroll=new ScrollView(a){@Override protected void onMeasure(int width,int height){int cap=(int)(a.getResources().getDisplayMetrics().heightPixels*.55f);if(View.MeasureSpec.getMode(height)!=View.MeasureSpec.UNSPECIFIED)cap=Math.min(cap,View.MeasureSpec.getSize(height)-dp(a,120));super.onMeasure(width,View.MeasureSpec.makeMeasureSpec(Math.max(0,cap),View.MeasureSpec.AT_MOST));}};scroll.addView(fields);box.addView(scroll,lp(a,-1,-2));LinearLayout buttons=new LinearLayout(a);buttons.setGravity(Gravity.END);buttons.setPadding(dp(a,12),0,dp(a,12),dp(a,12));TextView cancel=button(a,"Cancel",Ui.SUB,false);cancel.setContentDescription("Cancel");cancel.setOnClickListener(v->d.cancel());buttons.addView(cancel,lp(a,-2,48));TextView save=button(a,action,Ui.BLUE,true);save.setContentDescription(action);save.setOnClickListener(v->{if(onSubmit.getAsBoolean())d.dismiss();});buttons.addView(save,gapStart(a));Window w=d.getWindow();if(w!=null)w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);box.addView(buttons,lp(a,-1,-2));present(a,d,box,false);EditText first=firstEmpty(fields);if(first!=null)first.postDelayed(()->{if(!d.isShowing())return;first.requestFocus();((InputMethodManager)a.getSystemService(Activity.INPUT_METHOD_SERVICE)).showSoftInput(first,InputMethodManager.SHOW_IMPLICIT);},180);return d;}
    /** First empty text field in a form (a new item); editing an existing one leaves the keyboard down. */
    private static EditText firstEmpty(android.view.ViewGroup g){for(int i=0;i<g.getChildCount();i++){View v=g.getChildAt(i);if(v instanceof EditText&&((EditText)v).length()==0)return (EditText)v;if(v instanceof android.view.ViewGroup){EditText e=firstEmpty((android.view.ViewGroup)v);if(e!=null)return e;}}return null;}
    public static void info(Activity a,String title,String subtitle,String[][] rows){info(a,title,subtitle,rows,null);}
    /** {@code top}: an optional view (charts, a banner) shown above the rows. */
    public static void info(Activity a,String title,String subtitle,String[][] rows,View top){Dialog d=dialog(a);LinearLayout box=sheet(a,title,subtitle);LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);ScrollView sv=new ScrollView(a);sv.addView(body);box.addView(sv,new LinearLayout.LayoutParams(-1,-2,1));if(top!=null)body.addView(top,lp(a,-1,-2));for(String[] row:rows){LinearLayout r=new LinearLayout(a);r.setOrientation(LinearLayout.VERTICAL);r.setPadding(dp(a,18),dp(a,10),dp(a,18),dp(a,10));TextView label=Ui.label(a,row[0],Ui.FAINT);r.addView(label);TextView value=Ui.body(a,row[1],Ui.TEXT);value.setTextIsSelectable(true);value.setPadding(0,dp(a,3),0,0);r.addView(value);body.addView(r,lp(a,-1,-2));}TextView done=button(a,"Done",Ui.BLUE,true);done.setGravity(Gravity.CENTER);done.setOnClickListener(v->d.dismiss());LinearLayout.LayoutParams p=lp(a,-1,50);p.setMargins(dp(a,12),dp(a,6),dp(a,12),dp(a,12));box.addView(done,p);present(a,d,box,false);}
    public static void list(Activity a,String title,String subtitle,List<String> items){Dialog d=dialog(a);LinearLayout box=sheet(a,title,subtitle);ScrollView scroll=new ScrollView(a);LinearLayout list=new LinearLayout(a);list.setOrientation(LinearLayout.VERTICAL);for(String raw:items){String[] bits=raw.split("\\|",2);LinearLayout row=new LinearLayout(a);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(a,18),dp(a,10),dp(a,18),dp(a,10));row.setBackground(Ui.ripple(a,Color.TRANSPARENT,0));row.addView(Ui.body(a,bits[0],Ui.TEXT));if(bits.length>1)row.addView(Ui.caption(a,bits[1],Ui.SUB));list.addView(row,lp(a,-1,-2));}scroll.addView(list);box.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));present(a,d,box,true);}

    /** Long, asynchronously populated content with optional actions below the scroll area. */
    public static Dialog tall(Activity a,String title,String subtitle,View content,Item... actions){Dialog d=dialog(a);LinearLayout box=sheet(a,title,subtitle);box.addView(content,new LinearLayout.LayoutParams(-1,0,1));for(Item item:actions)box.addView(actionRow(a,d,item),lp(a,-1,64));present(a,d,box,true);return d;}

    /**
     * A grid of big tiles on top and optional list rows below: the composer's "+" menu, where the
     * common actions (camera, photos, files, voice) must be one tap and easy to hit.
     */
    public static void grid(Activity a, String title, Item[] tiles, Item... rows) {
        Dialog d = dialog(a);
        LinearLayout box = sheet(a, title, null);
        android.widget.GridLayout grid = new android.widget.GridLayout(a);
        grid.setColumnCount(4);
        grid.setPadding(dp(a, 8), 0, dp(a, 8), dp(a, 6));
        int cell = (a.getResources().getDisplayMetrics().widthPixels - dp(a, 32)) / 4;
        for (Item item : tiles) {
            LinearLayout t = new LinearLayout(a);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(a, 8), 0, dp(a, 8));
            t.setBackground(Ui.ripple(a, Color.TRANSPARENT, 16));
            ImageView icon = Ui.iconTile(a, item.icon, item.color);
            icon.setPadding(dp(a, 15), dp(a, 15), dp(a, 15), dp(a, 15));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            t.addView(icon, lp(a, 54, 54));
            TextView label = Ui.caption(a, item.title, Ui.SUB);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(a, 6), 0, 0);
            label.setMaxLines(1);
            t.addView(label, new LinearLayout.LayoutParams(-2, -2));
            t.setContentDescription(item.title);
            t.setOnClickListener(v -> { d.dismiss(); if (item.action != null) item.action.run(); });
            grid.addView(t, new android.widget.GridLayout.LayoutParams(new android.view.ViewGroup.LayoutParams(cell, -2)));
        }
        box.addView(grid, lp(a, -1, -2));
        if (rows.length > 0) {
            View line = new View(a); line.setBackgroundColor(Ui.LINE);
            LinearLayout.LayoutParams lpl = lp(a, -1, 1); lpl.setMargins(dp(a, 18), dp(a, 4), dp(a, 18), dp(a, 4));
            box.addView(line, lpl);
            for (Item item : rows) {
                if (item.danger) { View gap = new View(a); gap.setBackgroundColor(Ui.LINE); LinearLayout.LayoutParams g = lp(a, -1, 1); g.setMargins(dp(a, 18), dp(a, 6), dp(a, 18), dp(a, 4)); box.addView(gap, g); }
                box.addView(actionRow(a, d, item), lp(a, -1, 60));
            }
        }
        if (tiles.length > 8 || rows.length > 4) {
            ScrollView scroll = new ScrollView(a);
            scroll.addView(box);
            present(a, d, scroll, true);
        } else present(a, d, box, false);
    }

    /** Shared phone action mode picker for Settings and the permission centre. */
    public static void actionMode(Activity activity, SettingsStore store, Runnable changed) {
        picker(activity, "Action mode", "How Hermes handles phone actions",
                java.util.Arrays.asList(new Choice("Ask", "Confirm sensitive actions", "Ask".equals(store.actionMode())),
                        new Choice("Full access", "Run phone actions without asking", "Full access".equals(store.actionMode()))), choice -> {
                    if (choice.title.equals(store.actionMode())) return;
                    if ("Full access".equals(choice.title)) {
                        Confirm.strong(activity, "Enable Full access",
                                "Hermes will call, message, tap and type without asking. Anything it reads — web pages, notifications, screen text — could steer it.",
                                () -> { store.actionMode("Full access"); changed.run(); }, () -> {});
                    } else { store.actionMode("Ask"); changed.run(); }
                });
    }

    /** Settings › Appearance › Layout: navigation structures; picking one starts over at its home. */
    public static void layouts(Activity a, Layout current, Consumer<Layout> onPick) {
        int[] icons = {R.drawable.ic_menu, R.drawable.ic_session, R.drawable.ic_home, R.drawable.ic_search, R.drawable.ic_mic};
        Item[] items = new Item[Layout.ALL.length];
        for (int i = 0; i < items.length; i++) {
            Layout l = Layout.ALL[i];
            items[i] = Item.of(icons[i], l == current ? Ui.BLUE : Ui.SUB, l.name + (l == current ? "  ✓" : ""), l.blurb, () -> { if (l != current) onPick.accept(l); });
        }
        actions(a, "Layout", "How the app is organised; independent of the theme", items);
    }

    /** Settings › Appearance › Style: the four concepts, each with a preview (background, card, accent, "Aa 42%" in its fonts). */
    public static void concepts(Activity a, Concept current, Consumer<Concept> onPick) {
        Dialog d = dialog(a);
        LinearLayout box = sheet(a, "Theme", "Console or Refined styles, dark and light colour themes");
        ScrollView scroll = new ScrollView(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        java.util.List<Concept> all = new java.util.ArrayList<>(java.util.Arrays.asList(Concept.ALL));
        all.add(Concept.custom);
        for (Concept c : all) {
            boolean selected = c.id.equals(current.id);
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(a,18),dp(a,10),dp(a,16),dp(a,10));
            row.setBackground(Ui.ripple(a,Color.TRANSPARENT,0));
            View preview = Ui.stylePreview(a, c);
            preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(preview, lp(a, 76, 56));
            LinearLayout copy = new LinearLayout(a);
            copy.setOrientation(LinearLayout.VERTICAL);
            copy.setPadding(dp(a,14),0,dp(a,6),0);
            copy.addView(Ui.body(a, c.name, selected ? Ui.BLUE : Ui.TEXT));
            copy.addView(Ui.caption(a, c.blurb, Ui.SUB));
            row.addView(copy, new LinearLayout.LayoutParams(0,-2,1));
            if (selected) { TextView check = text(a, "✓", 16, Ui.BLUE, true); check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); row.addView(check); }
            row.setContentDescription(c.name + (selected ? ", selected" : "") + ", " + c.blurb);
            row.setOnClickListener(v -> { d.dismiss(); if (!selected) onPick.accept(c); });
            list.addView(row, lp(a, -1, -2));
        }
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, (int) (a.getResources().getDisplayMetrics().heightPixels * .6f)));
        present(a, d, box, false);
    }

    /** Settings › Appearance › Accent colour: a swatch per colour that overrides the theme's own accent. */
    public static void accents(Activity a, int current, Consumer<Integer> onPick) {
        Dialog d = dialog(a);
        LinearLayout box = sheet(a, "Accent colour", "Buttons, links and your messages; every theme keeps its own backgrounds");
        ScrollView scroll = new ScrollView(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < Accent.COLORS.length; i++) {
            final int color = Accent.COLORS[i]; final String name = Accent.NAMES[i];
            boolean selected = color == current;
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(a, 18), dp(a, 8), dp(a, 16), dp(a, 8));
            row.setBackground(Ui.ripple(a, Color.TRANSPARENT, 0));
            View dot = new View(a);
            dot.setBackground(Ui.circle(color == 0 ? Ui.concept.blue : Accent.readableOn(color, Ui.BG, 4.5)));
            dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(dot, lp(a, 28, 28));
            TextView label = Ui.body(a, name, selected ? Ui.BLUE : Ui.TEXT);
            label.setPadding(dp(a, 16), 0, 0, 0);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            if (selected) { TextView check = text(a, "✓", 16, Ui.BLUE, true); check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); row.addView(check); }
            row.setContentDescription(name + (selected ? ", selected" : ""));
            row.setOnClickListener(v -> { d.dismiss(); if (!selected) onPick.accept(color); });
            list.addView(row, lp(a, -1, 52));
        }
        boolean custom = current != 0 && java.util.Arrays.stream(Accent.COLORS).noneMatch(c -> c == current);
        TextView own = Ui.body(a, custom ? String.format("Custom · #%06x", current & 0xffffff) : "Custom colour…", custom ? Ui.BLUE : Ui.TEXT);
        own.setPadding(dp(a, 62), 0, dp(a, 16), 0); own.setGravity(Gravity.CENTER_VERTICAL);
        own.setBackground(Ui.ripple(a, Color.TRANSPARENT, 0));
        own.setOnClickListener(v -> { d.dismiss(); input(a, "Custom accent", "Hex colour, like 4f8ff7. It is adjusted to stay readable on your theme.",
                custom ? String.format("%06x", current & 0xffffff) : "", "4f8ff7", value -> {
                    Integer c = Accent.hex(value);
                    if (c == null) android.widget.Toast.makeText(a, "Use 6 hex digits, like 4f8ff7", android.widget.Toast.LENGTH_SHORT).show();
                    else onPick.accept(c);
                }, "Use"); });
        list.addView(own, lp(a, -1, 52));
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, (int) (a.getResources().getDisplayMetrics().heightPixels * .6f)));
        present(a, d, box, false);
    }

    /** Settings › Voice › Wake phrase: say it TRAIN_TIMES times; the count moves each time it is heard. Done stops early; Cancel discards. */
    public static void wakeTraining(Activity a, String label, String tokens, Consumer<int[]> done) {
        Dialog d = dialog(a);
        LinearLayout box = sheet(a, "Say \u201c" + label + "\u201d", "Say it " + WakePhrase.TRAIN_TIMES
                + " times in your normal voice, with a short pause each time. The count goes up when it's heard.");
        TextView count = text(a, "0 / " + WakePhrase.TRAIN_TIMES, 40, Ui.TEXT, true);
        count.setGravity(Gravity.CENTER); count.setPadding(0, dp(a, 12), 0, dp(a, 4));
        box.addView(count, lp(a, -1, -2));
        TextView status = Ui.caption(a, "Listening\u2026", Ui.SUB);
        status.setGravity(Gravity.CENTER); status.setPadding(dp(a, 16), 0, dp(a, 16), dp(a, 12));
        box.addView(status, lp(a, -1, -2));
        WakeTraining run = new WakeTraining();
        boolean[] abort = {false};
        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.END); buttons.setPadding(dp(a, 12), 0, dp(a, 12), dp(a, 12));
        TextView cancel = button(a, "Cancel", Ui.SUB, false);
        cancel.setOnClickListener(v -> d.cancel());
        buttons.addView(cancel, lp(a, -2, 48));
        TextView finish = button(a, "Done", Ui.BLUE, true);
        finish.setOnClickListener(v -> run.cancel());
        buttons.addView(finish, gapStart(a));
        box.addView(buttons, lp(a, -1, -2));
        d.setOnCancelListener(x -> { abort[0] = true; run.cancel(); });
        new Thread(() -> {
            try {
                int[] hits = run.run(a, tokens, n -> a.runOnUiThread(() -> count.setText(n + " / " + WakePhrase.TRAIN_TIMES)));
                a.runOnUiThread(() -> { if (abort[0]) return; if (d.isShowing()) d.dismiss(); done.accept(hits); });
            } catch (Exception e) {
                a.runOnUiThread(() -> status.setText("Couldn't listen: " + e.getMessage()));
            }
        }, "wake-training").start();
        present(a, d, box, false);
    }

    /** Settings › Appearance › Bottom bar tabs: pick 3 to 5 places for the Tabs layout. */
    public static void tabs(Activity a, java.util.List<Layout.Tab> current, Consumer<java.util.List<Layout.Tab>> onSave) {
        Dialog d = dialog(a);
        LinearLayout box = sheet(a, "Bottom bar tabs", "Pick " + Layout.MIN_TABS + " to " + Layout.MAX_TABS + ". They show in this order.");
        java.util.List<Layout.Tab> chosen = new java.util.ArrayList<>(current);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        TextView save = Ui.primaryButton(a, "Save");
        Runnable refresh = () -> {
            list.removeAllViews();
            for (Layout.Tab t : Layout.Tab.values()) {
                boolean on = chosen.contains(t);
                LinearLayout row = new LinearLayout(a);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(a, 18), dp(a, 8), dp(a, 16), dp(a, 8));
                row.setBackground(Ui.ripple(a, Color.TRANSPARENT, 0));
                TextView label = Ui.body(a, t.label, on ? Ui.BLUE : Ui.TEXT);
                row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(text(a, on ? "✓" : "", 16, Ui.BLUE, true));
                row.setContentDescription(t.label + (on ? ", on" : ", off"));
                row.setOnClickListener(v -> {
                    if (on) { if (chosen.size() > Layout.MIN_TABS) chosen.remove(t); }
                    else if (chosen.size() < Layout.MAX_TABS) chosen.add(t);
                    ((Runnable) list.getTag()).run();
                });
                list.addView(row, lp(a, -1, 52));
            }
            save.setEnabled(chosen.size() >= Layout.MIN_TABS && chosen.size() <= Layout.MAX_TABS);
        };
        list.setTag(refresh);
        refresh.run();
        ScrollView scroll = new ScrollView(a);
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        save.setOnClickListener(v -> { d.dismiss(); onSave.accept(new java.util.ArrayList<>(chosen)); });
        LinearLayout.LayoutParams sp = lp(a, -1, 50);
        sp.setMargins(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 14));
        box.addView(save, sp);
        present(a, d, box, true);
    }

    /** Server pack: tick the modules Hermes should install. Presets on top, skills expandable, the hub always on. */
    public static void kitModules(Activity a, KitModules kit, java.util.Set<String> initial, String host, boolean update, Consumer<java.util.Set<String>> onDone) {
        Dialog d = dialog(a);
        LinearLayout box = sheet(a, update ? "Update server pack" : "Set up server pack", "Choose what Hermes installs on " + host + ". You can run this again to add or change modules.");
        final java.util.Set<String> chosen = new java.util.LinkedHashSet<>(kit.normalize(initial));
        final boolean[] skillsOpen = {false};
        LinearLayout presetRow = new LinearLayout(a);
        presetRow.setPadding(dp(a, 14), dp(a, 4), dp(a, 14), dp(a, 8));
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        TextView go = Ui.primaryButton(a, "Install");
        Runnable[] render = new Runnable[1];
        for (java.util.Map.Entry<String, java.util.List<String>> e : kit.presets.entrySet()) {
            TextView chip = Ui.pillButton(a, kit.presetTitles.get(e.getKey()), 0, Ui.RAISED, Ui.LINE, Ui.TEXT);
            chip.setPadding(dp(a, 12), dp(a, 7), dp(a, 12), dp(a, 7));
            chip.setOnClickListener(v -> { chosen.clear(); chosen.addAll(kit.preset(e.getKey())); render[0].run(); });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.setMargins(0, 0, dp(a, 8), 0);
            presetRow.addView(chip, cp);
        }
        render[0] = () -> {
            list.removeAllViews();
            String lastGroup = null;
            for (KitModules.Module m : kit.rows()) {
                if (!m.group.equals(lastGroup)) {
                    lastGroup = m.group;
                    TextView h = Ui.label(a, kit.groupTitles.containsKey(m.group) ? kit.groupTitles.get(m.group) : m.group, Ui.FAINT);
                    h.setPadding(dp(a, 18), dp(a, 12), dp(a, 18), dp(a, 2));
                    list.addView(h);
                }
                list.addView(kitRow(a, kit, m, chosen, skillsOpen, render[0]));
                if (m.isGroup() && skillsOpen[0]) for (String child : m.children) list.addView(kitRow(a, kit, kit.byId.get(child), chosen, skillsOpen, render[0]));
            }
            int n = chosen.size() - countSkills(kit, chosen) + (countSkills(kit, chosen) > 0 ? 1 : 0);
            go.setText((update ? "Update " : "Install ") + n + (n == 1 ? " item" : " items"));
        };
        render[0].run();
        ScrollView scroll = new ScrollView(a);
        LinearLayout inner = new LinearLayout(a);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.addView(presetRow);
        inner.addView(list);
        scroll.addView(inner);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        go.setOnClickListener(v -> { d.dismiss(); onDone.accept(new java.util.LinkedHashSet<>(chosen)); });
        LinearLayout.LayoutParams gp = lp(a, -1, 50);
        gp.setMargins(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 14));
        box.addView(go, gp);
        present(a, d, box, true);
    }

    private static int countSkills(KitModules kit, java.util.Set<String> chosen) {
        int n = 0;
        for (String id : chosen) if (kit.byId.get(id) != null && kit.byId.get(id).isSkill()) n++;
        return n;
    }

    private static View kitRow(Activity a, KitModules kit, KitModules.Module m, java.util.Set<String> chosen, boolean[] skillsOpen, Runnable refresh) {
        boolean child = m.isSkill();
        int childCount = m.isGroup() ? m.children.size() : 0, have = 0;
        if (m.isGroup()) for (String c : m.children) if (chosen.contains(c)) have++;
        boolean on = m.isGroup() ? have == childCount : chosen.contains(m.id);
        boolean partial = m.isGroup() && have > 0 && have < childCount;
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(a, child ? 38 : 18), dp(a, 9), dp(a, 14), dp(a, 9));
        row.setBackground(Ui.ripple(a, Color.TRANSPARENT, 0));
        TextView box = text(a, on ? "☑" : partial ? "▣" : "☐", 20, m.required ? Ui.FAINT : (on || partial ? Ui.BLUE : Ui.SUB), false);
        box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(box, lp(a, 30, -2));
        LinearLayout copy = new LinearLayout(a);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(a, 8), 0, dp(a, 6), 0);
        String title = m.name + (m.required ? "  ·  always" : m.experimental ? "  ·  experimental" : m.isGroup() ? "  ·  " + have + " of " + childCount : "");
        copy.addView(Ui.body(a, title, m.required ? Ui.SUB : Ui.TEXT));
        copy.addView(Ui.caption(a, m.summary, Ui.SUB));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        if (m.isGroup()) {
            TextView more = text(a, skillsOpen[0] ? "▾" : "▸", 18, Ui.SUB, false);
            more.setGravity(Gravity.CENTER);
            more.setContentDescription(skillsOpen[0] ? "Hide the individual skills" : "Choose individual skills");
            more.setOnClickListener(v -> { skillsOpen[0] = !skillsOpen[0]; refresh.run(); });
            row.addView(more, lp(a, 40, 40));
        }
        row.setContentDescription(m.name + (on ? ", selected" : partial ? ", partly selected" : ", not selected") + ". " + m.summary);
        if (!m.required) row.setOnClickListener(v -> {
            if (m.isGroup()) { if (on) chosen.removeAll(m.children); else chosen.addAll(m.children); }
            else if (chosen.contains(m.id)) chosen.remove(m.id); else chosen.add(m.id);
            java.util.Set<String> fixed = kit.normalize(chosen);
            chosen.clear(); chosen.addAll(fixed);
            refresh.run();
        });
        return row;
    }

    /** A row in a picker sheet. */
    public static final class Choice {
        public final String title, subtitle;
        public final boolean selected;
        public Choice(String title, String subtitle, boolean selected) {
            this.title = title; this.subtitle = subtitle; this.selected = selected;
        }
    }

    /**
     * Searchable single-choice sheet.
     *
     * The model catalog runs to hundreds of entries across every provider, so this filters as he
     * types instead of making him scroll a wall of ids to find one.
     */
    public static void picker(Activity a, String title, String subtitle,
                              final List<Choice> choices, final Consumer<Choice> onPick) {
        Dialog d = dialog(a);
        LinearLayout box = sheet(a, title, subtitle);
        final EditText search = new EditText(a);
        search.setHint("Search");
        search.setTextColor(Ui.TEXT); search.setHintTextColor(Ui.FAINT); search.setTextSize(14);
        search.setSingleLine(true); search.setPadding(dp(a,14),0,dp(a,14),0);
        search.setBackground(Ui.stroke(a,Ui.RAISED,Ui.LINE,10));
        LinearLayout.LayoutParams sp = lp(a,-1,44); sp.setMargins(dp(a,16),0,dp(a,16),dp(a,6));
        // A short list fits on screen: no search box, and the sheet hugs its rows instead of standing 78% tall.
        final boolean big = choices.size() > 8;
        if (big) box.addView(search, sp);
        final ScrollView scroll = new ScrollView(a);
        final LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0,0,0,dp(a,14));
        scroll.addView(rows);
        // Rows are built once; typing only toggles visibility (long model lists lagged when rebuilt per key).
        final java.util.List<View> built = new java.util.ArrayList<>();
        final java.util.List<String> keys = new java.util.ArrayList<>();
        for (final Choice c : choices) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(a,18),dp(a,9),dp(a,16),dp(a,9));
            row.setBackground(Ui.ripple(a,Color.TRANSPARENT,0));
            LinearLayout copy = new LinearLayout(a);
            copy.setOrientation(LinearLayout.VERTICAL);
            copy.addView(Ui.body(a, c.title, c.selected ? Ui.BLUE : Ui.TEXT));
            if (c.subtitle != null && !c.subtitle.isEmpty()) copy.addView(Ui.caption(a, c.subtitle, Ui.SUB));
            row.addView(copy, new LinearLayout.LayoutParams(0,-2,1));
            if (c.selected) { TextView check = text(a, "✓", 16, Ui.BLUE, true); check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); row.addView(check); }
            row.setContentDescription(c.title + (c.selected ? ", selected" : "") + (c.subtitle == null ? "" : ", " + c.subtitle));
            row.setOnClickListener(v -> { d.dismiss(); if (onPick != null) onPick.accept(c); });
            rows.addView(row, lp(a, -1, -2));
            built.add(row);
            keys.add((c.title + "\n" + (c.subtitle == null ? "" : c.subtitle)).toLowerCase(java.util.Locale.ROOT));
        }
        final TextView none = Ui.body(a, "No matches", Ui.SUB);
        none.setPadding(dp(a,18),dp(a,14),dp(a,18),dp(a,14));
        rows.addView(none);
        final Runnable render = () -> {
            String q = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            int shown = 0;
            for (int i = 0; i < built.size(); i++) {
                boolean on = q.isEmpty() || keys.get(i).contains(q);
                built.get(i).setVisibility(on ? View.VISIBLE : View.GONE);
                if (on) shown++;
            }
            none.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int cnt, int aft) {}
            public void onTextChanged(CharSequence s, int st, int bef, int cnt) { search.removeCallbacks(render); search.postDelayed(render, 90); }
            public void afterTextChanged(android.text.Editable e) {}
        });
        render.run();
        box.addView(scroll, new LinearLayout.LayoutParams(-1,
                big ? (int)(a.getResources().getDisplayMetrics().heightPixels*.55f) : -2));
        present(a, d, box, big);
    }


    private static View actionRow(Activity a,Dialog d,Item item){LinearLayout row=new LinearLayout(a);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(a,16),dp(a,4),dp(a,14),dp(a,4));row.setMinimumHeight(dp(a,56));row.setBackground(Ui.ripple(a,Color.TRANSPARENT,0));row.addView(item.avatar!=0?Ui.avatar(a,item.title,item.avatar,40):Ui.iconTile(a,item.icon,item.color),lp(a,40,40));LinearLayout copy=new LinearLayout(a);copy.setOrientation(LinearLayout.VERTICAL);copy.setPadding(dp(a,14),0,0,0);copy.addView(Ui.body(a,item.title,item.danger?Ui.RED:Ui.TEXT));TextView sub=null;if(item.bind!=null||item.subtitle!=null&&!item.subtitle.isEmpty()){sub=Ui.caption(a,item.subtitle==null?"":item.subtitle,Ui.SUB);copy.addView(sub);}row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));if(item.bind!=null){TextView chip=Ui.caption(a,"",Ui.SUB);chip.setTypeface(Ui.WORD_SEMI);chip.setPadding(dp(a,8),dp(a,3),dp(a,8),dp(a,3));row.addView(chip,new LinearLayout.LayoutParams(-2,-2));item.bind.accept(chip,sub);}if(item.checked){TextView ck=Ui.title(a,"\u2713",Ui.BLUE);ck.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);ck.setPadding(dp(a,10),0,0,0);row.addView(ck);row.setContentDescription(item.title+", current");}row.setOnClickListener(v->{d.dismiss();if(item.action!=null)item.action.run();});return row;}
    private static LinearLayout sheet(Activity a,String title,String subtitle){LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);box.setBackground(Ui.card(a,3));Ui.elevate(box,3);box.setPadding(0,0,0,dp(a,8));View handle=new View(a);handle.setBackground(Ui.round(a,Ui.FAINT,99));LinearLayout.LayoutParams hp=lp(a,36,4);hp.gravity=Gravity.CENTER_HORIZONTAL;hp.setMargins(0,dp(a,8),0,dp(a,10));box.addView(handle,hp);TextView h=Ui.title(a,title,Ui.TEXT);h.setMaxLines(2);h.setEllipsize(android.text.TextUtils.TruncateAt.END);h.setPadding(dp(a,18),0,dp(a,18),0);box.addView(h);if(subtitle!=null&&!subtitle.isEmpty()){TextView s=Ui.body(a,subtitle,Ui.SUB);s.setLineSpacing(0,1.15f);s.setPadding(dp(a,18),dp(a,4),dp(a,18),dp(a,12));box.addView(s);}else{View gap=new View(a);box.addView(gap,lp(a,1,8));}return box;}
    private static Dialog dialog(Activity a){Dialog d=new Dialog(a);d.requestWindowFeature(Window.FEATURE_NO_TITLE);d.setCanceledOnTouchOutside(true);return d;}
    private static void present(Activity a,Dialog d,View content,boolean tall){if(a.isFinishing()||a.isDestroyed())return;FrameLayout frame=new FrameLayout(a);frame.setPadding(dp(a,8),dp(a,8),dp(a,8),dp(a,8));frame.addView(content,new FrameLayout.LayoutParams(-1,tall?(int)(a.getResources().getDisplayMetrics().heightPixels*.78f):-2,Gravity.BOTTOM));d.setContentView(frame);Window w=d.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.setGravity(Gravity.BOTTOM);w.setWindowAnimations(R.style.SheetMotion);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);WindowManager.LayoutParams attrs=w.getAttributes();attrs.width=-1;attrs.height=-2;attrs.dimAmount=Ui.motionEnabled()?0f:.62f;w.setAttributes(attrs);}d.show();if(w!=null&&Ui.motionEnabled()){ValueAnimator dim=ValueAnimator.ofFloat(0f,.62f);dim.setDuration(200);dim.addUpdateListener(v->{if(!d.isShowing()){dim.cancel();return;}WindowManager.LayoutParams attrs=w.getAttributes();attrs.dimAmount=(float)v.getAnimatedValue();w.setAttributes(attrs);});dim.start();}}
    private static TextView button(Activity a,String s,int color,boolean bold){return bold?Ui.primaryButton(a,s,color):Ui.ghostButton(a,s,color);}
    private static TextView text(Activity a,String s,float z,int color,boolean bold){return Ui.text(a,s,z,color,bold);}
    private static LinearLayout.LayoutParams gapStart(Activity a){LinearLayout.LayoutParams p=lp(a,-2,48);p.setMarginStart(dp(a,8));return p;}
    private static LinearLayout.LayoutParams lp(Activity a,int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(a,w),h<0?h:dp(a,h));}
    private static int dp(Activity a,int n){return Ui.dp(a,n);} private static int light(int c){int r=(c>>16)&255,g=(c>>8)&255,b=c&255;r=Math.min(255,r+28);g=Math.min(255,g+28);b=Math.min(255,b+28);return 0xff000000|(r<<16)|(g<<8)|b;}
}
