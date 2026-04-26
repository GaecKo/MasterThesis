package edge_control.filters;

import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.apache.apisix.plugin.runner.filter.PluginFilter;
import org.apache.apisix.plugin.runner.filter.PluginFilterChain;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SimpleFilter implements PluginFilter {

    @Override
    public String name() {
        return "SimpleFilter";
    }

    @Override
    public void filter(HttpRequest request,
                       HttpResponse response,
                       PluginFilterChain chain) {
        
        String body = request.getBody();

        chain.filter(request, response);
    }

    @Override
    public Boolean requiredBody() {
        return true;
    }
}