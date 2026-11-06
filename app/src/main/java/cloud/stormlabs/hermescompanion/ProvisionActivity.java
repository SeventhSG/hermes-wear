package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

/** One-shot ADB provisioning entry point. Requires an explicit endpoint and key; disables itself after storing the encrypted key. */
public class ProvisionActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String endpoint = Keys.clean(getIntent().getStringExtra("endpoint"));
        String key = Keys.clean(getIntent().getStringExtra("key"));
        String session = Keys.clean(getIntent().getStringExtra("session"));
        String essBase = Keys.clean(getIntent().getStringExtra("essbase"));
        String essToken = Keys.clean(getIntent().getStringExtra("esstoken"));
        SettingsStore store = new SettingsStore(this);
        boolean did = false;
        if (key != null && !key.trim().isEmpty() && UrlUtils.isUsableEndpoint(endpoint)) {
            store.endpoint(endpoint);
            store.apiKey(key);
            if (session != null && !session.trim().isEmpty()) store.sessionId(session);
            did = true;
        }
        // The capture lane can be provisioned on its own: a phone that only files captures does not
        // need the gateway key, and demanding both would force typing on the device.
        if (essToken != null && !essToken.trim().isEmpty() && UrlUtils.isUsableEndpoint(essBase)) {
            store.essentialsBase(essBase);
            store.essentialsToken(essToken);
            did = true;
        }
        boolean oneShot = getIntent().getBooleanExtra("once", false);
        if (did && oneShot) {
            // Opt-in only. Disabling this component makes it unusable forever: the shell can never
            // re-enable a component the app disabled, so a test/re-provisioning would need an
            // uninstall. Left enabled, it does nothing unless the extras are present.
            getPackageManager().setComponentEnabledSetting(
                    new ComponentName(this, ProvisionActivity.class),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }
}
