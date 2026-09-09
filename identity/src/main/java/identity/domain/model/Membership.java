package identity.domain.model;

import identity.domain.exception.CannotRemoveLastRoleException;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class Membership {
    private final AccountId accountId;
    private final Set<Role> roles;

    public Membership(AccountId accountId, Set<Role> initialRoles) {
        if (accountId == null) {
            throw new IllegalArgumentException("AccountId cannot be null");
        }
        if (initialRoles == null || initialRoles.isEmpty()) {
            throw new IllegalArgumentException("Membership must have at least one role");
        }
        this.accountId = accountId;
        this.roles = new HashSet<>(initialRoles);
    }

    public AccountId getAccountId() {
        return accountId;
    }

    public Set<Role> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    /**
     * Adds a role to the membership.
     * @return true if the role was added, false if it already had it.
     */
    public boolean addRole(Role role) {
        if (role == null) {
            throw new IllegalArgumentException("Role cannot be null");
        }
        return roles.add(role);
    }

    /**
     * Removes a role from the membership.
     * @return true if the role was removed, false if it didn't have it.
     * @throws CannotRemoveLastRoleException if it's the only role.
     */
    public boolean removeRole(Role role) {
        if (role == null) {
            throw new IllegalArgumentException("Role cannot be null");
        }
        if (!roles.contains(role)) {
            return false;
        }
        if (roles.size() == 1) {
            throw new CannotRemoveLastRoleException("Cannot remove the last role of a membership");
        }
        return roles.remove(role);
    }
}
