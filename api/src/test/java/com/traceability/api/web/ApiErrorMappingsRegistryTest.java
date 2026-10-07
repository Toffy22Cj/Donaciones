package com.traceability.api.web;

import com.traceability.core.application.authorization.CrossOrganizationAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan B6-0, test 10 (Q-B60-4): si dos módulos declaran la misma excepción, la aplicación no arranca. */
class ApiErrorMappingsRegistryTest {

    @Configuration
    static class OtherModule {
        @Bean
        ApiErrorMappings otherModuleMappings() {
            return new ApiErrorMappings() {
                @Override
                public String module() {
                    return "other-module";
                }

                @Override
                public List<ApiErrorMapping> mappings() {
                    return List.of(new ApiErrorMapping(CrossOrganizationAccessException.class, HttpStatus.NOT_FOUND, null));
                }
            };
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ApiBaseErrorMappings.class, CoreApiErrorMappings.class, ApiExceptionHandler.class);

    @Test
    void theBaseModules_start() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(ApiExceptionHandler.class));
    }

    @Test
    void twoModulesMappingTheSameException_preventTheStart_namingBothModulesAndTheException() {
        runner.withUserConfiguration(OtherModule.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause()
                    .hasMessageContaining(CrossOrganizationAccessException.class.getName())
                    .hasMessageContaining("core")
                    .hasMessageContaining("other-module");
        });
    }

    @Test
    void theSameExceptionTwiceInOneModule_alsoPreventsTheStart() {
        ApiErrorMappings twice = new ApiErrorMappings() {
            @Override
            public String module() {
                return "twice";
            }

            @Override
            public List<ApiErrorMapping> mappings() {
                return List.of(new ApiErrorMapping(IllegalStateException.class, HttpStatus.CONFLICT, null),
                        new ApiErrorMapping(IllegalStateException.class, HttpStatus.CONFLICT, null));
            }
        };
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ApiExceptionHandler(List.of(twice)))
                .hasMessageContaining(IllegalStateException.class.getName());
    }
}
