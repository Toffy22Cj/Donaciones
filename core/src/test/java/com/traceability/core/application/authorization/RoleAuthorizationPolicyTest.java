package com.traceability.core.application.authorization;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoleAuthorizationPolicyTest {

    private RoleAuthorizationPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new RoleAuthorizationPolicy();
    }

    @Test
    void testAuthorize_ValidAdministrator() {
        AuthorizationPrincipal admin = new AuthorizationPrincipal("acc-1", "org-1", Set.of(AuthorizationRole.ADMINISTRATOR));

        assertDoesNotThrow(() -> policy.authorize(admin, CommandType.REGISTER_FUND));
        assertDoesNotThrow(() -> policy.authorize(admin, CommandType.CLEAR_FUNDS_AS_GENESIS));
        assertDoesNotThrow(() -> policy.authorize(admin, CommandType.CLEAR_FUNDS_FOR_PLEDGE));
    }

    @Test
    void testAuthorize_ValidEmployee() {
        AuthorizationPrincipal employee = new AuthorizationPrincipal("acc-1", "org-1", Set.of(AuthorizationRole.EMPLOYEE));

        assertDoesNotThrow(() -> policy.authorize(employee, CommandType.REGISTER_PHYSICAL_ASSET));
        assertDoesNotThrow(() -> policy.authorize(employee, CommandType.REGISTER_PHYSICAL_ASSET_FROM_DONATION));
        assertDoesNotThrow(() -> policy.authorize(employee, CommandType.SPLIT_PHYSICAL_ASSET));
    }

    @Test
    void testAuthorize_PrincipalNull() {
        assertThrows(InsufficientRoleException.class, () -> policy.authorize(null, CommandType.REGISTER_FUND));
    }

    @Test
    void testAuthorize_RolesEmpty() {
        AuthorizationPrincipal emptyRoles = new AuthorizationPrincipal("acc-1", "org-1", Collections.emptySet());
        assertThrows(InsufficientRoleException.class, () -> policy.authorize(emptyRoles, CommandType.REGISTER_FUND));
    }

    @Test
    void testAuthorize_RolesNull() {
        AuthorizationPrincipal nullRoles = new AuthorizationPrincipal("acc-1", "org-1", null);
        assertThrows(InsufficientRoleException.class, () -> policy.authorize(nullRoles, CommandType.REGISTER_FUND));
    }

    @Test
    void testAuthorize_IncorrectRole() {
        AuthorizationPrincipal employee = new AuthorizationPrincipal("acc-1", "org-1", Set.of(AuthorizationRole.EMPLOYEE));
        assertThrows(InsufficientRoleException.class, () -> policy.authorize(employee, CommandType.REGISTER_FUND));

        AuthorizationPrincipal admin = new AuthorizationPrincipal("acc-1", "org-1", Set.of(AuthorizationRole.ADMINISTRATOR));
        assertThrows(InsufficientRoleException.class, () -> policy.authorize(admin, CommandType.REGISTER_PHYSICAL_ASSET));

        AuthorizationPrincipal representative = new AuthorizationPrincipal("acc-1", "org-1", Set.of(AuthorizationRole.REPRESENTATIVE));
        assertThrows(InsufficientRoleException.class, () -> policy.authorize(representative, CommandType.REGISTER_FUND));
        assertThrows(InsufficientRoleException.class, () -> policy.authorize(representative, CommandType.REGISTER_PHYSICAL_ASSET));
    }
}
