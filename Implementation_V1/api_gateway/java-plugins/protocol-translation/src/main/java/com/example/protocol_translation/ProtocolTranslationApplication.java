package com.example.protocol_translation;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication(scanBasePackages = {"com.example.protocol_translation", "org.apache.apisix.plugin.runner"})
public class ProtocolTranslationApplication {

    public static void main(String[] args) {

        new SpringApplicationBuilder(ProtocolTranslationApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }

}
