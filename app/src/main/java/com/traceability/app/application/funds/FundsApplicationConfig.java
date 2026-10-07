package com.traceability.app.application.funds;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FundsApplicationProperties.class)
public class FundsApplicationConfig {
}
