package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Bundle;

/** Invisible trampoline for "Answer" on the incoming-call notification: opens the call overlay (activities may start from a notification tap, services may not). */
public final class IncomingCallActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(IncomingCall.NID);
        String text = getIntent().getStringExtra(IncomingCall.EXTRA_TEXT);
        Bundle args = new Bundle();
        args.putBoolean(HermesVoiceSession.ARG_CALL, true);
        args.putString(HermesVoiceSession.ARG_OPENING, text);
        if (!HermesVoiceInteractionService.show(args, 0)) {
            startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_CHAT, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
    }
}
