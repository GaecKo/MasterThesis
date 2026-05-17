package edge_control;

import edge_control.auth.tokens.GatewayTokensCrypto;
import edge_control.exceptions.EdgeControlException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class GatewayTokensCryptoTest {

    private String previousTokenKeyPath;
    private Path keyFile;

    @BeforeEach
    void setup() throws Exception {
        previousTokenKeyPath = System.getProperty("TOKEN_KEY_PATH");
        keyFile = createKeyFile(randomKeyBytes(32));
        System.setProperty("TOKEN_KEY_PATH", keyFile.toString());
    }

    @AfterEach
    void teardown() {
        if (previousTokenKeyPath == null) {
            System.clearProperty("TOKEN_KEY_PATH");
        } else {
            System.setProperty("TOKEN_KEY_PATH", previousTokenKeyPath);
        }
    }

    @Test
    void encryptThenDecryptRoundTrip() throws Exception {
        GatewayTokensCrypto crypto = new GatewayTokensCrypto();

        String plaintext = "token-123";
        String encrypted = crypto.encrypt(plaintext);
        String decrypted = crypto.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(plaintext);
        assertThat(encrypted).isNotEqualTo(plaintext);
    }

    @Test
    void decryptRejectsTamperedCiphertext() throws Exception {
        GatewayTokensCrypto crypto = new GatewayTokensCrypto();

        String encrypted = crypto.encrypt("secret");
        byte[] tampered = Base64.getDecoder().decode(encrypted);
        tampered[tampered.length - 1] ^= 0xFF;

        String tamperedBase64 = Base64.getEncoder().encodeToString(tampered);

        assertThatThrownBy(() -> crypto.decrypt(tamperedBase64))
                .isInstanceOf(EdgeControlException.class)
                .hasMessageContaining("Failed to decrypt token");
    }

    @Test
    void constructorRejectsInvalidBase64Key() throws Exception {
        Path badKeyFile = Files.createTempFile("bad-token-key-", ".key");
        Files.writeString(badKeyFile, "not-base64");
        System.setProperty("TOKEN_KEY_PATH", badKeyFile.toString());

        assertThatThrownBy(GatewayTokensCrypto::new)
                .isInstanceOf(EdgeControlException.class)
                .hasMessageContaining("not valid Base64");
    }

    @Test
    void constructorRejectsWrongKeyLength() throws Exception {
        Path badKeyFile = createKeyFile(randomKeyBytes(16));
        System.setProperty("TOKEN_KEY_PATH", badKeyFile.toString());

        assertThatThrownBy(GatewayTokensCrypto::new)
                .isInstanceOf(EdgeControlException.class)
                .hasMessageContaining("must be 32 bytes");
    }

    private static byte[] randomKeyBytes(int size) {
        byte[] keyBytes = new byte[size];
        new SecureRandom().nextBytes(keyBytes);
        return keyBytes;
    }

    private static Path createKeyFile(byte[] keyBytes) throws Exception {
        Path keyFile = Files.createTempFile("test-token-key-", ".key");
        String base64 = Base64.getEncoder().encodeToString(keyBytes);
        Files.writeString(keyFile, base64);
        keyFile.toFile().deleteOnExit();
        return keyFile;
    }
}


