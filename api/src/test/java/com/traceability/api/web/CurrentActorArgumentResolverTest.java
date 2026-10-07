package com.traceability.api.web;

import com.traceability.api.auth.jwt.JwtAuthFilter;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.core.domain.event.HumanActor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Plan B6-0 §2.1, tests 1 y 2 (parte MockMvc). La parte contra Tomcat real está en {@code app}. */
@ExtendWith(OutputCaptureExtension.class)
class CurrentActorArgumentResolverTest {

    static final AuthorizationPrincipal PRINCIPAL =
            new AuthorizationPrincipal("acc-42", "org-7", Set.of(AuthorizationRole.ADMINISTRATOR), null);

    /** Solo de test. */
    @RestController
    static class ActorController {
        final AtomicInteger calls = new AtomicInteger();

        @GetMapping("/t/human")
        String human(@CurrentActor HumanActor actor) {
            calls.incrementAndGet();
            return "human:" + actor.accountId();
        }

        @GetMapping("/t/principal")
        String principal(@CurrentActor AuthorizationPrincipal principal) {
            calls.incrementAndGet();
            return "principal:" + principal.accountId() + "/" + principal.organizationId() + "/" + principal.roles();
        }

        @GetMapping("/t/optional")
        String optional(@CurrentActor Optional<HumanActor> actor) {
            calls.incrementAndGet();
            return actor.map(a -> "some:" + a.accountId()).orElse("none");
        }

        @GetMapping("/t/optional-principal")
        String optionalPrincipal(@CurrentActor Optional<AuthorizationPrincipal> principal) {
            calls.incrementAndGet();
            return principal.map(p -> "some:" + p.organizationId()).orElse("none");
        }
    }

    private final ActorController controller = new ActorController();
    private final MockMvc mvc = WebTestSupport.mvc(controller);

    private String body(String path, AuthorizationPrincipal principal) throws Exception {
        var request = get(path);
        if (principal != null) request.requestAttr(JwtAuthFilter.PRINCIPAL_ATTRIBUTE, principal);
        return mvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    @Test
    void humanActor_receivesTheAccountIdOfThePrincipal() throws Exception {
        assertThat(body("/t/human", PRINCIPAL)).isEqualTo("human:acc-42");
    }

    @Test
    void authorizationPrincipal_receivesTheWholePrincipal() throws Exception {
        assertThat(body("/t/principal", PRINCIPAL)).isEqualTo("principal:acc-42/org-7/[ADMINISTRATOR]");
    }

    @Test
    void optionalActor_isEmptyWithoutPrincipal_andFullWithIt() throws Exception {
        assertThat(body("/t/optional", null)).isEqualTo("none");
        assertThat(body("/t/optional", PRINCIPAL)).isEqualTo("some:acc-42");
        assertThat(body("/t/optional-principal", null)).isEqualTo("none");
        assertThat(body("/t/optional-principal", PRINCIPAL)).isEqualTo("some:org-7");
    }

    @Test
    void requiredActorWithoutPrincipal_is500_withErrorLog_andTheHandlerNeverRuns(CapturedOutput output) throws Exception {
        MvcResult result = mvc.perform(get("/t/human")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(controller.calls.get()).isZero();
        assertThat(output.getOut()).contains("ERROR").contains(MissingAuthenticatedActorException.class.getName());
    }

    @Test
    void anAttributeOfAnotherType_isNotAPrincipal() throws Exception {
        MvcResult result = mvc.perform(get("/t/human").requestAttr(JwtAuthFilter.PRINCIPAL_ATTRIBUTE, "acc-42")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(controller.calls.get()).isZero();
    }
}
