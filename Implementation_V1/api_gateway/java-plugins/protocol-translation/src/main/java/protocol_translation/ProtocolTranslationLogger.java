package protocol_translation;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public final class ProtocolTranslationLogger {

    private static ProtocolTranslationLogger instance;

    private static final String LOG_DIR =
            "/usr/local/apisix/java-plugins/protocol-translation/";

    private static final String LOG_FILE =
            LOG_DIR + "/logs";

    private final BufferedWriter writer;

    private ProtocolTranslationLogger() {
        try {
            File dir = new File(LOG_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            writer = new BufferedWriter(new FileWriter(LOG_FILE, true)); // append mode

        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize ProtocolTranslationLogger", e);
        }
    }

    public static synchronized ProtocolTranslationLogger getInstance() {
        if (instance == null) {
            instance = new ProtocolTranslationLogger();
        }
        return instance;
    }

    // --------------------
    // Simple log methods
    // --------------------

    public synchronized void log(String message) {
        try {
            writer.write(message);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            // Last-resort fallback
            e.printStackTrace();
        }
    }

    public void info(String message) {
        log("[INFO] " + message);
    }

    public void debug(String message) {
        log("[DEBUG] " + message);
    }

    public void error(String message) {
        log("[ERROR] " + message);
    }
}
