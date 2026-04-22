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
 * A retry task is scheduled when a request is enqueued and cancels itself
 * automatically when the device's queue is drained.
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

    // Tracks active retry schedules per device to prevent double-scheduling
    private final ConcurrentHashMap<String, ScheduledFuture<?>> activeSchedules =
            new ConcurrentHashMap<>();

    /**
     * @param requestRepo    Repository for loading and deleting queued requests
     * @param queueRegistry  Used to look up queue config and detect offboarded devices
     * @param deviceRegistry Used to retrieve the active adapter for each device
     */
    public QueueWorker(QueuedRequestRepository requestRepo,
                       QueueRegistry queueRegistry,
                       DeviceRegistry deviceRegistry) {
        this.requestRepo    = requestRepo;
        this.queueRegistry  = queueRegistry;
        this.deviceRegistry = deviceRegistry;
    }

    // | ================= Scheduling ================= |

    /**
     * Schedules a repeating retry task for the given device.
     * Uses computeIfAbsent so a task is only created if one is not already running.
     * Called by QueueRegistry.enqueue() when a request is added.
     *
     * @param deviceId      Device to schedule retries for
     * @param retryInterval Interval between retry attempts
     */
    public void scheduleRetries(String deviceId, Duration retryInterval) {
        activeSchedules.computeIfAbsent(deviceId, id -> {
            long intervalMs = retryInterval.toMillis();
            logger.info("Scheduling retries for device " + deviceId
                    + " every " + retryInterval.getSeconds() + "s");
            // First retry fires after one full interval, not immediately
            return scheduler.scheduleWithFixedDelay(
                    () -> retryDevice(deviceId),
                    intervalMs,
                    intervalMs,
                    TimeUnit.MILLISECONDS);
        });
    }

    /**
     * Cancels the active retry schedule for the given device.
     * Called by QueueRegistry.remove() when a device is offboarded.
     *
     * @param deviceId Device whose retry schedule should be cancelled
     */
    public void cancelRetries(String deviceId) {
        ScheduledFuture<?> future = activeSchedules.remove(deviceId);
        if (future != null) {
            future.cancel(false);
            logger.info("Retry schedule cancelled for device " + deviceId);
        }
    }

    /**
     * Shuts down the scheduler, interrupting any in-progress retry tasks.
     */
    public void shutdown() {
        scheduler.shutdownNow();
        logger.info("QueueWorker shut down");
    }

    // | ================= Retry logic ================= |

    /**
     * Fired on each scheduler tick for a device.
     * Loads all pending requests and attempts to deliver each one.
     * Cancels itself if the device has been offboarded or the queue is empty.
     *
     * @param deviceId Device to retry requests for
     */
    private void retryDevice(String deviceId) {
        try {
            DeviceQueueConfig queueConfig = queueRegistry.getConfig(deviceId);
            if (queueConfig == null) {
                // Device was offboarded since this task was scheduled
                cancelRetries(deviceId);
                return;
            }

            List<QueuedRequest> pending = requestRepo.findAllForDevice(deviceId);
            if (pending.isEmpty()) {
                // Queue fully drained — cancel and let enqueue() reschedule if needed
                logger.info("Queue empty for device " + deviceId + " — cancelling retry schedule");
                cancelRetries(deviceId);
                return;
            }

            logger.debug("Retrying " + pending.size() + " queued request(s) for device " + deviceId);

            for (QueuedRequest queuedRequest : pending) {
                processRequest(queuedRequest, queueConfig);
            }

        } catch (Exception e) {
            logger.error("Retry error for device " + deviceId + ": " + e.getMessage());
        }
    }

    /**
     * Attempts to deliver a single queued request to its device.
     * If the TTL has expired, notifies the backend and drops the request.
     * If the device is still unreachable, leaves the request in the queue.
     *
     * @param queuedRequest The request to deliver
     * @param queueConfig   Queue config for the device (TTL, callback URL, etc.)
     */
    private void processRequest(QueuedRequest queuedRequest, DeviceQueueConfig queueConfig) {
        String deviceId = queuedRequest.getGatewayDeviceId();

        // Drop requests that have exceeded their time-to-live
        Instant expiresAt = queuedRequest.getEnqueuedAt().plus(queueConfig.getMaxTimeToLive());
        if (Instant.now().isAfter(expiresAt)) {
            logger.warn("Queued request " + queuedRequest.getId()
                    + " for device " + deviceId + " exceeded TTL — dropping");
            silentDelete(queuedRequest.getId());
            notifyBackend(queuedRequest.getCallbackEndpoint(), queuedRequest.getId(), 504,
                    "{\"error\":\"Request expired in queue — device unreachable within TTL\"}");
            return;
        }

        DeviceAdapter adapter = deviceRegistry.getAdapter(deviceId);
        if (adapter == null) {
            logger.info("No adapter found for device " + deviceId + " — skipping retry");
            return;
        }

        // Build synthetic request/response objects so the adapter can be called
        // without a real APISIX HttpRequest or HttpResponse
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
                        // Device responded but with a non-2xx status — leave in queue and retry
                        logger.warn("Retry for request " + queuedRequest.getId()
                                + " got non-success status " + syntheticResp.getStatusCode()
                                + " — will retry");
                    }
                }

                @Override
                public void onDeviceUnreachable(String reason) {
                    // Leave the request in the queue; the next tick will retry
                    logger.warn("Device " + deviceId + " still unreachable: " + reason);
                }
            });
        } catch (Exception e) {
            logger.error("Unexpected error retrying request " + queuedRequest.getId()
                    + ": " + e.getMessage());
        }
    }

    // | ================= Backend notification ================= |

    /**
     * POSTs the result of a queued request to the backend's callback URL.
     * Fire-and-forget — failures are logged but do not affect queue state.
     *
     * @param callbackUrl URL to POST the result to
     * @param requestId   ID of the queued request being resolved
     * @param statusCode  HTTP status received from the device
     * @param body        Response body received from the device
     */
    private void notifyBackend(String callbackUrl, String requestId,
                               int statusCode, String body) {
        if (callbackUrl == null || callbackUrl.isEmpty()) return;

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
                        "Callback to " + callbackUrl + " - status=" + result.statusCode()))
                .exceptionally(throwable -> {
                    logger.error("Failed to notify backend at " + callbackUrl
                            + ": " + throwable.getMessage());
                    return null;
                });
    }

    /**
     * Deletes a queued request from the repository, logging but swallowing any error
     * to avoid disrupting the retry flow.
     *
     * @param requestId ID of the request to delete
     */
    private void silentDelete(String requestId) {
        try {
            requestRepo.delete(requestId);
        } catch (Exception e) {
            logger.error("Failed to delete queued request " + requestId + ": " + e.getMessage());
        }
    }
}