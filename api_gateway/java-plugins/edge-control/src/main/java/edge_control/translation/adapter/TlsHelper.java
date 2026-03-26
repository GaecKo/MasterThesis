package edge_control.translation.adapter;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * Utility for building SSLContext and SSLSocketFactory from a self-signed cert file.
 * Used by both HttpForgery (HTTPS) and MqttDeviceAdapter (MQTTS).
 */
public class TlsHelper {

    private TlsHelper() {}

    /**
     * Builds an SSLContext that trusts only the certificate at the given path.
     * Suitable for self-signed certs where no CA chain verification is needed.
     *
     * @param certPath absolute path to the .crt (PEM or DER) file
     */
    public static SSLContext buildSslContext(String certPath) throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(null, buildTrustManagers(certPath), null);
        return sslContext;
    }

    /**
     * Builds an SSLSocketFactory from the given cert path.
     * Used by Paho MQTT for MQTTS connections.
     */
    public static SSLSocketFactory buildSslSocketFactory(String certPath) throws Exception {
        return buildSslContext(certPath).getSocketFactory();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static TrustManager[] buildTrustManagers(String certPath) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert;
        try (FileInputStream fis = new FileInputStream(certPath)) {
            cert = (X509Certificate) cf.generateCertificate(fis);
        }

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("trusted-cert", cert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        return tmf.getTrustManagers();
    }
}