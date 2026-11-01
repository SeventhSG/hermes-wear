package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ConnectionActivity extends Activity {
    /** Create and switch to a new profile instead of editing the current connection. */
    static final String EXTRA_NEW_PROFILE="new_profile";
    private boolean creating; private EditText name; private String[] originalFields;
    private SettingsStore store; private EditText endpoint,key,essBase,essToken; private TextView testButton,statusText,saveButton; private final ExecutorService network=Executors.newSingleThreadExecutor();
    private int dp(int n){return Ui.dp(this,n);} private LinearLayout.LayoutParams match(int h){return new LinearLayout.LayoutParams(-1,h<0?h:dp(h));}
    @Override public void onCreate(Bundle b){super.onCreate(b);CrashReporter.install(this);try{Ui.legacyWindowInsets(this);store=new SettingsStore(this);getWindow().setStatusBarColor(Ui.BAR);getWindow().setNavigationBarColor(Ui.BG);build();if(creating)originalFields=fields();loadSecrets();}catch(Throwable t){CrashReporter.show(this,t);}}
    private void build(){creating=getIntent().getBooleanExtra(EXTRA_NEW_PROFILE,false);String serverType=store.serverType();boolean claudeBridge="Claude Code".equals(serverType)||"Codex".equals(serverType),codex="Codex".equals(serverType);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Ui.BG);LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(2),0,dp(8),0);bar.setBackgroundColor(Ui.BAR);ImageButton back=Ui.icon(this,R.drawable.ic_back,"Back");back.setOnClickListener(v->onBackPressed());bar.addView(back,new LinearLayout.LayoutParams(dp(52),dp(56)));bar.addView(Ui.text(this,creating?"New profile":codex?"Codex remote":claudeBridge?"Claude Code bridge":"Hermes server",19,Ui.TEXT,true),new LinearLayout.LayoutParams(0,-2,1));root.addView(bar,match(56));ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(14),dp(16),dp(14),dp(32));
        LinearLayout status=new LinearLayout(this);status.setOrientation(LinearLayout.VERTICAL);status.setPadding(dp(18),dp(16),dp(18),dp(16));status.setBackground(Ui.gradient(this,Ui.pick(0xff22272e,Ui.RAISED),Ui.pick(0xff1b1f24,Ui.SURFACE),16));TextView heading=Ui.text(this,"Direct connection",18,Ui.TEXT,true);status.addView(heading);TextView desc=Ui.text(this,codex?"Continue your Codex sessions from the phone: send, steer, stop and approve while Codex works on your server. Pair over Tailscale or enter the bridge URL and token.":claudeBridge?"Connect to your self-hosted Claude Code bridge. This version supports text chat and stopping runs.":"The phone connects to your Hermes API. Commands execute on your server, not on Android.",13,Ui.SUB,false);desc.setLineSpacing(0,1.18f);desc.setPadding(0,dp(5),0,dp(12));status.addView(desc);statusText=Ui.text(this,"●  NOT TESTED",11,Ui.FAINT,true);statusText.setLetterSpacing(.07f);status.addView(statusText);body.addView(status,match(-2));
        label(body,"PROFILE NAME");name=field(false);name.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS);name.setHint(creating?"Worker, Fitness, Alex's Hermes…":Profiles.defaultName(store.endpoint()));name.setText(creating?"":store.profileName().equals(Profiles.defaultName(store.endpoint()))?"":store.profileName());body.addView(name,match(56));
        label(body,"SERVER URL");endpoint=field(false);endpoint.setText(creating&&!claudeBridge?Profiles.serverRoot(store.endpoint())+"/p/":creating?"":store.endpoint());endpoint.setSelection(endpoint.length());body.addView(endpoint,match(56));TextView lan=Ui.text(this,codex?"Run codex-rc on the server once, then Set up automatically, or enter http://<server>:8795 and the token from ~/.hermes/secrets/codex-bridge.env.":claudeBridge?"Enter the bridge URL, not the Anthropic API URL. Use HTTPS or Tailscale.":creating?"Another profile on this server: add its name after /p/ and use that profile's API key. Or enter any other Hermes server.":"Example: https://hermes.example.com — use your Tailscale or LAN address.",12,Ui.FAINT,false);lan.setPadding(dp(4),dp(7),dp(4),0);body.addView(lan);
        label(body,claudeBridge?"BRIDGE TOKEN":"API KEY");key=field(true);body.addView(key,match(56));LinearLayout reveal=new LinearLayout(this);reveal.setGravity(Gravity.CENTER_VERTICAL);reveal.setPadding(dp(4),dp(8),dp(4),dp(12));reveal.addView(Ui.text(this,claudeBridge?"Show bridge token":"Show API key",13,Ui.SUB,false),new LinearLayout.LayoutParams(0,-2,1));HermesSwitch show=new HermesSwitch(this);show.setListener(checked->{int pos=key.getSelectionStart();key.setInputType(InputType.TYPE_CLASS_TEXT|(checked?InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:InputType.TYPE_TEXT_VARIATION_PASSWORD));key.setTypeface(Typeface.MONOSPACE);if(pos>=0)key.setSelection(Math.min(pos,key.length()));});reveal.addView(show,new LinearLayout.LayoutParams(dp(44),dp(28)));body.addView(reveal,match(44));
        if(!creating){label(body,"ESSENTIALS CAPTURE URL");essBase=field(false);essBase.setText(store.essentialsBase());body.addView(essBase,match(56));TextView essHint=Ui.text(this,"Your capture inbox — e.g. https://essentials.example.com. Leave blank to use the chat lane only.",12,Ui.FAINT,false);essHint.setPadding(dp(4),dp(7),dp(4),0);body.addView(essHint);
        label(body,"ESSENTIALS TOKEN");essToken=field(true);body.addView(essToken,match(56));TextView essHint2=Ui.text(this,"Set it once; it is encrypted on this device and never sent anywhere else.",12,Ui.FAINT,false);essHint2.setPadding(dp(4),dp(7),dp(4),0);body.addView(essHint2);TextView imp=button("IMPORT FROM HUB",false);imp.setContentDescription("Import Essentials URL and token from the hub");imp.setOnClickListener(v->importEssentials(imp));LinearLayout.LayoutParams ip=match(48);ip.setMargins(0,dp(10),0,0);body.addView(imp,ip);}
        if(!creating&&!store.pairHub().isEmpty()){TextView auto=button("SET UP AUTOMATICALLY",true);auto.setContentDescription("Set up automatically over Tailscale");auto.setOnClickListener(v->autoSetup(auto));LinearLayout.LayoutParams ap=match(52);ap.setMargins(0,0,0,dp(10));body.addView(auto,ap);autoButton=auto;}
        if(!creating){TextView pair=button("PAIR VIA TAILSCALE HUB",false);pair.setOnClickListener(v->Sheets.input(this,"Pairing hub","Your hermes-hub address on Tailscale; it hands this phone its connection.",store.pairHub(),"http://100.x.y.z:8791",u->{store.pairHub(u);if(store.pairHub().isEmpty())return;autoSetup(pair);}));LinearLayout.LayoutParams pp=match(48);pp.setMargins(0,0,0,dp(10));body.addView(pair,pp);}testButton=button("TEST CONNECTION",false);testButton.setOnClickListener(v->test());body.addView(testButton,match(52));saveButton=button(creating?"SAVE AND SWITCH":"SAVE CONNECTION",true);saveButton.setOnClickListener(v->save());LinearLayout.LayoutParams sp=match(52);sp.setMargins(0,dp(10),0,0);body.addView(saveButton,sp);if(claudeBridge&&!creating){TextView hermes=button("USE HERMES INSTEAD",false);hermes.setOnClickListener(v->useHermes());LinearLayout.LayoutParams hp=match(52);hp.setMargins(0,dp(10),0,0);body.addView(hermes,hp);}TextView secure=Ui.text(this,"API credentials are encrypted with Android Keystore on this device.",12,Ui.FAINT,false);secure.setGravity(Gravity.CENTER);secure.setPadding(dp(8),dp(18),dp(8),0);body.addView(secure,match(-2));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);Ui.fitInsets(root, root);}
    private void label(LinearLayout parent,String value){TextView label=Ui.text(this,value,11,Ui.BLUE,true);label.setLetterSpacing(.09f);label.setPadding(dp(8),dp(22),0,dp(8));parent.addView(label,match(-2));}
    private EditText field(boolean secret){EditText e=new EditText(this);e.setTextColor(Ui.TEXT);e.setHintTextColor(Ui.FAINT);e.setTextSize(15);e.setSingleLine(true);e.setPadding(dp(15),0,dp(15),0);e.setBackground(Ui.stroke(this,Ui.SURFACE,Ui.LINE,13));if(secret){e.setTypeface(Typeface.MONOSPACE);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);}e.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);e.setOnFocusChangeListener((v,focus)->e.setBackground(Ui.stroke(this,Ui.SURFACE,focus?Ui.BLUE:Ui.LINE,13)));return e;}
    private TextView button(String text,boolean primary){TextView b=Ui.text(this,text,13,primary?Color.WHITE:Ui.BLUE,true);b.setGravity(Gravity.CENTER);b.setLetterSpacing(.06f);b.setBackground(primary?Ui.ripple(this,Ui.BLUE,13):Ui.ripple(this,Ui.SURFACE,13));return b;}
    private void test(){String url=Keys.clean(endpoint.getText().toString()),token=Keys.clean(key.getText().toString());if(!UrlUtils.isUsableEndpoint(url)||token.isEmpty()){setStatus("●  HTTP(S) URL AND KEY REQUIRED",Ui.RED);return;}String normalized=UrlUtils.normalize(url);testButton.setEnabled(false);testButton.setText("CONNECTING…");setStatus("●  CHECKING",Ui.AMBER);AgentBackend api=Backends.create(store.serverType(),normalized,token,store.sessionId());network.submit(()->{HermesApi.Result result=api.health();runOnUiThread(()->{testButton.setEnabled(true);testButton.setText("TEST CONNECTION");setStatus(result.ok?"●  CONNECTED":"●  CONNECTION FAILED",result.ok?Ui.GREEN:Ui.RED);if(!result.ok)Sheets.info(this,"Connection failed",result.text,new String[][]{{"Endpoint",normalized},{"Action","Check the address, API key and VPN/LAN route"}});});});}
    private void loadSecrets() {
        if (creating) return;
        saveButton.setEnabled(false);
        testButton.setEnabled(false);
        network.submit(() -> {
            String api = store.apiKey(), essentials = store.essentialsToken();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (key.length() == 0) key.setText(api);
                // Fresh install: try Tailscale pairing straight away, so there is nothing to type.
                if (api.isEmpty() && autoButton != null && !store.pairHub().isEmpty() && !"Claude Code".equals(store.serverType())) autoSetup(autoButton);
                if (essToken.length() == 0) essToken.setText(essentials);
                originalFields = fields();
                saveButton.setEnabled(true);
                testButton.setEnabled(true);
            });
        });
    }
    private void save() {
        if (creating) { saveNewProfile(); return; }
        String rawUrl = endpoint.getText().toString(), rawKey = key.getText().toString();
        String rawBase = essBase.getText().toString(), rawToken = essToken.getText().toString();
        boolean noisy = Keys.hadNoise(rawKey) || Keys.hadNoise(rawToken) || Keys.hadNoise(rawUrl) || Keys.hadNoise(rawBase);
        String url = Keys.clean(rawUrl), token = Keys.clean(rawKey);
        if (!UrlUtils.isUsableEndpoint(url) || token.isEmpty()) { setStatus("●  HTTP(S) URL AND KEY REQUIRED", Ui.RED); return; }
        String eb = Keys.clean(rawBase), et = Keys.clean(rawToken);
        // Blank Essentials fields mean "chat lane only"; one blank field is incomplete.
        if (eb.isEmpty() != et.isEmpty()) { setStatus("●  ESSENTIALS NEEDS BOTH URL AND TOKEN", Ui.AMBER); return; }
        String profileName = Keys.clean(name.getText().toString());
        // Editing the active profile with another saved profile's URL would turn this one into a duplicate of it
        // (how a main profile ended up as a second "nsfw").
        String target = UrlUtils.normalize(url);
        if (!target.equals(store.endpoint())) for (SettingsStore.Profile p : store.profiles())
            if (!p.id.equals(store.profileId()) && target.equals(UrlUtils.normalize(p.endpoint))) {
                setStatus("●  THAT'S YOUR " + p.name.toUpperCase(java.util.Locale.ROOT) + " PROFILE · SWITCH TO IT IN PROFILES", Ui.AMBER); return;
            }
        saveButton.setEnabled(false);
        network.submit(() -> {
            try {
                String previousRoot = Profiles.serverRoot(store.endpoint());
                String normalized = UrlUtils.normalize(url);
                if (!previousRoot.equals(Profiles.serverRoot(normalized))) store.hubUrl("");
                store.endpoint(normalized);
                store.apiKey(token);
                store.profileName(profileName);
                store.essentialsBase(eb);
                store.essentialsToken(et);
                runOnUiThread(() -> {
                    if (noisy) android.widget.Toast.makeText(this, "Removed invisible characters from a pasted value", android.widget.Toast.LENGTH_LONG).show();
                    finish();
                });
            } catch (Throwable t) { runOnUiThread(() -> { saveButton.setEnabled(true); CrashReporter.show(this, t); }); }
        });
    }
    private void saveNewProfile() {
        String url = Keys.clean(endpoint.getText().toString()), token = Keys.clean(key.getText().toString());
        String normalized = UrlUtils.normalize(url);
        if (!UrlUtils.isUsableEndpoint(normalized) || Profiles.needsProfileName(normalized) || token.isEmpty()) { setStatus("●  HTTP(S) URL AND KEY REQUIRED", Ui.RED); return; }
        String profileName = Keys.clean(name.getText().toString()), type = store.serverType();
        saveButton.setEnabled(false);
        network.submit(() -> {
            try {
                store.addProfile(profileName, type, normalized, token);
                runOnUiThread(() -> Profiles.restart(this));
            } catch (Throwable t) { runOnUiThread(() -> { saveButton.setEnabled(true); CrashReporter.show(this, t); }); }
        });
    }
    // An unconfigured Claude Code bridge has no way into the app; leaving goes back to Hermes.
    private String[] fields(){return new String[]{name.getText().toString(),endpoint.getText().toString(),key.getText().toString(),creating?"":essBase.getText().toString(),creating?"":essToken.getText().toString()};}
    @Override public void onBackPressed(){if(originalFields!=null&&!java.util.Arrays.equals(originalFields,fields()))Sheets.confirm(this,"Discard changes?","Your connection edits have not been saved.","Discard",true,this::leave);else leave();}
    private void leave(){if(!creating&&originalFields!=null&&!"Hermes".equals(store.serverType())&&key.length()==0)useHermes();else if(isTaskRoot()&&!store.apiKey().isEmpty())Ui.back(this);else super.onBackPressed();}
    private void useHermes(){store.serverType("Hermes");startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));finish();}
    /** The capture token only lives on the server; fetch it (and a reachable URL) with the main key. */
    private void importEssentials(TextView button){
        button.setEnabled(false);button.setText("IMPORTING…");
        network.submit(()->{
            String[] cfg=null;String error=null;
            try{cfg=new HubApi(store.hubUrl(),store.hubKey()).essentialsConfig();
                if(cfg[0].isEmpty()||cfg[1].isEmpty())error="The hub has no Essentials setup";
                else{String h=new EssentialsApi(cfg[0],cfg[1]).health();}}
            catch(Exception e){error=HermesApi.friendly(e);}
            final String[] got=cfg;final String err=error;
            runOnUiThread(()->{if(isFinishing()||isDestroyed())return;button.setEnabled(true);button.setText("IMPORT FROM HUB");
                if(err!=null){setStatus("●  ESSENTIALS: "+err.toUpperCase(java.util.Locale.ROOT),Ui.RED);return;}
                essBase.setText(got[0]);essToken.setText(got[1]);setStatus("●  ESSENTIALS CONNECTED · SAVE TO KEEP",Ui.GREEN);});
        });
    }
    private TextView autoButton;
    private void autoSetup(TextView button){
        CharSequence label=button.getText();button.setEnabled(false);button.setText("SETTING UP…");setStatus("●  CONNECTING OVER TAILSCALE",Ui.AMBER);
        network.submit(()->{String err=Pairing.pair(store);
            runOnUiThread(()->{if(isFinishing()||isDestroyed())return;button.setEnabled(true);button.setText(label);
                if(err!=null){setStatus("●  "+err.toUpperCase(java.util.Locale.ROOT),Ui.RED);return;}
                Toast.makeText(this,"Connected",Toast.LENGTH_SHORT).show();
                startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));finish();});});
    }
    private void setStatus(String text,int color){statusText.setText(text);statusText.setTextColor(color);}
    @Override protected void onDestroy(){super.onDestroy();network.shutdown();}
}
