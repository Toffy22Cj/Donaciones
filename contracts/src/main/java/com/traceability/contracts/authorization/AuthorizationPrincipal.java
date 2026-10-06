package com.traceability.contracts.authorization;

import java.util.Set;

/**
 * Principal de autorización que representa al actor autenticado en el sistema.
 *
 * @param accountId identificador de la cuenta
 * @param organizationId identificador de la organización a la que pertenece, o {@code null} si no pertenece a ninguna
 * @param roles conjunto de roles dentro de la organización
 * @param platformAuthority autoridad global de plataforma, o {@code null} si no posee autoridad de plataforma
 */
public record AuthorizationPrincipal(
    String accountId,
    String organizationId,
    Set<AuthorizationRole> roles,
    PlatformAuthority platformAuthority
) {}
