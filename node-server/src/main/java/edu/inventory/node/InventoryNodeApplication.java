package edu.inventory.node;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Node entry point. Non-web application (spring.main.web-application-type=none); clients use gRPC only. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class InventoryNodeApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryNodeApplication.class, args);
    }
}
