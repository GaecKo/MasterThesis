package edge_control.auth.tokens;

/**
 * Token lookup result containing the security type and decrypted token.
 */
public record TokenResponse(String type, String decryptedToken) {}

