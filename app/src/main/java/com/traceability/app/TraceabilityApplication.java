package com.traceability.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.traceability")
@EnableMongoRepositories(basePackages = "com.traceability")
@EnableScheduling
public class TraceabilityApplication {

    public static void main(String[] args) {
        SpringApplication.run(TraceabilityApplication.class, args);
    }
}
