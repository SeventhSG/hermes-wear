package cloud.stormlabs.hermescompanion;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Notification buttons: stop a run, answer an approval, or reply straight from the shade. */
public final class RunActionReceiver extends BroadcastReceiver {
    static final String ACTION_STOP = "cloud.stormlabs.hermescompanion.STOP_RUN";
    static final String ACTION_APPROVE = "cloud.stormlabs.hermescompanion.APPROVE_RUN";
    static final String ACTION_REPLY = "cloud.stormlabs.hermescompanion.REPLY_RUN";
    static final String ACTION_STOP_WAKE = "cloud.stormlabs.hermescompanion.STOP_WAKE";
    static final String ACTION_PAUSE_WAKE = "cloud.stormlabs.hermescompanion.PAUSE_WAKE";
    static final String ACTION_RESUME_WAKE = "cloud.stormlabs.hermescompanion.RESUME_WAKE";
    static final String ACTION_MUTE_ALERT = "cloud.stormlabs.hermescompanion.MUTE_ALERT";
    static final String EXTRA_SESSION = "session", EXTRA_CHOICE = "choice";

    @Override public void onReceive(Context context, Intent intent) {
        if (ACTION_MUTE_ALERT.equals(intent.getAction())) {
            String key = intent.getStringExtra(EXTRA_CHOICE);
            if (key == null) return;
            new SettingsStore(context).muteAlert(key, System.currentTimeMillis() + 3_600_000L);
            Notifier.cancelAlert(context, key);
            Toast.makeText(context, "Alert muted for 1 h", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ACTION_STOP_WAKE.equals(intent.getAction())) {
            new SettingsStore(context).wakeEnabled(false);
            WakeWordService.stop(context);
            return;
        }
        if (ACTION_PAUSE_WAKE.equals(intent.getAction()) || ACTION_RESUME_WAKE.equals(intent.getAction())) {
            if (!new SettingsStore(context).wakeEnabled()) return;
            if (ACTION_PAUSE_WAKE.equals(intent.getAction())) WakeWordService.pause(context);
            else WakeWordService.unpause(context);
            WakeWordService.ensure(context);
            return;
        }
        Context c = context.getApplicationContext();
        String session = intent.getStringExtra(EXTRA_SESSION);
        String action = intent.getAction();
        if (session == null || action == null) return;
        RunManager rm = RunManager.get(c);
        String originProfile = intent.getStringExtra(MainActivity.EXTRA_PROFILE);
        if (originProfile == null) return;
        switch (action) {
            case ACTION_STOP:
                rm.stop(rm.active(originProfile, session));
                break;
            case ACTION_APPROVE: {
                RunManager.LiveRun run = rm.active(originProfile, session);
                String choice = intent.getStringExtra(EXTRA_CHOICE);
                if (run == null || choice == null) {
                    String profile = run == null ? intent.getStringExtra(MainActivity.EXTRA_PROFILE) : run.profileId;
                    Notifier.cancelApproval(c, profile == null ? new SettingsStore(c).profileId() : profile, session);
                    return;
                }
                rm.approve(run, choice, r -> Toast.makeText(c, r.ok ? HermesApi.Approval.label(choice) : r.text, Toast.LENGTH_SHORT).show());
                break;
            }
            case ACTION_REPLY: {
                Bundle results = RemoteInput.getResultsFromIntent(intent);
                CharSequence text = results == null ? null : results.getCharSequence(Notifier.KEY_REPLY);
                if (text == null || text.toString().trim().isEmpty()) return;
                SettingsStore store = new SettingsStore(c);
                String profileId = intent.getStringExtra(MainActivity.EXTRA_PROFILE);
                if (profileId == null) profileId = store.profileId();
                SettingsStore.Connection connection = store.connection(profileId);
                if (connection == null) { Toast.makeText(c, "Profile no longer available", Toast.LENGTH_SHORT).show(); return; }
                AgentBackend api = Backends.create(connection.type, connection.endpoint, connection.key, session);
                String title = intent.getStringExtra(EXTRA_CHOICE);
                Notifier.cancelResult(c, profileId, session);
                RunManager.LiveRun started = rm.start(api, profileId, session, title, text.toString().trim(), DeviceContext.systemMessage(c, store), text.toString().trim(), false);
                if (started == null) Toast.makeText(c, "Hermes is still working on that conversation", Toast.LENGTH_SHORT).show();
                break;
            }
            default:
        }
    }
}
