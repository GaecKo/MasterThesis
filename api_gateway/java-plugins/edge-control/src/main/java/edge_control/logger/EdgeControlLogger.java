package edge_control.logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.random.RandomGenerator;

public final class EdgeControlLogger {

    private static EdgeControlLogger instance;

    private static final String LOG_DIR =
            "/usr/local/apisix/java-plugins/edge-control/";

    private static final String LOG_FILE =
            LOG_DIR + "/logs";

    private final BufferedWriter writer;

    private static boolean enabled = true;

    ExecutorService executor = Executors.newSingleThreadExecutor();

    private EdgeControlLogger() {
        try {
            File dir = new File(LOG_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            writer = new BufferedWriter(new FileWriter(LOG_FILE, true)); // append mode

        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize EdgeControlLogger", e);
        }
    }

    public static void disable() {
        enabled =  false;
    }

    public static void enable() {
        enabled =  true;
    }

    public static synchronized EdgeControlLogger getInstance() {
        if (instance == null) {
            instance = new EdgeControlLogger();
        }
        return instance;
    }

    // --------------------
    // Simple log methods
    // --------------------

    public synchronized void log(String message) {
        if (!enabled) return;
        executor.submit(() -> {
            try {
                // Random ran = new Random();
                // int r = ran.nextInt(50) + 1;
                // writer.write(".".repeat(r));
                writer.write(message);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                // Last-resort fallback
                e.printStackTrace();
            }
        });
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
