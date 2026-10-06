package com.traceability.app.bootstrap;

import identity.application.service.BootstrapPlatformAuthorityService;
import identity.domain.exception.BootstrapTargetAccountNotFoundException;
import identity.domain.model.AccountId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PlatformAdminBootstrapRunnerTest {

    @Test
    void run_callsServiceWithConfiguredEmail() throws Exception {
        BootstrapPlatformAuthorityService service = mock(BootstrapPlatformAuthorityService.class);
        String email = "admin@example.com";
        AccountId expectedId = AccountId.generate();
        when(service.bootstrap(email)).thenReturn(expectedId);

        PlatformAdminBootstrapRunner runner = new PlatformAdminBootstrapRunner(service, email);
        runner.run(new DefaultApplicationArguments());

        verify(service, times(1)).bootstrap(email);
    }

    @Test
    void run_whenServiceThrows_rethrowsSameInstance() {
        BootstrapPlatformAuthorityService service = mock(BootstrapPlatformAuthorityService.class);
        String email = "admin@example.com";
        BootstrapTargetAccountNotFoundException expectedException = new BootstrapTargetAccountNotFoundException();
        when(service.bootstrap(email)).thenThrow(expectedException);

        PlatformAdminBootstrapRunner runner = new PlatformAdminBootstrapRunner(service, email);

        Exception actualException = assertThrows(Exception.class,
                () -> runner.run(new DefaultApplicationArguments()));

        assertSame(expectedException, actualException);
    }
}
