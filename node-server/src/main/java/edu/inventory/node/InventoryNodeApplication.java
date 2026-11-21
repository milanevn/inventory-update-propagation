package edu.inventory.node;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.WebApplicationType;

@SpringBootApplication
@ConfigurationPropertiesScan
public class InventoryNodeApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(InventoryNodeApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
