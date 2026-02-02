package protocol_translation;

import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.apache.apisix.plugin.runner.filter.PluginFilter;
import org.apache.apisix.plugin.runner.filter.PluginFilterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import protocol_translation.logger.ProtocolTranslationLogger;

/**
 * APISIX plugin filter that handles protocol translation for devices.
 *
 * Responsibilities:
 * - 
 */
@Component
public class OtherFilter implements PluginFilter {

    private static final Logger API_LOGGER =
            LoggerFactory.getLogger(OtherFilter.class);

    private final ProtocolTranslationLogger logger =
            ProtocolTranslationLogger.getInstance();

    /**
     * Initializes the plugin and logs startup messages.
     */
    OtherFilter() {
        logger.info("Other Filter initialized");
        API_LOGGER.warn("OtherFilter is running");
    }

    /**
     * Returns the name of this plugin filter.
     *
     * @return plugin name
     */
    @Override
    public String name() {
        return "OtherFilter";
    }

    /**
     * Main filter method invoked by APISIX.
     * Routes requests to health check, device management, or device adapters.
     *
     * @param request the incoming HTTP request
     * @param response the HTTP response to populate
     * @param chain the APISIX plugin filter chain
     */
    @Override
    public void filter(HttpRequest request,
                       HttpResponse response,
                       PluginFilterChain chain) {

        logger.debug("Incoming request in " + name());
        logger.debug("Path: " + request.getPath());
        logger.debug("Method: " + request.getMethod());
        logger.debug("Source IP: " + request.getSourceIP());

        // Mark request as processed
        request.setHeader("X-Processed-By", "Java-plugins:OtherFilter");
        
        response.setBody("STOPPED AT OTHERFILTER\n");
        response.setStatusCode(200);
        response.setHeader("filter", "otherFilter");

        /*
        logger.debug("Tried to stop response here, filters of chain are: ");
        for (PluginFilter filters : chain.getFilters()) {
            logger.debug(filters.name() + ": ");
            logger.debug("\t" + filters.getClass().getPackageName());
            logger.debug("\t" + filters.getClass().toString());
        }
        */
        // Continue APISIX chain
        chain.filter(request, response);
    }

    /**
     * Indicates that the plugin requires the request body to function.
     *
     * @return true
     */
    @Override
    public Boolean requiredBody() {
        return true;
    }

    /**
     * Indicates that the plugin requires the response body to function.
     *
     * @return true
     */
    @Override
    public Boolean requiredRespBody() {
        return true;
    }
}
