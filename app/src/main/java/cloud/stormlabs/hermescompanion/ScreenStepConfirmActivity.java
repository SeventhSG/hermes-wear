package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Strong confirmation for screen steps requested from the voice overlay. */
public final class ScreenStepConfirmActivity extends Activity {
    private boolean prompted;
    private final List<DeviceActions.Action> actions = new ArrayList<>();

    static Intent intent(Context context, List<DeviceActions.Action> steps, String sessionId, String app) {
        JSONArray json = new JSONArray();
        for (DeviceActions.Action step : steps) {
            try { json.put(new JSONObject(step.args.toString()).put("action", step.type)); }
            catch (Exception ignored) {}
        }
        return new Intent(context, ScreenStepConfirmActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("actions", json.toString()).putExtra("session", sessionId)
                .putExtra("app", app);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            JSONArray json = new JSONArray(getIntent().getStringExtra("actions"));
            for (int i = 0; i < json.length(); i++) {
                JSONObject args = json.optJSONObject(i);
                if (args != null && DeviceActions.isScreenStep(args.optString("action", args.optString("type"))))
                    actions.add(new DeviceActions.Action(args.optString("action", args.optString("type")), args));
            }
        } catch (Exception ignored) {}
        if (actions.isEmpty()) finish();
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        if (prompted || actions.isEmpty()) return;
        prompted = true;
        if (!DeviceActions.needsConfirmation(actions.get(0), new SettingsStore(this))) {
            DeviceActionRunner.runAll(this, actions, getIntent().getStringExtra("session"));
            finish();
            return;
        }
        String app = getIntent().getStringExtra("app");
        if (app == null || "Unknown app".equals(app) || !HermesAccessibilityService.enabled()) {
            DeviceActionRunner.runAll(this, actions, getIntent().getStringExtra("session"));
            finish();
            return;
        }
        StringBuilder detail = new StringBuilder("App: ").append(app);
        for (DeviceActions.Action action : actions) detail.append('\n').append(action.describe());
        Confirm.strong(this, "Screen steps in " + app, detail.toString(), () -> {
            String session = getIntent().getStringExtra("session");
            Context context = getApplicationContext();
            finish();
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                    DeviceActionRunner.runAll(context, actions, session, null, true, app), 450);
        }, () -> {
            DeviceActionRunner.runAll(this, actions, getIntent().getStringExtra("session"));
            finish();
        });
    }
}
