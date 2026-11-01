package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.app.Fragment;
import android.app.KeyguardManager;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
import java.util.concurrent.atomic.AtomicBoolean;

/** A device-authentication gate, with a visible confirmation when authentication is unavailable. */
final class Confirm {
    private Confirm() {}

    static void strong(Activity activity, String title, Runnable onOk) {
        strong(activity, title, onOk, () -> {});
    }

    static void strong(Activity activity, String title, Runnable onOk, Runnable onCancel) {
        strong(activity, title, "Allow this action?", onOk, onCancel);
    }

    static void strong(Activity activity, String title, String detail, Runnable onOk, Runnable onCancel) {
        AtomicBoolean finished = new AtomicBoolean();
        Runnable accept = () -> { if (finished.compareAndSet(false, true)) onOk.run(); };
        Runnable decline = () -> { if (finished.compareAndSet(false, true)) onCancel.run(); };
        if (Build.VERSION.SDK_INT < 28) { dialog(activity, title, detail, accept, decline); return; }
        KeyguardManager keyguard = (KeyguardManager) activity.getSystemService(Activity.KEYGUARD_SERVICE);
        boolean credential = keyguard != null && keyguard.isDeviceSecure();
        boolean biometric;
        if (Build.VERSION.SDK_INT >= 29) {
            BiometricManager manager = activity.getSystemService(BiometricManager.class);
            biometric = manager != null && manager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
        } else {
            FingerprintManager manager = (FingerprintManager) activity.getSystemService(Activity.FINGERPRINT_SERVICE);
            biometric = manager != null && manager.isHardwareDetected() && manager.hasEnrolledFingerprints();
        }
        if (!biometric && Build.VERSION.SDK_INT == 28 && credential) {
            Intent intent = keyguard.createConfirmDeviceCredentialIntent(title, detail);
            if (intent != null) {
                try {
                    CredentialGate gate = new CredentialGate();
                    gate.intent = intent;
                    gate.accept = accept;
                    gate.decline = decline;
                    activity.getFragmentManager().beginTransaction().add(gate, "credential-confirm").commit();
                    return;
                } catch (RuntimeException unavailable) { /* use the visible confirmation below */ }
            }
        }
        if (!biometric && !credential) {
            dialog(activity, title, detail, accept, decline);
            return;
        }
        if (!biometric && Build.VERSION.SDK_INT == 28) { dialog(activity, title, detail, accept, decline); return; }
        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity).setTitle(title).setDescription(detail);
        if (Build.VERSION.SDK_INT >= 29 && credential) builder.setDeviceCredentialAllowed(true);
        else builder.setNegativeButton("Cancel", activity.getMainExecutor(), (d, which) -> decline.run());
        try {
            builder.build().authenticate(new CancellationSignal(), activity.getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) { accept.run(); }
                        @Override public void onAuthenticationError(int code, CharSequence message) { decline.run(); }
                    });
        } catch (RuntimeException unavailable) {
            dialog(activity, title, detail, accept, decline);
        }
    }

    private static void dialog(Activity activity, String title, String detail, Runnable onOk, Runnable onCancel) {
        // A sheet is never shown on a finished activity; decline so the caller isn't left waiting.
        if (activity.isFinishing() || activity.isDestroyed()) { onCancel.run(); return; }
        Sheets.confirm(activity, title, detail, "Allow", false, onOk, onCancel);
    }

    public static final class CredentialGate extends Fragment {
        Intent intent;
        Runnable accept, decline;

        @Override public void onResume() {
            super.onResume();
            if (intent != null) {
                Intent pending = intent;
                intent = null;
                startActivityForResult(pending, 1);
            }
        }

        @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode == 1) {
                if (resultCode == Activity.RESULT_OK && accept != null) accept.run();
                else if (decline != null) decline.run();
                getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
            }
        }
    }
}
