package com.traceability.api.web;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.core.domain.event.HumanActor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan B6-0, test 3: un actor no {@code Optional} en una ruta pública o de JWT opcional impide el arranque. */
class CurrentActorRouteValidatorTest {

    static final String OPTIONAL_JWT_ROUTE = "/api/v1/public/campaigns/{publicCode}/donation-intents";

    @RestController
    static class RequiredActorOnOptionalJwtRoute {
        @PostMapping(OPTIONAL_JWT_ROUTE)
        String create(@CurrentActor HumanActor actor) {
            return "x";
        }
    }

    @RestController
    static class RequiredPrincipalOnPublicRoute {
        @GetMapping("/api/v1/public/campaigns/{publicCode}")
        String get(@CurrentActor AuthorizationPrincipal principal) {
            return "x";
        }
    }

    @RestController
    static class OptionalActorOnOptionalJwtRoute {
        @PostMapping(OPTIONAL_JWT_ROUTE)
        String create(@CurrentActor Optional<HumanActor> actor) {
            return "x";
        }
    }

    @RestController
    static class RequiredActorOnProtectedRoute {
        @PostMapping("/api/v1/organizations/{orgId}/campaigns")
        String create(@CurrentActor HumanActor actor) {
            return "x";
        }
    }

    @RestController
    static class UnsupportedActorType {
        @PostMapping("/api/v1/organizations/{orgId}/other")
        String create(@CurrentActor String accountId) {
            return "x";
        }
    }

    @RestController
    static class CommandIdNotAString {
        @PostMapping("/api/v1/organizations/{orgId}/commands")
        String create(@CommandId java.util.UUID commandId) {
            return "x";
        }
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class))
            .withUserConfiguration(ApiWebConfig.class, CurrentActorRouteValidator.class);

    @Test
    void requiredActorOnAnOptionalJwtRoute_preventsTheStart() {
        runner.withUserConfiguration(RequiredActorOnOptionalJwtRoute.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .hasStackTraceContaining("RequiredActorOnOptionalJwtRoute")
                    .hasStackTraceContaining("must be Optional");
        });
    }

    @Test
    void requiredPrincipalOnAPublicRoute_preventsTheStart() {
        runner.withUserConfiguration(RequiredPrincipalOnPublicRoute.class).run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void unsupportedParameterType_preventsTheStart() {
        runner.withUserConfiguration(UnsupportedActorType.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("UnsupportedActorType");
        });
    }

    @Test
    void commandIdThatIsNotAString_preventsTheStart() {
        runner.withUserConfiguration(CommandIdNotAString.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("@CommandId must be a String");
        });
    }

    @Test
    void optionalActorOnAnOptionalJwtRoute_andRequiredActorOnAProtectedRoute_start() {
        runner.withUserConfiguration(OptionalActorOnOptionalJwtRoute.class, RequiredActorOnProtectedRoute.class)
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
