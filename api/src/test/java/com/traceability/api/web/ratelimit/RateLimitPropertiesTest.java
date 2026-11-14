package com.traceability.api.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** ADR-053 D4: sin cuota de algún grupo, o con una clave desconocida, la aplicación no arranca. */
class RateLimitPropertiesTest {

    private static RateLimitProperties.Quota quota(int limit, Duration window) {
        RateLimitProperties.Quota q = new RateLimitProperties.Quota();
        q.setLimit(limit);
        q.setWindow(window);
        return q;
    }

    private static RateLimitProperties complete() {
        RateLimitProperties p = new RateLimitProperties();
        for (RateLimitGroup g : RateLimitGroup.values()) {
            p.getGroups().put(g.configKey(), quota(5, Duration.ofMinutes(1)));
        }
        p.setLoginEmail(quota(5, Duration.ofMinutes(15)));
        return p;
    }

    @Test
    void completeConfigurationIsAccepted() {
        assertThatCode(() -> complete().validate()).doesNotThrowAnyException();
    }

    @Test
    void everyGroupNeedsItsQuota() {
        for (RateLimitGroup g : RateLimitGroup.values()) {
            RateLimitProperties p = complete();
            p.getGroups().remove(g.configKey());
            assertThatThrownBy(p::validate).isInstanceOf(RateLimitConfigurationException.class)
                    .hasMessageContaining(g.configKey());
        }
    }

    @Test
    void unknownGroupKeyIsATypoAndFails() {
        RateLimitProperties p = complete();
        p.getGroups().put("logn", quota(5, Duration.ofMinutes(1)));
        assertThatThrownBy(p::validate).isInstanceOf(RateLimitConfigurationException.class).hasMessageContaining("logn");
    }

    @Test
    void nonPositiveValuesFail() {
        RateLimitProperties p = complete();
        p.getGroups().put("login", quota(0, Duration.ofMinutes(1)));
        assertThatThrownBy(p::validate).isInstanceOf(RateLimitConfigurationException.class);
        RateLimitProperties q = complete();
        q.getGroups().put("login", quota(5, Duration.ZERO));
        assertThatThrownBy(q::validate).isInstanceOf(RateLimitConfigurationException.class);
        RateLimitProperties r = complete();
        r.setLoginEmail(null);
        assertThatThrownBy(r::validate).isInstanceOf(RateLimitConfigurationException.class)
                .hasMessageContaining("login-email");
    }

    @Test
    void disabledSkipsValidation() {
        RateLimitProperties p = new RateLimitProperties();
        p.setEnabled(false);
        assertThatCode(p::validate).doesNotThrowAnyException();
    }
}
