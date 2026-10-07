package com.traceability.core.application.authorization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrganizationBoundaryPolicyTest {

    private OrganizationBoundaryPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new OrganizationBoundaryPolicy();
    }

    @Test
    void testAssertBelongs_SameOrganization_DoesNotThrow() {
        assertDoesNotThrow(() -> policy.assertBelongs("org-1", "org-1"));
    }

    @Test
    void testAssertBelongs_DifferentOrganizations_Throws() {
        assertThrows(CrossOrganizationAccessException.class,
                () -> policy.assertBelongs("org-1", "org-2"));
    }

    @Test
    void testAssertBelongs_PrincipalOrganizationNull_Throws() {
        assertThrows(CrossOrganizationAccessException.class,
                () -> policy.assertBelongs(null, "org-1"));
    }

    @Test
    void testAssertBelongs_ResourceOrganizationNull_Throws() {
        assertThrows(CrossOrganizationAccessException.class,
                () -> policy.assertBelongs("org-1", null));
    }

    @Test
    void testAssertBelongs_BothNull_Throws() {
        assertThrows(CrossOrganizationAccessException.class,
                () -> policy.assertBelongs(null, null));
    }
}