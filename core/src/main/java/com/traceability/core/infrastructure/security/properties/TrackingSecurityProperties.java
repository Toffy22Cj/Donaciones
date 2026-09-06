package com.traceability.core.infrastructure.security.properties;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "traceability.security")
@Data
public class TrackingSecurityProperties implements InitializingBean {

    private String trackingCodeSecret;
    private String assetRefSecret;

    @Override
    public void afterPropertiesSet() throws Exception {
        if (!StringUtils.hasText(trackingCodeSecret) || trackingCodeSecret.startsWith("${")) {
            throw new IllegalStateException("trackingCodeSecret must be configured via environment variable TRACKING_CODE_SECRET");
        }
        if (!StringUtils.hasText(assetRefSecret) || assetRefSecret.startsWith("${")) {
            throw new IllegalStateException("assetRefSecret must be configured via environment variable ASSET_REF_SECRET");
        }
    }
}
