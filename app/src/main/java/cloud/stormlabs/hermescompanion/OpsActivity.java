package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Old Homelab entry point: host and services now live on the System screen. */
public final class OpsActivity extends Activity {
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        startActivity(new Intent(getIntent()).setClass(this, StatusActivity.class));
        finish();
    }
}
