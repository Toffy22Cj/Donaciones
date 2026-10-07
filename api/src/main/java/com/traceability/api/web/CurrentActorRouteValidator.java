package com.traceability.api.web;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Skeleton B6-0. */
@Component
public class CurrentActorRouteValidator implements SmartInitializingSingleton {

    public CurrentActorRouteValidator(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
    }

    @Override
    public void afterSingletonsInstantiated() {
    }
}
