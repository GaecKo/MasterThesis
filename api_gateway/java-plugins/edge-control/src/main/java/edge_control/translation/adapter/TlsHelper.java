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
        // Load the certificate
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert;
        try (FileInputStream fis = new FileInputStream(certPath)) {
            cert = (X509Certificate) cf.generateCertificate(fis);
        }

        // Create a KeyStore containing our trusted cert
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("self-signed", cert);

        // Build a TrustManagerFactory from that KeyStore
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        // Build and init the SSLContext
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);
        return sslContext;
    }

    /**
     * Builds an SSLSocketFactory from the given cert path.
     * Used by Paho MQTT for MQTTS connections.
     */
    public static SSLSocketFactory buildSslSocketFactory(String certPath) throws Exception {
        return buildSslContext(certPath).getSocketFactory();
    }
}