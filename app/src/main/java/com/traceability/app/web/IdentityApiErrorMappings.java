package com.traceability.app.web;

import com.traceability.api.web.ApiErrorMapping;
import com.traceability.api.web.ApiErrorMappings;
import com.traceability.api.web.ApiExceptionHandler;
import identity.domain.exception.AccountNotFoundException;
import identity.domain.exception.DuplicateEmailException;
import identity.domain.exception.InvalidEmailFormatException;
import identity.domain.exception.InvalidInformationRequestMessageException;
import identity.domain.exception.InactiveAccountException;
import identity.domain.exception.InsufficientPlatformAuthorityException;
import identity.domain.exception.InvalidVerificationTransitionException;
import identity.domain.exception.OrganizationAccessDeniedException;
import identity.domain.exception.OrganizationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Excepciones de {@code identity} que pueden salir de B6-a (plan B6-a §2.2 y §2.4).
 *
 * <p>Cuenta inexistente o {@code INACTIVE}: en B6-a solo puede llegar aquí al resolver el <b>destinatario</b> de CV-02
 * (el actor ya lo resolvió el filtro JWT en la misma petición, y un fallo ahí es 401). Responden exactamente igual que
 * un destinatario de otra organización, para no revelar qué cuentas existen (Q-B6A-2 (i),
 * `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` DD-05). Se traduce aquí, y no en {@code convocatoria},
 * para no contradecir X2 (implementation_plan.md §6: lo que lanza {@code resolvePrincipal} se propaga sin renombrar).
 */
@Component
public class IdentityApiErrorMappings implements ApiErrorMappings {

    /** El título que deriva {@code InvalidResponsibleRecipientException}: las tres respuestas son idénticas. */
    static final String INVALID_RESPONSIBLE_RECIPIENT = "InvalidResponsibleRecipient";

    @Override
    public String module() {
        return "identity";
    }

    @Override
    public List<ApiErrorMapping> mappings() {
        return List.of(
                new ApiErrorMapping(InsufficientPlatformAuthorityException.class, HttpStatus.FORBIDDEN, ApiExceptionHandler.FORBIDDEN),
                // solo la ve un administrador de plataforma, que puede saber qué organizaciones existen
                new ApiErrorMapping(OrganizationNotFoundException.class, HttpStatus.NOT_FOUND, ApiExceptionHandler.NOT_FOUND),
                new ApiErrorMapping(InvalidVerificationTransitionException.class, HttpStatus.CONFLICT, null),
                new ApiErrorMapping(AccountNotFoundException.class, HttpStatus.CONFLICT, INVALID_RESPONSIBLE_RECIPIENT),
                new ApiErrorMapping(InactiveAccountException.class, HttpStatus.CONFLICT, INVALID_RESPONSIBLE_RECIPIENT),
                // P2.2 (registro): el email mal formado es una validación con nombre (400); el duplicado, 409
                new ApiErrorMapping(InvalidEmailFormatException.class, HttpStatus.BAD_REQUEST, null),
                new ApiErrorMapping(DuplicateEmailException.class, HttpStatus.CONFLICT, null),
                // P2.8 (pedir información): mensaje vacío o de más de 2000 caracteres
                new ApiErrorMapping(InvalidInformationRequestMessageException.class, HttpStatus.BAD_REQUEST, null),
                // P2.7 (miembros): el mismo 403 que cualquier acceso denegado (DD-01)
                new ApiErrorMapping(OrganizationAccessDeniedException.class, HttpStatus.FORBIDDEN, ApiExceptionHandler.FORBIDDEN));
    }
}
