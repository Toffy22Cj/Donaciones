package com.traceability.app.web;

import com.traceability.api.web.ApiErrorMapping;
import com.traceability.api.web.ApiErrorMappings;
import com.traceability.api.web.ApiExceptionHandler;
import com.traceability.app.web.campaign.PublicCampaignNotFoundException;
import com.traceability.convocatoria.domain.exception.ActorNotInCampaignOrganizationException;
import com.traceability.convocatoria.domain.exception.ActorRoleNotAllowedException;
import com.traceability.convocatoria.domain.exception.CampaignDateInPastException;
import com.traceability.convocatoria.domain.exception.CampaignDescriptionTooLongException;
import com.traceability.convocatoria.domain.exception.CampaignNotFoundException;
import com.traceability.convocatoria.domain.exception.CampaignTitleRequiredException;
import com.traceability.convocatoria.domain.exception.CampaignTitleTooLongException;
import com.traceability.convocatoria.domain.exception.CampaignVisibilityRequiredException;
import com.traceability.convocatoria.domain.exception.CommandIdReusedForDifferentCommandException;
import com.traceability.convocatoria.domain.exception.EmployeeAlreadyAssignedException;
import com.traceability.convocatoria.domain.exception.EmployeeSelfAssignmentNotAllowedException;
import com.traceability.convocatoria.domain.exception.EmptyAcceptedDonationTypesException;
import com.traceability.convocatoria.domain.exception.IncompleteMonetaryConfigurationException;
import com.traceability.convocatoria.domain.exception.InvalidCampaignCurrencyException;
import com.traceability.convocatoria.domain.exception.InvalidCampaignDateRangeException;
import com.traceability.convocatoria.domain.exception.InvalidOnTargetReachedException;
import com.traceability.convocatoria.domain.exception.InvalidResponsibleRecipientException;
import com.traceability.convocatoria.domain.exception.InvalidTargetAmountException;
import com.traceability.convocatoria.domain.exception.MissingCampaignCurrencyException;
import com.traceability.convocatoria.domain.exception.MonetaryTermsWithoutMonetaryDonationTypeException;
import com.traceability.convocatoria.domain.exception.OrganizationNotVerifiedException;
import com.traceability.convocatoria.domain.exception.ResponsibleAlreadyActiveInCampaignException;
import com.traceability.convocatoria.domain.exception.ResponsibleAssignmentOnClosedCampaignException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Excepciones de {@code convocatoria} → HTTP (plan B6-a §2.6). Solo las que pueden salir de B6-a, una a una: ninguna
 * traducción genérica por {@code ConvocatoriaDomainException} (ADR-041 §2.5). El test de exhaustividad obliga a decidir
 * cada excepción nueva. {@code title} {@code null} = nombre de la regla, sin ningún valor recibido.
 */
@Component
public class ConvocatoriaApiErrorMappings implements ApiErrorMappings {

    @Override
    public String module() {
        return "convocatoria";
    }

    @Override
    public List<ApiErrorMapping> mappings() {
        return List.of();
    }

    private static ApiErrorMapping forbidden(Class<? extends Throwable> type) {
        return new ApiErrorMapping(type, HttpStatus.FORBIDDEN, ApiExceptionHandler.FORBIDDEN);
    }

    private static ApiErrorMapping badRequest(Class<? extends Throwable> type) {
        return new ApiErrorMapping(type, HttpStatus.BAD_REQUEST, null);
    }

    private static ApiErrorMapping conflict(Class<? extends Throwable> type) {
        return new ApiErrorMapping(type, HttpStatus.CONFLICT, null);
    }
}
