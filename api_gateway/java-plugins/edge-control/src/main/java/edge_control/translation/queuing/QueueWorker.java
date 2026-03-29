package edge_control.translation.queuing;

import edge_control.logger.EdgeControlLogger;
import edge_control.translation.queuing.config.DeviceQueueConfig;
import edge_control.database.QueuedRequestRepository;
import edge_control.translation.queuing.model.QueuedRequest;
import edge_control.translation.queuing.synthetic.QueueHttpRequest;
import edge_control.translation.queuing.synthetic.QueueHttpResponse;
import edge_control.translation.adapter.AdapterCallback;
import edge_control.translation.adapter.DeviceAdapter;
import edge_control.translation.adapter.HttpForgery;
import edge_control.translation.registry.DeviceRegistry;
import org.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Retries queued requests for devices on an event-driven schedule.
 * A retry task is scheduled when a request is enqueued, and cancels
 * itself automatically when the device's queue is empty.
 */
public class QueueWorker {

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private final QueuedRequestRepository requestRepo;
    private final QueueRegistry           queueRegistry;
    private final DeviceRegistry          deviceRegistry;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "queue-worker");
                t.setDaemon(true);
                return t;
            });

    // Tracks active retry schedules per device — prevents double-scheduling
    private final ConcurrentHashMap<String, ScheduledFuture<?>> activeSchedules
            = new ConcurrentHashMap<>();

    public QueueWorker(QueuedRequestRepository requestRepo,
                       QueueRegistry queueRegistry,
                       DeviceRegistry deviceRegistry) {
        this.requestRepo   = requestRepo;
        this.queueRegistry = queueRegistry;
        this.deviceRegistry = deviceRegistry;
    }

    // ── Schedule ──────────────────────────────────────────────────────────────

    /**
     * Called by QueueRegistry.enqueue() when a request is added for a device.
     * Schedules a repeating retry task at the device's exact retryInterval.
     * Does nothing if a task is already scheduled for this device.
     */
    public void scheduleRetries(String deviceId, Duration retryInterval) {
        // putIfAbsent semantics — only schedule if not already running
        activeSchedules.computeIfAbsent(deviceId, id -> {
            long intervalMs = retryInterval.toMillis();
            logger.info("Scheduling retries for device " + deviceId
                    + " every " + retryInterval.getSeconds() + "s");

            return scheduler.scheduleWithFixedDelay(
                    () -> retryDevice(deviceId),
                    intervalMs,   // first retry after one interval
                    intervalMs,
                    TimeUnit.MILLISECONDS);
        });
    }

    /**
     * Called by QueueRegistry.remove() when a device is offboarded.
     * Cancels any active retry schedule for that device.
     */
    public void cancelRetries(String deviceId) {
        ScheduledFuture<?> future = activeSchedules.remove(deviceId);
        if (future != null) {
            future.cancel(false);
            logger.info("Retry schedule cancelled (off) for device " + deviceId);
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
        logger.info("QueueWorker shut down");
    }

    // ── Retry logic ───────────────────────────────────────────────────────────

    private void retryDevice(String deviceId) {
        try {
            DeviceQueueConfig queueConfig = queueRegistry.getConfig(deviceId);
            if (queueConfig == null) {
                // Device was offboarded — cancel this task
                cancelRetries(deviceId);
                return;
            }

            List<QueuedRequest> pending = requestRepo.findAllForDevice(deviceId);

            if (pending.isEmpty()) {
                // Queue drained — cancel task, will be rescheduled if needed
                logger.info("Queue empty for device " + deviceId + " — cancelling retry schedule");
                cancelRetries(deviceId);
                return;
            }

            logger.debug("Retrying " + pending.size()
                    + " queued request(s) for device " + deviceId);

            for (QueuedRequest queuedRequest : pending) {
                processRequest(queuedRequest, queueConfig);
            }

        } catch (Exception e) {
            logger.error("Retry error for device " + deviceId + ": " + e.getMessage());
        }
    }

    private void processRequest(QueuedRequest queuedRequest, DeviceQueueConfig queueConfig) {
        String deviceId = queuedRequest.getGatewayDeviceId();

        // Check TTL — drop and notify backend if expired
        Instant expiresAt = queuedRequest.getEnqueuedAt().plus(queueConfig.getMaxTimeToLive());
        if (Instant.now().isAfter(expiresAt)) {
            logger.log("Queued request " + queuedRequest.getId()
                    + " for device " + deviceId + " exceeded TTL — dropping");
            silentDelete(queuedRequest.getId());
            notifyBackend(queuedRequest.getCallbackEndpoint(), queuedRequest.getId(), 504,
                    "{\"error\":\"Request expired in queue — device unreachable within TTL\"}");
            return;
        }

        DeviceAdapter adapter = deviceRegistry.getAdapter(deviceId);
        if (adapter == null) {
            logger.log("No adapter found for device " + deviceId + " — skipping");
            return;
        }

        QueueHttpRequest  syntheticReq  = new QueueHttpRequest(
                queuedRequest.getBody(), queuedRequest.getHeaders());
        QueueHttpResponse syntheticResp = new QueueHttpResponse();

        try {
            adapter.handleRequest(syntheticReq, syntheticResp, new AdapterCallback() {
                @Override
                public void onSuccess() {
                    if (syntheticResp.isSuccess()) {
                        logger.info("Queued request " + queuedRequest.getId()
                                + " successfully delivered to device " + deviceId);
                        notifyBackend(queuedRequest.getCallbackEndpoint(), queuedRequest.getId(),
                                syntheticResp.getStatusCode(), syntheticResp.getBody());
                        silentDelete(queuedRequest.getId());
                    } else {
                        logger.log("Retry for request " + queuedRequest.getId()
                                + " got non-success status " + syntheticResp.getStatusCode()
                                + " — will retry");
                    }
                }

                @Override
                public void onDeviceUnreachable(String reason) {
                    // Still unreachable — leave in queue, task will fire again
                    logger.log("Device " + deviceId + " still unreachable: " + reason);
                }
            });
        } catch (Exception e) {
            logger.error("Unexpected error retrying request " + queuedRequest.getId()
                    + ": " + e.getMessage());
        }
    }

    // ── Backend notification ──────────────────────────────────────────────────

    private void notifyBackend(String callbackUrl, String requestId,
                               int statusCode, String body) {
        if (callbackUrl == null || callbackUrl.isEmpty()) {
            return;
        }

        JSONObject notification = new JSONObject();
        notification.put("queuedRequestId", requestId);
        notification.put("statusCode",      statusCode);
        notification.put("body",            body);
        notification.put("resolvedAt",      Instant.now().toString());

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        HttpForgery.doRequestAsync(
                        "POST",
                        callbackUrl,
                        notification.toString(),
                        headers,
                        Duration.of(5,  ChronoUnit.SECONDS),
                        Duration.of(10, ChronoUnit.SECONDS))
                .thenAccept(result -> logger.debug(
                        "Callback to " + callbackUrl + " — status=" + result.statusCode()))
                .exceptionally(throwable -> {
                    logger.error("Failed to notify backend at " + callbackUrl
                            + ": " + throwable.getMessage());
                    return null;
                });
    }

    private void silentDelete(String requestId) {
        try {
            requestRepo.delete(requestId);
        } catch (Exception e) {
            logger.error("Failed to delete queued request " + requestId + ": " + e.getMessage());
        }
    }
}