package cloud.stormlabs.hermescompanion;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * UnifiedPush connector, implemented directly against the wire protocol
 * (https://unifiedpush.org/developers/spec/android/) — no distributor library. Registers with
 * whatever distributor is installed (ntfy: io.heckel.ntfy) and turns MESSAGE broadcasts into
 * Hermes event notifications.
 */
public final class PushReceiver extends BroadcastReceiver {
    private static final String ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER";
    private static final String ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT";
    private static final String ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE";
    private static final String ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED";
    private static final String ACTION_REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED";
    /** The distributor everyone can install without Google: https://f-droid.org/packages/io.heckel.ntfy/ */
    static final String NTFY_PACKAGE = "io.heckel.ntfy";

    /** Finds an installed UnifiedPush distributor, preferring ntfy; null if none is installed. */
    static String findDistributor(Context c) {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> found = pm.queryBroadcastReceivers(new Intent(ACTION_REGISTER), 0);
        if (found.isEmpty()) return null;
        for (ResolveInfo r : found) if (NTFY_PACKAGE.equals(r.activityInfo.packageName)) return NTFY_PACKAGE;
        return found.get(0).activityInfo.packageName;
    }

    /** Sends the REGISTER request; the distributor answers asynchronously with NEW_ENDPOINT or REGISTRATION_FAILED. */
    static boolean register(Context c) {
        String distributor = findDistributor(c);
        if (distributor == null) return false;
        SettingsStore store = new SettingsStore(c);
        String hub = store.hubUrl();
        String profile = store.profileId();
        String token = store.pushToken(hub, profile);
        Intent request = new Intent(ACTION_REGISTER).setPackage(distributor)
                .putExtra("token", token)
                .putExtra("application", c.getPackageName());
        // Spec v2: a PendingIntent the distributor can reply through instead of a broadcast the OS
        // might drop; harmless for distributors that only understand the v1 broadcast reply.
        Intent reply = new Intent(c, PushReceiver.class).setPackage(c.getPackageName())
                .setData(android.net.Uri.parse("hermes-push:" + token));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        request.putExtra("pi", PendingIntent.getBroadcast(c, 0, reply, flags));
        store.pushDistributor(hub, distributor);
        c.sendBroadcast(request);
        return true;
    }

    @Override public void onReceive(Context context, Intent intent) {
        Context c = context.getApplicationContext();
        String action = intent.getAction();
        if (action == null) return;
        SettingsStore store = new SettingsStore(c);
        String token = intent.getStringExtra("token");
        // Exported receiver: without our registration token this could be any app injecting events.
        String hub = store.pushHub(token), profile = store.pushProfile(token);
        if (hub.isEmpty() || profile.isEmpty()) return;
        String target = store.mainProfileId(profile);
        String notificationProfile = target == null ? profile : target;

        switch (action) {
            case ACTION_NEW_ENDPOINT: {
                String endpoint = intent.getStringExtra("endpoint");
                if (endpoint == null || endpoint.isEmpty()) return;
                store.pushEndpoint(hub, endpoint);
                PendingResult result = goAsync();
                new Thread(() -> {
                    try {
                        SettingsStore.Connection connection = store.hubConnection(profile);
                        if (connection != null && hub.equals(connection.endpoint)) new HubApi(hub, connection.key).registerPush(endpoint);
                    }
                    catch (Exception ignored) { /* the poller still covers delivery */ }
                    finally { result.finish(); }
                }).start();
                break;
            }
            case ACTION_MESSAGE: {
                byte[] bytes = intent.getByteArrayExtra("bytesMessage");
                String text = bytes != null ? new String(bytes, StandardCharsets.UTF_8) : intent.getStringExtra("message");
                if (text == null || text.trim().isEmpty()) return;
                try {
                    HubApi.Event event = new HubApi.Event(new JSONObject(text));
                    if (event.body.trim().isEmpty()) return;
                    Notifier.event(c, event, hub, notificationProfile);
                    Notifier.eventSummary(c, 0, hub, notificationProfile);
                } catch (Exception ignored) { /* not a recognizable event payload */ }
                break;
            }
            case ACTION_UNREGISTERED:
                store.pushEndpoint(hub, ""); store.pushDistributor(hub, "");
                break;
            case ACTION_REGISTRATION_FAILED:
                store.pushEndpoint(hub, ""); store.pushDistributor(hub, "");
                break;
            default:
        }
    }
}
