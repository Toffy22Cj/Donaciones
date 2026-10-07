package com.traceability.api.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Registra {@link CurrentActor} y {@link CommandId} para todos los controladores del contexto ({@code api} y {@code app.web}). */
@Configuration
public class ApiWebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentActorArgumentResolver());
        resolvers.add(new CommandIdArgumentResolver());
    }
}
