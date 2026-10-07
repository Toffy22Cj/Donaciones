package com.traceability.app.infrastructure.identity;

import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.OrganizationNotFoundException;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import identity.domain.model.VerificationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrganizationVerificationAdapterTest {

    private static final String ORG_REF = "01JTESTORGANIZATION0000000";

    private final OrganizationRepositoryPort repository = mock(OrganizationRepositoryPort.class);
    private final OrganizationVerificationAdapter adapter = new OrganizationVerificationAdapter(repository);

    @Test
    void verifiedOrganization_returnsTrue() {
        givenOrganizationWithStatus(VerificationStatus.VERIFIED);

        assertThat(adapter.isVerified(ORG_REF)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = VerificationStatus.class, names = "VERIFIED", mode = EnumSource.Mode.EXCLUDE)
    void nonVerifiedOrganization_returnsFalse(VerificationStatus status) {
        givenOrganizationWithStatus(status);

        assertThat(adapter.isVerified(ORG_REF)).isFalse();
    }

    @Test
    void unknownOrganization_returnsFalseInsteadOfThrowing() {
        when(repository.findById(any())).thenThrow(new OrganizationNotFoundException("not found"));

        assertThat(adapter.isVerified(ORG_REF)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void missingOrganizationRef_returnsFalseWithoutReadingIdentity(String organizationRef) {
        assertThat(adapter.isVerified(organizationRef)).isFalse();
        verifyNoInteractions(repository);
    }

    private void givenOrganizationWithStatus(VerificationStatus status) {
        Organization organization = mock(Organization.class);
        when(organization.getVerificationStatus()).thenReturn(status);
        when(repository.findById(new OrganizationId(ORG_REF))).thenReturn(organization);
    }
}
