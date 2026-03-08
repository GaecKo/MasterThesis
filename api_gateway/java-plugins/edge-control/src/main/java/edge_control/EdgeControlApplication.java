package edge_control;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import edge_control.logger.EdgeControlLogger;

@SpringBootApplication(scanBasePackages = {"edge_control", "org.apache.apisix.plugin.runner"})
public class EdgeControlApplication {

    final static EdgeControlLogger logger = EdgeControlLogger.getInstance();

    public static void main(String[] args) {
        logger.info("EdgeControlApplication is starting ...");
        // logger.disable();
        new SpringApplicationBuilder(EdgeControlApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
