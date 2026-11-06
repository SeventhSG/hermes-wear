package cloud.stormlabs.hermescompanion;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * This phone's mutual-TLS identity: a client certificate from the hub's device CA ({@code GET /pair/cert}, handed out
 * only over Tailscale to the owner's own devices). The private key is imported into the Android Keystore. Once it is
 * installed every HTTPS connection can present it (the public proxy requires it), the voice ports are reached over
 * TLS on the LAN, and {@link Reach} stops sending anything over plain HTTP except across Tailscale.
 */
final class DeviceCert {
    private DeviceCert() {}

    private static final String ALIAS = "hermes-device";
    private static final SSLSocketFactory SYSTEM = HttpsURLConnection.getDefaultSSLSocketFactory();
    /** Trusts only the device CA: the voice ports' certificate names the server's LAN and Tailscale IPs. */
    private static volatile SSLSocketFactory voice;
    private static volatile long expires;

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("device_cert", Context.MODE_PRIVATE); }

    static boolean present() { return voice != null; }
    static long expiresMs() { return expires * 1000L; }

    /** No certificate yet, or less than 30 days left: worth asking the hub (over Tailscale) for a fresh one. */
    static boolean needsRenewal() { return voice == null || expiresMs() - System.currentTimeMillis() < 30L * 86_400_000L; }

    /** Saves a {@code /pair/cert} answer and starts using it. */
    static synchronized void install(Context c, JSONObject o) throws Exception {
        X509Certificate cert = x509(o.getString("cert"));
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pem(o.getString("key"))));
        SharedPreferences.Editor e = prefs(c).edit().putString("cert", o.getString("cert")).putString("ca", o.getString("ca")).putLong("expires", o.optLong("expires"));
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
            ks.setEntry(ALIAS, new KeyStore.PrivateKeyEntry(key, new Certificate[]{cert}), new KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512).build());
            e.remove("key");
        } catch (Exception keystoreRefused) {
            e.putString("key", o.getString("key")); // app-private storage when this device's keystore can't take an imported EC key
        }
        e.commit();
        load(c);
    }

    /** Reads the stored certificate (if any) and makes it the default for HTTPS. Call at app start. */
    static synchronized void load(Context c) {
        SharedPreferences p = prefs(c);
        try {
            String certPem = p.getString("cert", ""), caPem = p.getString("ca", "");
            if (certPem.isEmpty() || caPem.isEmpty()) throw new IllegalStateException("none");
            X509Certificate cert = x509(certPem), ca = x509(caPem);
            PrivateKey key;
            String soft = p.getString("key", "");
            if (!soft.isEmpty()) key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pem(soft)));
            else { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); key = (PrivateKey) ks.getKey(ALIAS, null); }
            if (key == null) throw new IllegalStateException("key missing");
            KeyManager[] km = {new ClientKey(key, new X509Certificate[]{cert}, ca)};
            SSLContext pub = SSLContext.getInstance("TLS"); pub.init(km, null, null);
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null); trust.setCertificateEntry("ca", ca);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tmf.init(trust);
            SSLContext lan = SSLContext.getInstance("TLS"); lan.init(km, tmf.getTrustManagers(), null);
            HttpsURLConnection.setDefaultSSLSocketFactory(pub.getSocketFactory());
            voice = lan.getSocketFactory();
            expires = p.getLong("expires", 0);
        } catch (Exception e) {
            HttpsURLConnection.setDefaultSSLSocketFactory(SYSTEM);
            voice = null; expires = 0;
        }
        Reach.secureOnly = voice != null;
    }

    /** TLS to a voice port with this phone's certificate; the server must prove it holds the device CA's certificate for {@code host}. */
    static Socket voiceSocket(String host, int port, int timeoutMs) throws java.io.IOException {
        SSLSocketFactory f = voice;
        if (f == null) throw new java.net.ConnectException("No device certificate for an encrypted voice connection");
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(host, port), timeoutMs);
            SSLSocket s = (SSLSocket) f.createSocket(raw, host, port, true);
            SSLParameters params = s.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS"); s.setSSLParameters(params);
            s.setSoTimeout(timeoutMs); s.startHandshake(); s.setSoTimeout(0);
            return s;
        } catch (java.io.IOException e) { try { raw.close(); } catch (Exception ignored) {} throw e; }
    }

    private static X509Certificate x509(String pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    }

    private static byte[] pem(String pem) { return Base64.decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""), Base64.DEFAULT); }

    /** Offers the certificate only to servers that ask for one from the device CA. */
    private static final class ClientKey extends X509ExtendedKeyManager {
        private final PrivateKey key; private final X509Certificate[] chain; private final Principal issuer;
        ClientKey(PrivateKey key, X509Certificate[] chain, X509Certificate ca) { this.key = key; this.chain = chain; this.issuer = ca.getSubjectX500Principal(); }
        private String pick(Principal[] issuers) {
            if (issuers == null || issuers.length == 0) return ALIAS;
            for (Principal p : issuers) if (issuer.equals(p)) return ALIAS;
            return null;
        }
        @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) { return pick(issuers); }
        @Override public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) { return pick(issuers); }
        @Override public String[] getClientAliases(String keyType, Principal[] issuers) { String a = pick(issuers); return a == null ? null : new String[]{a}; }
        @Override public X509Certificate[] getCertificateChain(String alias) { return ALIAS.equals(alias) ? chain : null; }
        @Override public PrivateKey getPrivateKey(String alias) { return ALIAS.equals(alias) ? key : null; }
        @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return null; }
        @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) { return null; }
    }
}
