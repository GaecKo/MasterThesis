package edge_control.filters;

import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.apache.apisix.plugin.runner.filter.PluginFilter;
import org.apache.apisix.plugin.runner.filter.PluginFilterChain;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import edge_control.EdgeControlApplication;

@Component
public class SimpleFilter implements PluginFilter {

    private static final Logger logger = LoggerFactory.getLogger(SimpleFilter.class);

    SimpleFilter() {
        logger.warn("Simple Filter running...");
    }

    @Override
    public String name() {
        return "SimpleFilter";
    }

    @Override
    public void filter(HttpRequest request,
                       HttpResponse response,
                       PluginFilterChain chain) {
        
        String body = request.getBody();
        logger.warn("Packet received in Simple Filter, path: " + request.getPath());

        chain.filter(request, response);
    }

    @Override
    public Boolean requiredBody() {
        return true;
    }
}