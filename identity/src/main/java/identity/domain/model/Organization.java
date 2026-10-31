package identity.domain.model;

import identity.domain.exception.AccountAlreadyBelongsToOrganizationException;
import identity.domain.exception.AccountNotMemberOfOrganizationException;
import identity.domain.exception.AccountNotRepresentativeException;
import identity.domain.exception.InvalidMemberRoleException;
import identity.domain.exception.InvalidOrganizationProfileFieldException;
import identity.domain.exception.InvalidVerificationTransitionException;
import identity.domain.exception.MemberAlreadyHasRoleException;
import identity.domain.exception.OrganizationProfileLockedException;
import identity.domain.exception.RepresentativeTransferRequiredException;
import identity.domain.exception.SelfTransferNotAllowedException;
import identity.domain.exception.TransferTargetNotMemberException;
import identity.domain.exception.VerificationDataMissingException;
import identity.domain.exception.VerificationResubmitNotAllowedException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class Organization {
    private final OrganizationId organizationId;
    private final OrganizationType type;
    private final List<Membership> members;
    private VerificationStatus verificationStatus;
    private InformationRequestMessage verificationInformationRequest;
    /** Nombre público, opcional (plan B6-a, Q-B6A-1; `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` DD-04). */
    private String name;
    /** Referencia del logo: la {@code objectKey} del proveedor (ADR-051 F9; P-T2-1). Opcional: si falta, no hay logo. */
    private String logoObjectKey;
    /** ADR-050 D2: los términos de organización aceptados al crearla. Nulo en las organizaciones existentes. */
    private OrganizationTermsAcceptance organizationTermsAcceptance;
    /** Ficha R-7 §3: el perfil público y de verificación; vacío (nunca nulo) en las organizaciones existentes. */
    private OrganizationProfile profile = OrganizationProfile.EMPTY;

    /** Longitud máxima del nombre público (DD-04). */
    public static final int NAME_MAX_LENGTH = 200;

    private Organization(OrganizationId organizationId,
                         OrganizationType type,
                         List<Membership> initialMembers,
                         VerificationStatus verificationStatus,
                         InformationRequestMessage verificationInformationRequest) {
        this.organizationId = organizationId;
        this.type = type;
        this.members = new ArrayList<>(initialMembers);
        this.verificationStatus = verificationStatus;
        this.verificationInformationRequest = verificationInformationRequest;
    }

    /**
     * Uso exclusivo de adaptadores de persistencia — NUNCA invocar desde Application Services ni tests de dominio.
     * No aplica ninguna regla de negocio de creación.
     */
    public static Organization reconstitute(OrganizationId organizationId,
                                            OrganizationType type,
                                            List<Membership> members,
                                            VerificationStatus verificationStatus,
                                            InformationRequestMessage verificationInformationRequest) {
        Objects.requireNonNull(verificationStatus, "verificationStatus must not be null");
        if (verificationStatus == VerificationStatus.NEEDS_MORE_INFORMATION && verificationInformationRequest == null) {
            throw new IllegalArgumentException("verificationInformationRequest must be present when verificationStatus is NEEDS_MORE_INFORMATION");
        }
        if (verificationStatus != VerificationStatus.NEEDS_MORE_INFORMATION && verificationInformationRequest != null) {
            throw new IllegalArgumentException("verificationInformationRequest must be null when verificationStatus is not NEEDS_MORE_INFORMATION");
        }
        return new Organization(organizationId, type, members, verificationStatus, verificationInformationRequest);
    }

    /** Igual que {@link #reconstitute(OrganizationId, OrganizationType, List, VerificationStatus, InformationRequestMessage)}, con el nombre público. */
    public static Organization reconstitute(OrganizationId organizationId,
                                            OrganizationType type,
                                            List<Membership> members,
                                            VerificationStatus verificationStatus,
                                            InformationRequestMessage verificationInformationRequest,
                                            String name) {
        Organization organization = reconstitute(organizationId, type, members, verificationStatus,
                verificationInformationRequest);
        organization.name = name;
        return organization;
    }

    /** Igual que el anterior, con la referencia del logo (ADR-051 F9). */
    public static Organization reconstitute(OrganizationId organizationId,
                                            OrganizationType type,
                                            List<Membership> members,
                                            VerificationStatus verificationStatus,
                                            InformationRequestMessage verificationInformationRequest,
                                            String name,
                                            String logoObjectKey) {
        Organization organization = reconstitute(organizationId, type, members, verificationStatus,
                verificationInformationRequest, name);
        organization.logoObjectKey = logoObjectKey;
        return organization;
    }

    /** Igual que el anterior, con la aceptación de los términos de organización (ADR-050 D2). */
    public static Organization reconstitute(OrganizationId organizationId,
                                            OrganizationType type,
                                            List<Membership> members,
                                            VerificationStatus verificationStatus,
                                            InformationRequestMessage verificationInformationRequest,
                                            String name,
                                            String logoObjectKey,
                                            OrganizationTermsAcceptance organizationTermsAcceptance) {
        Organization organization = reconstitute(organizationId, type, members, verificationStatus,
                verificationInformationRequest, name, logoObjectKey);
        organization.organizationTermsAcceptance = organizationTermsAcceptance;
        return organization;
    }

    /** Igual que el anterior, con el perfil (ficha R-7). Uso exclusivo de adaptadores de persistencia. */
    public static Organization reconstitute(OrganizationId organizationId,
                                            OrganizationType type,
                                            List<Membership> members,
                                            VerificationStatus verificationStatus,
                                            InformationRequestMessage verificationInformationRequest,
                                            String name,
                                            String logoObjectKey,
                                            OrganizationTermsAcceptance organizationTermsAcceptance,
                                            OrganizationProfile profile) {
        Organization organization = reconstitute(organizationId, type, members, verificationStatus,
                verificationInformationRequest, name, logoObjectKey, organizationTermsAcceptance);
        organization.profile = profile == null ? OrganizationProfile.EMPTY : profile;
        return organization;
    }

    public static Organization createOrganization(OrganizationType type, AccountId initialRepresentativeAccountId) {
        if (type == null) {
            throw new IllegalArgumentException("OrganizationType cannot be null");
        }
        if (initialRepresentativeAccountId == null) {
            throw new IllegalArgumentException("Initial Representative AccountId cannot be null");
        }
        Membership initialMembership = new Membership(initialRepresentativeAccountId, Set.of(Role.REPRESENTATIVE));
        return new Organization(OrganizationId.generate(), type, List.of(initialMembership), VerificationStatus.PENDING_VERIFICATION, null);
    }

    /**
     * Igual que {@link #createOrganization(OrganizationType, AccountId)}, con un nombre público opcional (plan B6-a,
     * Q-B6A-1). Un nombre presente no puede estar en blanco ni superar {@link #NAME_MAX_LENGTH}.
     */
    public static Organization createOrganization(OrganizationType type, AccountId initialRepresentativeAccountId,
                                                  String name) {
        if (name != null && (name.isBlank() || name.length() > NAME_MAX_LENGTH)) {
            throw new IllegalArgumentException("Organization name must not be blank nor exceed " + NAME_MAX_LENGTH);
        }
        Organization organization = createOrganization(type, initialRepresentativeAccountId);
        organization.name = name == null ? null : name.strip();
        return organization;
    }

    public String getName() {
        return name;
    }

    public OrganizationProfile getProfile() {
        return profile;
    }

    /**
     * Ficha R-7 §4 ({@code PUT /profile}): sustituye el nombre y el perfil completos.
     * <ul>
     *   <li>{@code name}: de 1 a {@link #NAME_MAX_LENGTH} caracteres (P-R7-7, como al crear), o
     *       {@link InvalidOrganizationProfileFieldException} ({@code name});</li>
     *   <li>{@code specialTaxRegime} solo para una {@code FOUNDATION} (§3);</li>
     *   <li>P1 (b): con {@code VERIFIED}, si cambian {@code name}, {@code legalName}, {@code nit} o {@code nitCheckDigit}
     *       → {@link OrganizationProfileLockedException} y nada cambia.</li>
     * </ul>
     *
     * @return los nombres de los campos que cambiaron ({@code name} y los de {@link OrganizationProfile#changedFields})
     */
    public List<String> updateProfile(String newName, OrganizationProfile newProfile) {
        Objects.requireNonNull(newProfile, "profile must not be null");
        String stripped = newName == null ? null : newName.strip();
        if (stripped == null || stripped.isEmpty() || stripped.length() > NAME_MAX_LENGTH) {
            throw new InvalidOrganizationProfileFieldException("name");
        }
        if (newProfile.specialTaxRegime() != null && this.type != OrganizationType.FOUNDATION) {
            throw new InvalidOrganizationProfileFieldException("specialTaxRegime");
        }
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(this.name, stripped)) {
            changed.add("name");
        }
        changed.addAll(this.profile.changedFields(newProfile));
        if (this.verificationStatus == VerificationStatus.VERIFIED && changed.stream().anyMatch(LOCKED_WHEN_VERIFIED::contains)) {
            throw new OrganizationProfileLockedException("A verified organization cannot change " + LOCKED_WHEN_VERIFIED);
        }
        this.name = stripped;
        this.profile = newProfile;
        return changed;
    }

    /**
     * [Plan] Ficha R-7 §4 ({@code POST /organizations} ampliado): el perfil inicial de una organización recién creada,
     * con el nombre opcional de siempre (B6-a). Solo en {@code PENDING_VERIFICATION} y con la regla de
     * {@code specialTaxRegime}.
     */
    public void applyProfileAtCreation(OrganizationProfile initialProfile) {
        Objects.requireNonNull(initialProfile, "profile must not be null");
        if (this.verificationStatus != VerificationStatus.PENDING_VERIFICATION) {
            throw new IllegalStateException("The initial profile is only set at creation");
        }
        if (initialProfile.specialTaxRegime() != null && this.type != OrganizationType.FOUNDATION) {
            throw new InvalidOrganizationProfileFieldException("specialTaxRegime");
        }
        this.profile = initialProfile;
    }

    /** Ficha R-7, P1 (b): lo que identifica a la organización verificada. */
    public static final Set<String> LOCKED_WHEN_VERIFIED = Set.of("name", "legalName", "nit", "nitCheckDigit");

    /**
     * Ficha R-7 §5 (P8 (a)): la organización responde a la solicitud de información. {@code NEEDS_MORE_INFORMATION →
     * PENDING_VERIFICATION} y se borra la solicitud; desde cualquier otro estado, {@link
     * VerificationResubmitNotAllowedException}.
     */
    public void resubmitForVerification() {
        if (this.verificationStatus != VerificationStatus.NEEDS_MORE_INFORMATION) {
            throw new VerificationResubmitNotAllowedException("Resubmit requires NEEDS_MORE_INFORMATION, not "
                    + this.verificationStatus);
        }
        this.verificationStatus = VerificationStatus.PENDING_VERIFICATION;
        this.verificationInformationRequest = null;
    }

    public String getLogoObjectKey() {
        return logoObjectKey;
    }

    public OrganizationTermsAcceptance getOrganizationTermsAcceptance() {
        return organizationTermsAcceptance;
    }

    /** ADR-050 D2: guarda la versión de los términos de organización, la cuenta que los aceptó y cuándo. */
    public void acceptOrganizationTerms(String version, AccountId acceptedBy, Instant now) {
        this.organizationTermsAcceptance = new OrganizationTermsAcceptance(version, acceptedBy, now);
    }

    /**
     * Pone un logo nuevo (ADR-051 F10). No toca miembros ni verificación.
     * @return la referencia anterior, si la había: su archivo se borra después de confirmar el cambio
     */
    public Optional<String> replaceLogo(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("Logo objectKey cannot be blank");
        }
        Optional<String> previous = Optional.ofNullable(logoObjectKey);
        logoObjectKey = objectKey;
        return previous;
    }

    /** Quita el logo (ADR-051 F10). @return la referencia anterior, si la había */
    public Optional<String> removeLogo() {
        Optional<String> previous = Optional.ofNullable(logoObjectKey);
        logoObjectKey = null;
        return previous;
    }

    public OrganizationId getOrganizationId() {
        return organizationId;
    }

    public OrganizationType getType() {
        return type;
    }

    public List<Membership> getMembers() {
        return Collections.unmodifiableList(members);
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public InformationRequestMessage getVerificationInformationRequest() {
        return verificationInformationRequest;
    }

    private Optional<Membership> findMembership(AccountId accountId) {
        return members.stream()
            .filter(m -> m.getAccountId().equals(accountId))
            .findFirst();
    }

    public void addEmployee(AccountId accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("AccountId cannot be null");
        }
        if (findMembership(accountId).isPresent()) {
            throw new AccountAlreadyBelongsToOrganizationException("Account is already a member of this organization");
        }
        members.add(new Membership(accountId, Set.of(Role.EMPLOYEE)));
    }

    /**
     * Assigns the ADMINISTRATOR role to an existing member.
     * @return true if the role was added (mutation occurred), false if the member already had the role (no-op).
     */
    public boolean assignAdministrator(AccountId accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("AccountId cannot be null");
        }
        Membership membership = findMembership(accountId)
            .orElseThrow(() -> new AccountNotMemberOfOrganizationException("Account is not a member of this organization"));

        return membership.addRole(Role.ADMINISTRATOR);
    }

    /**
     * Removes the ADMINISTRATOR role from an existing member.
     * @return true if the role was removed (mutation occurred), false if the member did not have the role (no-op).
     */
    public boolean removeAdministrator(AccountId accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("AccountId cannot be null");
        }
        Membership membership = findMembership(accountId)
            .orElseThrow(() -> new AccountNotMemberOfOrganizationException("Account is not a member of this organization"));

        return membership.removeRole(Role.ADMINISTRATOR);
    }

    /**
     * Removes the EMPLOYEE role from an existing member.
     * @return true if the role was removed (mutation occurred), false if the member did not have the role (no-op).
     */
    public boolean removeEmployee(AccountId accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("AccountId cannot be null");
        }
        Membership membership = findMembership(accountId)
            .orElseThrow(() -> new AccountNotMemberOfOrganizationException("Account is not a member of this organization"));

        return membership.removeRole(Role.EMPLOYEE);
    }

    /**
     * Cambia el rol de un miembro (ADR-049 D7; operación nueva, no contradice ADR-026). {@code REPRESENTATIVE} nunca se
     * toca aquí: se cambia solo con la transferencia.
     * <ul>
     *   <li>{@code ADMINISTRATOR}: añade {@code ADMINISTRATOR} y conserva el resto;</li>
     *   <li>{@code EMPLOYEE}: añade {@code EMPLOYEE} si falta y quita {@code ADMINISTRATOR}.</li>
     * </ul>
     * Si ya está en el estado pedido, {@link MemberAlreadyHasRoleException} (DD-66: la opción restrictiva).
     */
    public void changeMemberRole(AccountId accountId, Role target) {
        if (accountId == null) {
            throw new IllegalArgumentException("AccountId cannot be null");
        }
        if (target != Role.ADMINISTRATOR && target != Role.EMPLOYEE) {
            throw new InvalidMemberRoleException("Role must be ADMINISTRATOR or EMPLOYEE");
        }
        Membership membership = findMembership(accountId)
            .orElseThrow(() -> new AccountNotMemberOfOrganizationException("Account is not a member of this organization"));
        if (target == Role.ADMINISTRATOR) {
            if (!membership.addRole(Role.ADMINISTRATOR)) {
                throw new MemberAlreadyHasRoleException("Member already has the ADMINISTRATOR role");
            }
            return;
        }
        if (membership.hasRole(Role.EMPLOYEE) && !membership.hasRole(Role.ADMINISTRATOR)) {
            throw new MemberAlreadyHasRoleException("Member already has the EMPLOYEE role only");
        }
        // primero EMPLOYEE, para que quitar ADMINISTRATOR nunca deje la membresía sin roles (invariante 4)
        membership.addRole(Role.EMPLOYEE);
        membership.removeRole(Role.ADMINISTRATOR);
    }

    /** La membresía de una cuenta, si es miembro. */
    public Optional<Membership> membershipOf(AccountId accountId) {
        return findMembership(accountId);
    }

    /**
     * Removes an account from the organization entirely.
     * @throws RepresentativeTransferRequiredException if the member is the REPRESENTATIVE.
     */
    public void removeMemberFromOrganization(AccountId accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("AccountId cannot be null");
        }
        Membership membership = findMembership(accountId)
            .orElseThrow(() -> new AccountNotMemberOfOrganizationException("Account is not a member of this organization"));

        if (membership.hasRole(Role.REPRESENTATIVE)) {
            throw new RepresentativeTransferRequiredException("Cannot remove the representative without transferring the role first");
        }

        // Just remove the membership. Roles are never emptied.
        members.remove(membership);
    }

    /**
     * Atomically transfers the REPRESENTATIVE role to another member, and if the current
     * representative is left without any roles, removes them from the organization entirely.
     */
    public void transferRepresentativeAndRemove(AccountId currentRepId, AccountId newRepId) {
        if (currentRepId == null || newRepId == null) {
            throw new IllegalArgumentException("Account IDs cannot be null");
        }
        if (currentRepId.equals(newRepId)) {
            throw new SelfTransferNotAllowedException("Cannot transfer representative role to the same account");
        }

        Membership currentRepMembership = findMembership(currentRepId)
            .orElseThrow(() -> new AccountNotMemberOfOrganizationException("Current representative is not a member of this organization"));
        
        if (!currentRepMembership.hasRole(Role.REPRESENTATIVE)) {
            throw new AccountNotRepresentativeException("Current account does not hold the representative role");
        }

        Membership newRepMembership = findMembership(newRepId)
            .orElseThrow(() -> new TransferTargetNotMemberException("Target account is not a member of this organization"));

        // Assign REPRESENTATIVE to the new representative
        newRepMembership.addRole(Role.REPRESENTATIVE);

        // Handle the current representative
        if (currentRepMembership.getRoles().size() == 1) {
            // It only has REPRESENTATIVE. Removing it would leave roles empty.
            // So we remove the membership entirely without modifying its roles.
            members.remove(currentRepMembership);
        } else {
            // It has other roles, so we can safely remove REPRESENTATIVE
            currentRepMembership.removeRole(Role.REPRESENTATIVE);
        }
    }

    public void verify() {
        if (this.verificationStatus == VerificationStatus.VERIFIED || this.verificationStatus == VerificationStatus.REJECTED) {
            throw new InvalidVerificationTransitionException(this.verificationStatus, VerificationCommand.VERIFY);
        }
        // ficha R-7, P2 (b): la plataforma no verifica sin razón social y NIT con que comprobarla
        if (this.profile.legalName() == null || this.profile.nit() == null) {
            throw new VerificationDataMissingException("Verification requires legalName and nit");
        }
        this.verificationStatus = VerificationStatus.VERIFIED;
        this.verificationInformationRequest = null;
    }

    public void reject() {
        if (this.verificationStatus == VerificationStatus.VERIFIED || this.verificationStatus == VerificationStatus.REJECTED) {
            throw new InvalidVerificationTransitionException(this.verificationStatus, VerificationCommand.REJECT);
        }
        this.verificationStatus = VerificationStatus.REJECTED;
        this.verificationInformationRequest = null;
    }

    public void requestInformation(InformationRequestMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        if (this.verificationStatus == VerificationStatus.VERIFIED || this.verificationStatus == VerificationStatus.REJECTED) {
            throw new InvalidVerificationTransitionException(this.verificationStatus, VerificationCommand.REQUEST_INFORMATION);
        }
        this.verificationStatus = VerificationStatus.NEEDS_MORE_INFORMATION;
        this.verificationInformationRequest = message;
    }
}
