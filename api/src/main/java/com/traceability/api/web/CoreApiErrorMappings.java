package com.traceability.api.web;

import com.traceability.core.application.authorization.CrossOrganizationAccessException;
import com.traceability.core.application.authorization.InsufficientRoleException;
import com.traceability.core.application.command.ConcurrencyRetryExhaustedException;
import com.traceability.core.application.exception.CampaignNotEligibleForInKindDonationException;
import com.traceability.core.application.exception.CommandIdReusedException;
import com.traceability.core.application.exception.FundNotFoundException;
import com.traceability.core.application.exception.PhysicalAssetNotFoundException;
import com.traceability.core.application.exception.ConcurrencyConflictException;
import com.traceability.core.domain.shared.exceptions.AggregateNotFoundException;
import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/** Excepciones de {@code core} → HTTP (plan B6-0 §2.3; Q1–Q2 aprobadas por Carlos, 2026-10-07). */
@Component
public class CoreApiErrorMappings implements ApiErrorMappings {

    /** "No disponible", igual para "no existe" y "es de otra organización" (D-CAMPAIGN). */
    public static final String CAMPAIGN_NOT_AVAILABLE = "CampaignNotAvailable";
    public static final String CONCURRENT_MODIFICATION = "ConcurrentModification";
    public static final String COMMAND_ID_REUSED = "CommandIdReused";

    @Override
    public String module() {
        return "core";
    }

    @Override
    public List<ApiErrorMapping> mappings() {
        return List.of(
                // Q-B60-5: el mismo 403 para las dos; no revela si el recurso es de otra organización o falta un rol
                new ApiErrorMapping(CrossOrganizationAccessException.class, HttpStatus.FORBIDDEN, ApiExceptionHandler.FORBIDDEN),
                new ApiErrorMapping(InsufficientRoleException.class, HttpStatus.FORBIDDEN, ApiExceptionHandler.FORBIDDEN),
                // Plan B6-c, DD-12 [DECISIÓN DELEGADA — pendiente de ratificar por Carlos]: un activo inexistente responde
                // igual que uno de otra organización, para no revelar qué ids existen (Q-B60-6 rige con el 403)
                new ApiErrorMapping(PhysicalAssetNotFoundException.class, HttpStatus.FORBIDDEN, ApiExceptionHandler.FORBIDDEN),
                // Plan P1.1, DD-30: un fondo inexistente responde como uno ajeno
                new ApiErrorMapping(FundNotFoundException.class, HttpStatus.FORBIDDEN, ApiExceptionHandler.FORBIDDEN),
                new ApiErrorMapping(AggregateNotFoundException.class, HttpStatus.NOT_FOUND, ApiExceptionHandler.NOT_FOUND),
                // title = nombre de la regla, derivado de la subclase (null)
                new ApiErrorMapping(DomainInvariantViolationException.class, HttpStatus.CONFLICT, null),
                new ApiErrorMapping(CampaignNotEligibleForInKindDonationException.class, HttpStatus.CONFLICT, CAMPAIGN_NOT_AVAILABLE),
                new ApiErrorMapping(ConcurrencyConflictException.class, HttpStatus.CONFLICT, CONCURRENT_MODIFICATION),
                new ApiErrorMapping(ConcurrencyRetryExhaustedException.class, HttpStatus.CONFLICT, CONCURRENT_MODIFICATION),
                // Plan B6-c, DD-11: el Command-Id ya lo usó otro comando
                new ApiErrorMapping(CommandIdReusedException.class, HttpStatus.CONFLICT, COMMAND_ID_REUSED));
    }
}
