package cloud.stormlabs.hermescompanion;

import android.content.Context;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;

/**
 * Firebase Cloud Messaging: the hub sends a data-only message whose "event" is the same event JSON
 * UnifiedPush endpoints get, and we post it through Notifier like any other push. Inactive until the
 * build includes app/google-services.json (no FirebaseApp, no token).
 */
public final class FcmService extends FirebaseMessagingService {
    @Override public void onNewToken(String token) { register(getApplicationContext(), token); }

    @Override public void onMessageReceived(RemoteMessage message) {
        String phone = message.getData().get("phone");
        if (phone != null) { PhoneCommand.handle(getApplicationContext(), phone); return; }
        String json = message.getData().get("event");
        if (json == null) return;
        Context c = getApplicationContext();
        SettingsStore store = new SettingsStore(c);
        String profile = store.profileId(), target = store.mainProfileId(profile);
        String notificationProfile = target == null ? profile : target;
        SettingsStore.Connection hub = store.hubConnection(profile);
        String origin = hub == null ? "relay" : hub.endpoint; // relay-only setups have no hub connection
        try {
            HubApi.Event event = new HubApi.Event(new JSONObject(json));
            if (event.body.trim().isEmpty()) return;
            Notifier.event(c, event, origin, notificationProfile);
            Notifier.eventSummary(c, 0, origin, notificationProfile);
        } catch (Exception ignored) { /* not an event payload */ }
    }

    /** Called on every app resume (PushPoller.schedule): fetch the token and register it once per hub and once per push relay. */
    static void ensureRegistered(Context c) {
        if (FirebaseApp.getApps(c).isEmpty()) return; // built without google-services.json
        try { FirebaseMessaging.getInstance().getToken().addOnSuccessListener(t -> register(c, t)); }
        catch (RuntimeException ignored) { }
    }

    private static void register(Context c, String token) {
        if (token == null || token.isEmpty()) return;
        new Thread(() -> {
            SettingsStore store = new SettingsStore(c);
            SettingsStore.Connection hub = store.hubConnection(store.profileId());
            String endpoint = "fcm:" + token;
            if (hub != null) PhoneCommand.hello(c, hub.endpoint, hub.key, endpoint); // so Hermes can reach this phone by name
            if (hub != null && !endpoint.equals(store.fcmRegistered(hub.endpoint))) {
                try { new HubApi(hub.endpoint, hub.key).registerPush(endpoint); store.fcmRegistered(hub.endpoint, endpoint); }
                catch (Exception ignored) { /* retried on the next resume */ }
            }
            String relay = store.relayUrl();
            if (store.relayReady() && !token.equals(store.relayRegistered(relay))) {
                try { PushRelay.register(relay, store.relayKey(), token); store.relayRegistered(relay, token); }
                catch (Exception ignored) { /* retried on the next resume */ }
            }
        }, "fcm-register").start();
    }
}
