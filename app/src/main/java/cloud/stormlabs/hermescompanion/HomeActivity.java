package cloud.stormlabs.hermescompanion;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Personal Home Assistant commands and opt-in place automations. */
public final class HomeActivity extends Activity implements RunManager.Observer {
    private TextView addPlaceButton;
    private int locationRequest; // ties the 20 s timeout to the request that started it
    private static final int FINE = 301, BACKGROUND = 302;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private SettingsStore store;
    private RunManager runs;
    private String apiProfile;
    private LinearLayout body;
    private TextView reply;
    private String watchedSession;
    private final java.util.Set<String> shownTiles = new java.util.HashSet<>();
    private boolean tilesRendered;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private CancellationSignal locationCancellation;
    private boolean locationRequestClosed;

    private int dp(int n) { return Ui.dp(this, n); }
    private LinearLayout.LayoutParams match(int h) { return new LinearLayout.LayoutParams(-1, h < 0 ? h : dp(h)); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.legacyWindowInsets(this);
        store = new SettingsStore(this); apiProfile = store.profileId(); runs = RunManager.get(this);
        getWindow().setStatusBarColor(Ui.BAR); getWindow().setNavigationBarColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); Ui.ambient(root);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL); bar.setBackgroundColor(Ui.BAR);
        ImageButton back = Ui.icon(this, R.drawable.ic_back, "Back"); back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(56)));
        bar.addView(Ui.text(this, "Home", 19, Ui.TEXT, true));
        root.addView(bar, match(56));
        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(12), dp(12), dp(28)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root); Ui.fitInsets(root, root);
    }

    @Override protected void onResume() { super.onResume(); watchedSession = store.homeSessionId(); runs.addObserver(this); render(); }
    @Override protected void onPause() { runs.removeObserver(this); super.onPause(); }
    @Override protected void onDestroy() {
        locationRequestClosed = true;
        if (locationCancellation != null) locationCancellation.cancel();
        if (locationManager != null && locationListener != null) locationManager.removeUpdates(locationListener);
        network.shutdownNow(); super.onDestroy();
    }

    private void render() {
        body.removeAllViews();
        label("QUICK COMMANDS");
        GridLayout grid = new GridLayout(this); grid.setColumnCount(2);
        List<HomeData.Tile> tiles = store.homeTiles();
        java.util.Set<String> currentTiles = new java.util.HashSet<>();
        int width = (getResources().getDisplayMetrics().widthPixels - dp(36)) / 2;
        for (int i = 0; i <= tiles.size(); i++) {
            final int index = i;
            boolean add = i == tiles.size();
            LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(dp(14), dp(13), dp(12), dp(12));
            cell.setMinimumHeight(dp(98));
            cell.setBackground(Ui.ripple(this, add ? Ui.RAISED : Ui.SURFACE, 14));
            if (add) {
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(36), dp(36)); ip.bottomMargin = dp(10);
                cell.addView(Ui.iconTile(this, R.drawable.ic_new, Ui.BLUE), ip);
                cell.addView(Ui.text(this, "Add command", 14, Ui.TEXT, true));
                cell.setOnClickListener(v -> editTile(-1));
            } else {
                HomeData.Tile tile = tiles.get(i);
                String key = tile.label + "\n" + tile.phrase;
                currentTiles.add(key);
                if (tilesRendered && !shownTiles.contains(key)) cell.post(() -> Ui.fadeIn(cell));
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(36), dp(36)); ip.bottomMargin = dp(10);
                cell.addView(Ui.iconTile(this, tileIcon(tile), 0), ip);
                TextView name = Ui.text(this, tile.label, 16, Ui.TEXT, true); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END); cell.addView(name);
                if (!HomeData.phraseRepeatsLabel(tile.label, tile.phrase)) {
                    TextView phrase = Ui.text(this, tile.phrase, 12, Ui.SUB, false); phrase.setMaxLines(2); phrase.setEllipsize(TextUtils.TruncateAt.END);
                    phrase.setPadding(0, dp(4), 0, 0); cell.addView(phrase);
                }
                cell.setOnClickListener(v -> send(tile.phrase));
                cell.setOnLongClickListener(v -> { Sheets.actions(this, tile.label, "Quick command",
                        Sheets.Item.of(R.drawable.ic_compose, Ui.BLUE, "Edit", tile.phrase, () -> editTile(index)),
                        Sheets.Item.danger(R.drawable.ic_delete, "Delete", "Remove this command", () ->
                                Sheets.confirm(this, "Delete command?", "Remove " + tile.label + "?", "Delete", true, () -> {
                                    List<HomeData.Tile> current = store.homeTiles(); current.remove(index); store.homeTiles(current); render();
                                }))); return true; });
            }
            cell.setContentDescription(add ? "Add command" : tiles.get(i).label + ". Long press to edit or delete");
            GridLayout.LayoutParams p = new GridLayout.LayoutParams(); p.width = width; p.height = -2;
            p.setMargins(dp(3), dp(3), dp(3), dp(3)); grid.addView(cell, p);
        }
        Ui.appearing(grid);
        shownTiles.clear(); shownTiles.addAll(currentTiles); tilesRendered = true;
        body.addView(grid, match(-2));
        reply = Ui.text(this, "Tap to ask Hermes · long press to edit", 13, Ui.SUB, false);
        reply.setPadding(dp(12), dp(12), dp(12), dp(12)); reply.setMinHeight(dp(48));
        body.addView(reply, match(-2));
        RunManager.LiveRun last = runs.run(apiProfile, store.homeSessionId());
        if (last != null) updateReply(last);
        label("PLACES");
        TextView intro = Ui.text(this, "Run a Home command when you arrive or leave. Location stays on this phone; Android needs background location to detect places while the app is closed.", 13, Ui.SUB, false);
        intro.setPadding(dp(10), 0, dp(10), dp(10)); body.addView(intro);
        for (HomeData.Place p : store.places()) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12)); row.setBackground(Ui.ripple(this, Ui.SURFACE, 14));
            boolean permitted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    && (Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED);
            row.addView(Ui.text(this, p.name + (p.enabled ? (permitted ? "" : " · Needs location access") : " · Off"),
                    16, p.enabled && permitted ? Ui.TEXT : Ui.FAINT, true));
            row.addView(Ui.text(this, Math.round(p.radius) + " m radius · Arrive: " + shortText(p.arrive) + " · Leave: " + shortText(p.leave), 12, Ui.SUB, false));
            row.setOnClickListener(v -> placeActions(p));
            LinearLayout.LayoutParams rp = match(-2); rp.bottomMargin = dp(8); body.addView(row, rp);
        }
        TextView addPlace = Ui.text(this, "+  Add Home or Work place", 15, Ui.BLUE, true); addPlaceButton = addPlace;
        addPlace.setGravity(Gravity.CENTER_VERTICAL); addPlace.setPadding(dp(16), 0, 0, 0);
        addPlace.setBackground(Ui.ripple(this, Ui.RAISED, 14)); addPlace.setOnClickListener(v -> addPlace());
        body.addView(addPlace, match(54));
    }

    private String shortText(String s) { return s.isEmpty() ? "off" : s.length() > 28 ? s.substring(0, 28) + "…" : s; }
    private void label(String text) {
        TextView v = Ui.text(this, text, 11, Ui.BLUE, true); v.setLetterSpacing(.09f);
        LinearLayout.LayoutParams p = match(-2); p.setMargins(dp(10), dp(16), 0, dp(8)); body.addView(v, p);
    }

    private static int tileIcon(HomeData.Tile t) {
        switch (HomeData.iconKey(t.label, t.phrase)) {
            case "light": return R.drawable.ic_brightness;
            case "night": return R.drawable.ic_moon;
            case "leave": return R.drawable.ic_door;
            case "shop": return R.drawable.ic_cart;
            case "home": return R.drawable.ic_home;
            case "music": return R.drawable.ic_speaker;
            default: return R.drawable.ic_bolt;
        }
    }

    private void editTile(int index) {
        List<HomeData.Tile> tiles = store.homeTiles(); HomeData.Tile old = index < 0 ? null : tiles.get(index);
        LinearLayout form = form(); EditText name = field(form, "Label", old == null ? "" : old.label);
        EditText phrase = field(form, "What to say to Hermes", old == null ? "" : old.phrase);
        Dialog d = Sheets.form(this, old == null ? "Add command" : "Edit command", form, "Save", () -> {
            String n = name.getText().toString().trim(), p = phrase.getText().toString().trim();
            if (n.isEmpty() || p.isEmpty()) { toast("Add a label and phrase"); return false; }
            if (index < 0) tiles.add(new HomeData.Tile(n, p)); else tiles.set(index, new HomeData.Tile(n, p));
            store.homeTiles(tiles); render(); return true;
        });
        confirmDiscard(d, name, phrase);
    }

    private void send(String phrase) {
        reply.setText("Hermes is starting…");
        network.submit(() -> {
            try {
                AgentBackend api = Backends.create(store, apiProfile);
                String sid = api.ensureSession("Home").id; store.homeSessionId(sid);
                watchedSession = sid;
                RunManager.LiveRun run = runs.start(api, apiProfile, sid, "Home", phrase, DeviceContext.systemMessage(this, store), phrase, false);
                runOnUiThread(() -> { if (run == null) reply.setText("Hermes is already working in Home"); else updateReply(run); });
            } catch (Exception e) { runOnUiThread(() -> reply.setText(HermesApi.friendly(e))); }
        });
    }

    @Override public void onRunUpdate(RunManager.LiveRun run, String kind) {
        if (run.belongsTo(apiProfile, watchedSession) && reply != null) updateReply(run);
    }
    private void updateReply(RunManager.LiveRun run) {
        String text = run.error != null ? run.error : run.finalText();
        if (text.isEmpty()) text = run.active() ? "Hermes is working…" : "Done";
        reply.setText(text);
        reply.setTextColor(run.error == null ? Ui.SUB : Ui.RED);
    }

    private void addPlace() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(Build.VERSION.SDK_INT >= 31
                    ? new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}
                    : new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, FINE); return;
        }
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Sheets.confirm(this, "Allow background location",
                    "Android needs 'Allow all the time' so arrival and departure commands can run while Hermes is closed. Enable it for Hermes, then return here to add a place.",
                    "Continue", "Not now", false, () -> {
                        if (Build.VERSION.SDK_INT >= 30) startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                        else requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, BACKGROUND);
                    });
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) { toast("Location unavailable"); return; }
        locationManager = lm;
        // Wi-Fi/cell location is quick and well within a 100 m place radius; GPS can take minutes indoors.
        String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER;
        if (!lm.isProviderEnabled(provider)) { toast("Turn on location first"); return; }
        locationRequestClosed = false;
        Location recent = recentFix(lm);
        if (recent != null) { newPlaceAt(recent); return; }
        if (addPlaceButton != null) { addPlaceButton.setText("Finding your location…"); addPlaceButton.setEnabled(false); }
        final int request = ++locationRequest;
        getWindow().getDecorView().postDelayed(() -> {
            if (request != locationRequest || locationRequestClosed || isFinishing() || isDestroyed()) return;
            if (locationCancellation != null) locationCancellation.cancel();
            if (locationListener != null) lm.removeUpdates(locationListener);
            newPlaceAt(null);
        }, 20_000);
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                locationCancellation = new CancellationSignal();
                lm.getCurrentLocation(provider, locationCancellation, getMainExecutor(), this::newPlaceAt);
            } else {
                locationListener = this::newPlaceAt;
                lm.requestSingleUpdate(provider, locationListener, null);
            }
        } catch (SecurityException e) { toast("Precise location permission needed"); }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == FINE || code == BACKGROUND) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) addPlace();
            else Sheets.confirm(this, "Location permission needed",
                    "Allow precise location in App info to add a place.", "App info", false, () ->
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName()))));
        }
    }

    /** A last-known fix from the past 2 minutes that is good enough for a 100 m place, if any. */
    private Location recentFix(LocationManager lm) {
        Location best = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused"}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l == null || System.currentTimeMillis() - l.getTime() > 120_000 || (l.hasAccuracy() && l.getAccuracy() > 100)) continue;
                if (best == null || l.getAccuracy() < best.getAccuracy()) best = l;
            } catch (SecurityException | IllegalArgumentException ignored) { }
        }
        return best;
    }

    private void newPlaceAt(Location location) {
        if (locationRequestClosed || isFinishing() || isDestroyed()) return;
        locationRequestClosed = true;
        locationCancellation = null;
        locationListener = null;
        if (addPlaceButton != null) { addPlaceButton.setText("+  Add Home or Work place"); addPlaceButton.setEnabled(true); }
        if (location == null) { toast("Couldn't get a location fix. Try again near a window."); return; }
        editPlace(null, location);
    }

    private void placeActions(HomeData.Place place) {
        Sheets.actions(this, place.name, place.enabled ? "Place automation on" : "Place automation off",
                Sheets.Item.of(R.drawable.ic_compose, Ui.BLUE, "Edit", "Radius and phrases", () -> editPlace(place, null)),
                Sheets.Item.of(R.drawable.ic_bolt, Ui.AMBER, place.enabled ? "Disable" : "Enable", "Toggle this place", () -> {
                    List<HomeData.Place> all = store.places();
                    HomeData.Place changed = place.enabled(!place.enabled);
                    for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(place.id)) all.set(i, changed);
                    store.places(all); PresenceReceiver.register(this, changed); render();
                }),
                Sheets.Item.danger(R.drawable.ic_delete, "Delete", "Remove this place", () ->
                        Sheets.confirm(this, "Delete place?", "Remove " + place.name + "?", "Delete", true, () -> {
                            PresenceReceiver.remove(this, place.id);
                            List<HomeData.Place> all = store.places(); all.removeIf(p -> p.id.equals(place.id));
                            store.places(all); render();
                        })));
    }

    private void editPlace(HomeData.Place old, Location location) {
        LinearLayout form = form();
        EditText name = field(form, "Place name (Home or Work)", old == null ? "Home" : old.name);
        EditText radius = field(form, "Radius in metres", old == null ? "150" : String.valueOf(Math.round(old.radius)));
        radius.setInputType(2);
        EditText arrive = field(form, "On arrive · what to say", old == null ? "" : old.arrive);
        EditText leave = field(form, "On leave · what to say", old == null ? "" : old.leave);
        Dialog d = Sheets.form(this, old == null ? "Add place" : "Edit place", form, "Save", () -> {
            String n = name.getText().toString().trim(), a = arrive.getText().toString().trim(), l = leave.getText().toString().trim();
            int metres;
            try { metres = Integer.parseInt(radius.getText().toString().trim()); } catch (NumberFormatException e) { metres = 0; }
            if (n.isEmpty() || metres < 100 || metres > 10000 || (a.isEmpty() && l.isEmpty())) {
                toast("Name, radius 100–10000 m and an arrival or departure phrase required"); return false;
            }
            HomeData.Place next = new HomeData.Place(old == null ? UUID.randomUUID().toString() : old.id, n,
                    old == null ? location.getLatitude() : old.latitude, old == null ? location.getLongitude() : old.longitude,
                    metres, a, l, old == null || old.enabled);
            List<HomeData.Place> all = store.places();
            if (old != null) all.removeIf(p -> p.id.equals(old.id));
            all.add(next); store.places(all); PresenceReceiver.register(this, next); render(); return true;
        });
        confirmDiscard(d, name, radius, arrive, leave);
    }

    private void confirmDiscard(Dialog dialog, EditText... fields) {
        String[] initial = new String[fields.length];
        for (int i = 0; i < fields.length; i++) initial[i] = fields[i].getText().toString();
        dialog.setOnCancelListener(x -> {
            for (int i = 0; i < fields.length; i++) if (!initial[i].equals(fields[i].getText().toString())) {
                getWindow().getDecorView().post(() -> Sheets.confirm(this, "Discard changes?",
                        "Your edits have not been saved.", "Discard", true, () -> {}, dialog::show));
                break;
            }
        });
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8)); return form;
    }
    private EditText field(LinearLayout form, String hint, String value) {
        // Caption stays visible once the field has text (a bare "150" said nothing about metres).
        TextView caption = Ui.text(this, hint, 12, Ui.BLUE, true); caption.setPadding(dp(4), dp(10), 0, 0);
        form.addView(caption);
        EditText e = new EditText(this); e.setSingleLine(true); e.setTextSize(16); e.setText(value);
        e.setTextColor(Ui.TEXT); e.setHintTextColor(Ui.FAINT);
        e.setPadding(dp(14), 0, dp(14), 0); e.setBackground(Ui.stroke(this, Ui.SURFACE, Ui.BLUE, 12));
        e.setContentDescription(hint);
        form.addView(e, match(56)); return e;
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    @Override public void onBackPressed() { Ui.back(this); }
}
