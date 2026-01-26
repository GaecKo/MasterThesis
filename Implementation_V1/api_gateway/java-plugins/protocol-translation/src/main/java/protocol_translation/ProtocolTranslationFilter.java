package protocol_translation;

import java.util.ArrayList;
import java.util.List;
import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.apache.apisix.plugin.runner.filter.PluginFilter;
import org.apache.apisix.plugin.runner.filter.PluginFilterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ProtocolTranslationFilter implements PluginFilter {
    private final Logger API_logger = LoggerFactory.getLogger(ProtocolTranslationFilter.class);

    private final ProtocolTranslationLogger logger = ProtocolTranslationLogger.getInstance();

    ProtocolTranslationFilter() {
        logger.info("Protocol Translation initialized...");
        API_logger.warn("ProtocolTranslation is running...");
    }

    @Override
    public String name() {
        return "ProtocolTranslation";
    }

    @Override
    public void filter(HttpRequest request, HttpResponse response, PluginFilterChain chain) {
        logger.debug("Request received:");
        logger.debug("Path: " + request.getPath());
        logger.debug("Method: " + request.getMethod());
        logger.debug("Body: " + request.getBody());
        logger.debug("Source IP: " + request.getSourceIP());

        request.setHeader("X-Processed-By", "Java-plugins:ProtocolTranslation");

        // use response.exit(error_code, "reason") to stop the packet (won't be forwarded)

        chain.filter(request, response);
    }

    /**
     * If you need to fetch some Nginx variables in the current plugin, you will need to declare them in this function.
     * @return a list of Nginx variables that need to be called in this plugin
     */
    @Override
    public List<String> requiredVars() {
        List<String> vars = new ArrayList<>();
        vars.add("remote_addr");
        vars.add("server_port");
        return vars;
    }

    /**
     * If you need to fetch request body in the current plugin, you will need to return true in this function.
     */
    @Override
    public Boolean requiredBody() {
        return true;
    }
}