package com.aman.connector;

import com.aman.connector.config.ConnectorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ConnectorProperties.class)
public class WooCommerceAgentConnectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(WooCommerceAgentConnectorApplication.class, args);
    }
}
