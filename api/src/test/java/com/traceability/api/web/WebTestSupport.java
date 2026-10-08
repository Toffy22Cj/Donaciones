package com.traceability.api.web;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

/** MockMvc standalone con los resolvers y el manejador de B6-0, tal como los registra {@link ApiWebConfig}. */
final class WebTestSupport {

    private WebTestSupport() {}

    static ApiExceptionHandler handler() {
        return new ApiExceptionHandler(List.of(new ApiBaseErrorMappings(), new CoreApiErrorMappings()));
    }

    static MockMvc mvc(Object... controllers) {
        return MockMvcBuilders.standaloneSetup(controllers)
                .setCustomArgumentResolvers(new CurrentActorArgumentResolver(), new CommandIdArgumentResolver())
                .setControllerAdvice(handler())
                .build();
    }
}
