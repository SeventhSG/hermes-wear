package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Launcher/router: the companion opens straight into the last Hermes conversation. */
public final class MainActivity extends Activity {
    public static final String EXTRA_START_VOICE="start_voice";
    /** Set to open the Hermes chat lane instead of the capture lane (used by EssentialsActivity). */
    public static final String EXTRA_OPEN_CHAT="open_chat";
    /** Open the chosen layout's home screen (Settings › Layout, the Back fallback of screens opened directly). */
    public static final String EXTRA_OPEN_HOME="open_home";
    /** Open this exact conversation (from a notification or shortcut). */
    public static final String EXTRA_SESSION="open_session";
    /** Set by a profile switch: show the switch overlay in place of the splash and hand it on to the chat. */
    public static final String EXTRA_SWITCHING="switching";
    public static final String EXTRA_PROFILE="open_profile", EXTRA_PROFILE_SECRET="open_profile_secret", EXTRA_HARNESS="open_harness";
    /** Launcher shortcut actions (declared in res/xml/shortcuts.xml). */
    public static final String ACTION_VOICE="cloud.stormlabs.hermescompanion.VOICE";
    public static final String ACTION_NEW_CHAT="cloud.stormlabs.hermescompanion.NEW_CHAT";
    private final ExecutorService network=Executors.newSingleThreadExecutor(); private SettingsStore store; private TextView status;
    @Override public void onCreate(Bundle b){super.onCreate(b);CrashReporter.install(this);try{Ui.legacyWindowInsets(this);getWindow().setStatusBarColor(Ui.BG);getWindow().setNavigationBarColor(Ui.BG);store=new SettingsStore(this);if(switchProfileForIntent())return;Notifier.cancelWakeHeard(this);WakeWordService.ensure(this);PushPoller.schedule(this);PushPoller.pollOnce(this);buildSplash();route();}catch(Throwable t){CrashReporter.show(this,t);}}
    /** A notification from another profile: switch, then start over in a fresh task so no open screen keeps the old profile's client. */
    private boolean switchProfileForIntent(){
        Intent in=getIntent();String id=in.getStringExtra(EXTRA_PROFILE);String secret=in.getStringExtra(EXTRA_PROFILE_SECRET);
        in.removeExtra(EXTRA_PROFILE);
        if(id==null||!store.intentSecret().equals(secret)||id.equals(store.profileId()))return switchHarnessForIntent();
        if(!store.switchProfile(id)){in.removeExtra(EXTRA_SESSION);in.removeExtra(JobsActivity.EXTRA_JOB_ID);android.widget.Toast.makeText(this,"That profile was removed",android.widget.Toast.LENGTH_SHORT).show();return false;}
        startActivity(new Intent(in).setClass(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));finish();return true;
    }
    /** Same profile, other harness (a Codex reply tapped while on Hermes): switch like the harness picker, then start over. */
    private boolean switchHarnessForIntent(){
        Intent in=getIntent();String h=in.getStringExtra(EXTRA_HARNESS);in.removeExtra(EXTRA_HARNESS);
        if(h==null||h.equals(store.serverType())||!store.intentSecret().equals(in.getStringExtra(EXTRA_PROFILE_SECRET))||!store.harnessReady(h))return false;
        store.serverType(h);Reach.configure(store);
        startActivity(new Intent(in).setClass(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));finish();return true;
    }
    private void buildSplash(){LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Ui.BG);LinearLayout bar=new LinearLayout(this);bar.setBackgroundColor(Ui.BAR);ImageButton back=Ui.icon(this,R.drawable.ic_back,"Back");back.setOnClickListener(v->onBackPressed());bar.addView(back,new LinearLayout.LayoutParams(Ui.dp(this,52),Ui.dp(this,56)));root.addView(bar);LinearLayout center=new LinearLayout(this);center.setOrientation(LinearLayout.VERTICAL);center.setGravity(Gravity.CENTER);String profile=store.profileName();center.addView(Ui.avatar(this,profile,Ui.BLUE,72),new LinearLayout.LayoutParams(Ui.dp(this,72),Ui.dp(this,72)));TextView name=Ui.text(this,profile,21,Ui.TEXT,true);name.setGravity(Gravity.CENTER);name.setPadding(0,Ui.dp(this,14),0,0);center.addView(name);status=Ui.text(this,"Opening conversation…",13,Ui.SUB,false);status.setPadding(0,Ui.dp(this,6),0,Ui.dp(this,18));center.addView(status);ProgressBar p=new ProgressBar(this);p.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.BLUE));center.addView(p,new LinearLayout.LayoutParams(Ui.dp(this,28),Ui.dp(this,28)));root.addView(center,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);if(getIntent().getBooleanExtra(EXTRA_SWITCHING,false))overlay=SwitchOverlay.still(this,profile,store.profileId());}
    private SwitchOverlay.Overlay overlay;
    /** Capture is the primary action: land there, and reach the chat lane on demand. */
    private void route(){
        if(Onboarding.shouldRun(store.apiKey(),store.onboardingDone())){startActivity(new Intent(this,OnboardingActivity.class));finish();return;}
        android.net.Uri deepLink=getIntent().getData();
        if(deepLink!=null&&"hermes".equals(deepLink.getScheme())){routeDeepLink(deepLink);return;}
        if(getIntent().hasExtra(JobsActivity.EXTRA_JOB_ID)){startActivity(new Intent(this,JobsActivity.class).putExtra(JobsActivity.EXTRA_JOB_ID,getIntent().getStringExtra(JobsActivity.EXTRA_JOB_ID)).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();return;}
        if(ACTION_NEW_CHAT.equals(getIntent().getAction())){newChat();return;}
        boolean voiceShortcut=ACTION_VOICE.equals(getIntent().getAction());
        if(voiceShortcut)getIntent().putExtra(EXTRA_START_VOICE,true);
        if(getIntent().getBooleanExtra(EXTRA_OPEN_CHAT,false)){openChat();return;}
        boolean home=getIntent().getBooleanExtra(EXTRA_OPEN_HOME,false);
        if(!home&&store.essentialsReady()&&store.openCaptureFirst()&&getIntent().getStringExtra(EXTRA_SESSION)==null&&!getIntent().getBooleanExtra(EXTRA_START_VOICE,false)&&getIntent().getStringExtra(ChatActivity.EXTRA_PROMPT)==null){
            startActivity(new Intent(this,EssentialsActivity.class)
                    .putExtra(EssentialsActivity.EXTRA_BASE,store.essentialsBase())
                    .putExtra(EssentialsActivity.EXTRA_TOKEN,store.essentialsToken()));
            finish();return;
        }
        boolean chatWork=getIntent().getStringExtra(EXTRA_SESSION)!=null||getIntent().getBooleanExtra(EXTRA_START_VOICE,false)||getIntent().getStringExtra(ChatActivity.EXTRA_PROMPT)!=null||getIntent().hasExtra(ChatActivity.EXTRA_IMAGES);
        if(chatWork||Ui.layout.home==Layout.Home.CHAT){openChat();return;}
        if(store.apiKey().isEmpty()){startActivity(new Intent(this,ConnectionActivity.class));finish();return;}
        startActivity(Nav.home(this));finish();
    }
    /** hermes://session/<id> opens that chat; hermes://job/<id> opens the jobs screen. */
    private void routeDeepLink(android.net.Uri uri){
        String id=uri.getLastPathSegment();
        if("media".equals(uri.getHost())){startActivity(new Intent(this,MediaActivity.class));finish();return;}
        if("job".equals(uri.getHost())){startActivity(new Intent(this,JobsActivity.class).putExtra(JobsActivity.EXTRA_JOB_ID,id));finish();return;}
        if("session".equals(uri.getHost())&&id!=null&&!id.isEmpty()){getIntent().putExtra(EXTRA_SESSION,id);openChat();return;}
        if("codex".equals(uri.getHost())){openCodex(id);return;}
        openChat();
    }
    /** hermes://codex/<thread> (codex-rc on the server): switch to the Codex profile, pairing it over Tailscale the first time, then open that session. */
    private void openCodex(String thread){
        if(!store.moduleOn(Modules.CODEX)){openChat();return;} // Codex module switched off: open the usual chat
        if("Codex".equals(store.serverType())){if(thread!=null&&!thread.isEmpty())getIntent().putExtra(EXTRA_SESSION,thread);openChat();return;}
        network.submit(()->{
            String id=null;for(SettingsStore.Profile p:store.profiles())if("Codex".equals(p.type)){id=p.id;break;}
            String err=id!=null?(store.switchProfile(id)?null:"Couldn't switch to the Codex profile"):Pairing.addCodex(store);
            runOnUiThread(()->{
                if(err!=null){android.widget.Toast.makeText(this,err,android.widget.Toast.LENGTH_LONG).show();openChat();return;}
                Intent i=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
                if(thread!=null&&!thread.isEmpty())i.putExtra(EXTRA_SESSION,thread);
                startActivity(i);finish();
            });
        });
    }
    static HermesApi.SessionInfo findAppChat(AgentBackend api, String wanted) throws Exception {
        final int pageSize = 200;
        // Bound startup work; reaching the limit must fail rather than create a duplicate.
        for (int offset = 0; offset < 10000; offset += pageSize) {
            List<HermesApi.SessionInfo> page = api.listSessions(pageSize, offset);
            for (HermesApi.SessionInfo session : page) if (session.id.equals(wanted)) return session;
            for (HermesApi.SessionInfo session : page) if (isAppChat(session)) return session;
            if (!HermesApi.hasMoreSessions(page, pageSize)) return null;
        }
        throw new Exception("Could not find an app conversation among 10,000 sessions. Open one from Conversations, or start a new chat.");
    }

    private void openChat(){if(store.apiKey().isEmpty()){startActivity(new Intent(this,ConnectionActivity.class));finish();return;}String direct=getIntent().getStringExtra(EXTRA_SESSION);if(direct!=null&&!direct.isEmpty()){store.sessionId(direct);Intent i=Nav.chat(this,direct,getIntent().getStringExtra(ChatActivity.EXTRA_TITLE),null);forwardChatExtras(i);Nav.startChat(this,i);finish();overridePendingTransition(0,0);return;}String last=store.sessionId();if(!store.sessionTitle(last).isEmpty()){/* fast path: open the last conversation now; ChatActivity handles one that was deleted */Intent i=Nav.chat(this,last,store.sessionTitle(last),null);forwardChatExtras(i);Nav.startChat(this,i);finish();overridePendingTransition(0,0);return;}AgentBackend api=Backends.create(store);network.submit(()->{try{HermesApi.SessionInfo chosen=findAppChat(api,store.sessionId());if(chosen==null)chosen=api.createSession("Mobile");HermesApi.SessionInfo target=chosen;runOnUiThread(()->open(target));}catch(Exception e){runOnUiThread(()->showFailure(e,this::openChat));}});}
    /** "New conversation" shortcut: always creates a fresh session rather than reopening the last one. */
    private void newChat(){
        if(store.apiKey().isEmpty()){startActivity(new Intent(this,ConnectionActivity.class).putExtra(ConnectionActivity.EXTRA_OPEN_NEW_CHAT,true));finish();return;}
        AgentBackend api=Backends.create(store);
        network.submit(()->{
            try{HermesApi.SessionInfo s=api.createSession("New conversation");runOnUiThread(()->open(s));}
            catch(Exception e){runOnUiThread(()->showFailure(e,this::newChat));}
        });
    }
    private void open(HermesApi.SessionInfo s){store.sessionId(s.id);store.sessionTitle(s.id,s.displayTitle());Intent i=Nav.chat(this,s.id,s.displayTitle(),s.model);forwardChatExtras(i);Nav.startChat(this,i);finish();overridePendingTransition(0,0);}

    /**
     * Carries prompt/voice/image extras from whoever routed through MainActivity (AskActivity, a
     * shortcut, a notification, EXTRA_OPEN_CHAT from another screen) onto the ChatActivity intent
     * that actually opens — both the direct-session path and the find-or-create path need this.
     */
    private void forwardChatExtras(Intent i){
        Intent from=getIntent();
        String prompt=from.getStringExtra(ChatActivity.EXTRA_PROMPT);
        if(prompt!=null&&(from.getFlags()&Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)==0)i.putExtra(ChatActivity.EXTRA_PROMPT,prompt); // reopened from recents: don't send it again
        i.putExtra(ChatActivity.EXTRA_AUTOSEND,from.getBooleanExtra(ChatActivity.EXTRA_AUTOSEND,true));
        i.putExtra(EXTRA_PROFILE_SECRET,from.getStringExtra(EXTRA_PROFILE_SECRET));
        if(from.getBooleanExtra(EXTRA_SWITCHING,false))i.putExtra(EXTRA_SWITCHING,true).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        i.putExtra(ChatActivity.EXTRA_VOICE,from.getBooleanExtra(EXTRA_START_VOICE,from.getBooleanExtra(ChatActivity.EXTRA_VOICE,false)));
        if(from.getBooleanExtra(ChatActivity.EXTRA_DICTATE,false))i.putExtra(ChatActivity.EXTRA_DICTATE,true);
        java.util.ArrayList<android.net.Uri> images=from.getParcelableArrayListExtra(ChatActivity.EXTRA_IMAGES);
        if(images!=null&&!images.isEmpty()){
            i.putParcelableArrayListExtra(ChatActivity.EXTRA_IMAGES,images);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            android.content.ClipData clip=android.content.ClipData.newRawUri("images",images.get(0));
            for(int k=1;k<images.size();k++)clip.addItem(new android.content.ClipData.Item(images.get(k)));
            i.setClipData(clip);
        }
    }
    /** A new intent while we are already on screen (widget, tile, ASSIST) must still route. */

    /** The server is only known by Tailscale (100.x / *.ts.net) addresses and no VPN is up: the likely reason it can't be reached. */
    static boolean tailscaleDown(android.content.Context c,SettingsStore store){
        String all=(store.endpoint()+","+store.backupHosts()).toLowerCase(java.util.Locale.ROOT);
        if(all.contains("https://")||!all.matches("(?s).*(^|[/,\\s])100\\..*")&&!all.contains(".ts.net"))return false;
        try{
            android.net.ConnectivityManager cm=(android.net.ConnectivityManager)c.getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkCapabilities nc=cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc!=null&&!nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);
        }catch(Exception e){return false;}
    }

    /** Say why the server could not be reached, and offer retry or the connection settings. */
    private void showFailure(Exception e,Runnable retry){
        if(overlay!=null){overlay.dismiss();overlay=null;}
        String why=HermesApi.friendly(e);
        if(tailscaleDown(this,store))why+="\n\nTailscale isn't connected on this phone. Open Tailscale and connect, then retry.";
        status.setText("Can't reach "+store.profileName()+" at "+store.endpoint()+"\n"+why+"\n\nTap to retry");
        status.setGravity(Gravity.CENTER);status.setTextColor(Ui.RED);
        status.setOnClickListener(v->{status.setText("Opening conversation…");status.setTextColor(Ui.SUB);retry.run();});
        if(settingsLink==null){
            settingsLink=Ui.text(this,"CONNECTION SETTINGS",13,Ui.BLUE,true);
            settingsLink.setPadding(Ui.dp(this,16),Ui.dp(this,12),Ui.dp(this,16),Ui.dp(this,12));
            settingsLink.setOnClickListener(v->startActivity(new Intent(this,ConnectionActivity.class)));
            ((android.view.ViewGroup)status.getParent()).addView(settingsLink);
            // A broken profile must not trap the user in it.
            if(store.profiles().size()>1){
                TextView switchLink=Ui.text(this,"SWITCH PROFILE",13,Ui.BLUE,true);
                switchLink.setPadding(Ui.dp(this,16),Ui.dp(this,12),Ui.dp(this,16),Ui.dp(this,12));
                switchLink.setOnClickListener(v->Profiles.show(this));
                ((android.view.ViewGroup)status.getParent()).addView(switchLink);
            }
            if(!"Hermes".equals(store.serverType())){
                TextView hermesLink=Ui.text(this,"USE HERMES INSTEAD",13,Ui.BLUE,true);
                hermesLink.setPadding(Ui.dp(this,16),Ui.dp(this,12),Ui.dp(this,16),Ui.dp(this,12));
                hermesLink.setOnClickListener(v->{store.serverType("Hermes");recreate();});
                ((android.view.ViewGroup)status.getParent()).addView(hermesLink);
            }
        }
    }
    private TextView settingsLink;
    /** Only land in this app's own chats: a fresh profile's newest conversation is often a kanban task or a Telegram thread. */
    private static boolean isAppChat(HermesApi.SessionInfo s){String src=s.source==null?"":s.source;return src.isEmpty()||"api_server".equals(src)||"api".equals(src);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);try{if(switchProfileForIntent())return;route();}catch(Throwable t){CrashReporter.show(this,t);}}
    @Override protected void onDestroy(){super.onDestroy();network.shutdownNow();}
    @Override public void onBackPressed(){if(isTaskRoot())moveTaskToBack(true);else super.onBackPressed();}
}
