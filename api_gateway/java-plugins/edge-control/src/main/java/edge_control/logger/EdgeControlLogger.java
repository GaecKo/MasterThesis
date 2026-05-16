package edge_control.logger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EdgeControlLogger {

    private static EdgeControlLogger instance;

    private static final DateTimeFormatter TIMING_FORMATTER =
            DateTimeFormatter.ofPattern("hh:mm:ss.SSSS").withZone(ZoneId.systemDefault());

    private static final String LOG_DIR =
            "/usr/local/apisix/java-plugins/edge-control/";

    private static final Logger logger = LoggerFactory.getLogger(EdgeControlLogger.class);

    private static final String LOG_FILE =
            LOG_DIR + "/logs";

    private final BufferedWriter writer;

    private static boolean enabled = true;

    ExecutorService executor = Executors.newSingleThreadExecutor();

    private EdgeControlLogger() {
        BufferedWriter writer1;
        try {
            File dir = new File(LOG_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            writer1 = new BufferedWriter(new FileWriter(LOG_FILE, true)); // append mode

        } catch (IOException e) {
            logger.debug("Failed to initialize EdgeControlLogger " + e + " / disabling logger...");
            enabled = false;
            writer1 = null;
        }
        writer = writer1;
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
                writer.write(message);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                // fallback...
                e.printStackTrace();
            }
        });
    }

    public void time(String message) {
        Instant now = Instant.now();
        log("[" + TIMING_FORMATTER.format(now) + "] " + message);
    }

    public void info(String message) {
        log("[INFO] " + message);
    }

    public void warn(String message) {
        log("[WARN] " + message);
    }

    public void debug(String message) {
        log("[DEBUG] " + message);
    }

    public void error(String message) {
        log("[ERROR] " + message);
    }
}
