package edge_control.translation.adapter;

/**
 * Callback passed to DeviceAdapter.handleRequest().
 * Replaces the plain Runnable to allow the adapter to signal the outcome
 * without throwing exceptions across CompletableFuture thread boundaries.
 */
public interface AdapterCallback {

    /** Called when the adapter has set the response and the chain should continue. */
    void onSuccess();

    /**
     * Called when the device could not be reached.
     * The filter will handle queuing or 502 — the adapter must NOT set a response.
     */
    void onDeviceUnreachable(String reason);
}