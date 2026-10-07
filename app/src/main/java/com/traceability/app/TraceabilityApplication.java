package com.traceability.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

// "identity" lives outside com.traceability; app is the composition root that wires it (ADR-032/D1).
@SpringBootApplication(scanBasePackages = {"com.traceability", "identity"})
@EnableMongoRepositories(basePackages = {"com.traceability", "identity"})
@EnableScheduling
public class TraceabilityApplication {

    public static void main(String[] args) {
        SpringApplication.run(TraceabilityApplication.class, args);
    }
}
