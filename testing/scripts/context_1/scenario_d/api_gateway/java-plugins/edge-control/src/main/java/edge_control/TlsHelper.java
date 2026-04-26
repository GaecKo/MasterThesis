package edge_control;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * Utility for building SSLContext and SSLSocketFactory from a self-signed certificate file.
 *
 * Loads a single trusted certificate from disk and builds a TrustStore containing only
 * that certificate. This is suitable for POC environments using self-signed certs where
 * no CA chain verification is needed.
 *
 * Used by HttpForgery for HTTPS requests and MqttDeviceAdapter for MQTTS connections.
 */
public class TlsHelper {

    // Utility class, not meant to be instantiated
    private TlsHelper() {}

    // | ================= Public API ================= |

    /**
     * Builds an SSLContext that trusts only the certificate at the given path.
     *
     * TLSv1.3 is enforced as the minimum protocol version to avoid known
     * vulnerabilities in older TLS versions (1.0, 1.1, 1.2).
     *
     * @param certPath Absolute path to the .crt file (PEM or DER format)
     * @return SSLContext configured to trust only the given certificate
     * @throws Exception If the certificate cannot be loaded or the SSLContext cannot be initialised
     */
    public static SSLContext buildSslContext(String certPath) throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(null, buildTrustManagers(certPath), null);
        return sslContext;
    }

    /**
     * Builds an SSLSocketFactory that trusts only the certificate at the given path.
     * Convenience wrapper around buildSslContext, used by Paho MQTT for MQTTS connections.
     *
     * @param certPath Absolute path to the .crt file (PEM or DER format)
     * @return SSLSocketFactory configured to trust only the given certificate
     * @throws Exception If the certificate cannot be loaded or the SSLContext cannot be initialised
     */
    public static SSLSocketFactory buildSslSocketFactory(String certPath) throws Exception {
        return buildSslContext(certPath).getSocketFactory();
    }

    // | ================= Internal ================= |

    /**
     * Loads the certificate at the given path and builds a TrustManager array
     * that trusts only that certificate.
     *
     * A fresh in-memory KeyStore is created for each call, containing only the
     * single provided certificate as the sole trusted entry.
     *
     * @param certPath Absolute path to the .crt file (PEM or DER format)
     * @return Array of TrustManagers trusting only the given certificate
     * @throws Exception If the file cannot be read or the certificate is malformed
     */
    private static TrustManager[] buildTrustManagers(String certPath) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");

        X509Certificate cert;
        try (FileInputStream fis = new FileInputStream(certPath)) {
            cert = (X509Certificate) cf.generateCertificate(fis);
        }

        // Create a fresh in-memory KeyStore containing only this certificate
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("trusted-cert", cert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        return tmf.getTrustManagers();
    }
}