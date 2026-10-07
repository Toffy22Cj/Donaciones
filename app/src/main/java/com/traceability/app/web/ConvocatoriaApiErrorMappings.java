package com.traceability.app.web;

import com.traceability.api.web.ApiErrorMapping;
import com.traceability.api.web.ApiErrorMappings;
import com.traceability.api.web.ApiExceptionHandler;
import com.traceability.app.web.campaign.PublicCampaignNotFoundException;
import com.traceability.app.web.donation.IntentNotFoundException;
import com.traceability.app.web.donation.InvalidWebhookSignatureException;
import com.traceability.convocatoria.domain.exception.CampaignClosedException;
import com.traceability.convocatoria.domain.exception.CashDonationIntentNotSupportedException;
import com.traceability.convocatoria.domain.exception.CloseOnTargetCloseNotSupportedException;
import com.traceability.convocatoria.domain.exception.DonationCurrencyMismatchException;
import com.traceability.convocatoria.domain.exception.DonationTypeNotAcceptedException;
import com.traceability.convocatoria.domain.exception.InvalidDonationAmountException;
import com.traceability.convocatoria.domain.exception.PaymentCorrelationNotFoundException;
import com.traceability.convocatoria.domain.exception.PaymentEventMismatchException;
import com.traceability.convocatoria.domain.exception.PaymentMethodNotAcceptedException;
import com.traceability.convocatoria.domain.exception.PaymentProviderUnavailableException;
import com.traceability.convocatoria.domain.exception.SimulatedPaymentsNotAllowedException;
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
        return List.of(
                // El mismo 403 que core (Q-B60-5). CampaignNotFound en CV-02 responde igual que "otra organización"
                // (plan B6-a §2.2; Q-B60-6 rige con el 403, DD-01)
                forbidden(ActorNotInCampaignOrganizationException.class),
                forbidden(ActorRoleNotAllowedException.class),
                forbidden(CampaignNotFoundException.class),
                // CV-07: no encontrado público, cuerpo fijo
                new ApiErrorMapping(PublicCampaignNotFoundException.class, HttpStatus.NOT_FOUND, ApiExceptionHandler.NOT_FOUND),
                // Validación de la entrada (ficha CV-01 §3.4)
                badRequest(CampaignTitleRequiredException.class),
                badRequest(CampaignTitleTooLongException.class),
                badRequest(CampaignDescriptionTooLongException.class),
                badRequest(CampaignVisibilityRequiredException.class),
                badRequest(InvalidCampaignDateRangeException.class),
                badRequest(CampaignDateInPastException.class),
                badRequest(EmptyAcceptedDonationTypesException.class),
                badRequest(IncompleteMonetaryConfigurationException.class),
                badRequest(MissingCampaignCurrencyException.class),
                badRequest(InvalidCampaignCurrencyException.class),
                badRequest(InvalidTargetAmountException.class),
                badRequest(InvalidOnTargetReachedException.class),
                badRequest(MonetaryTermsWithoutMonetaryDonationTypeException.class),
                // Conflictos con el estado (Q1 de B6-0)
                conflict(OrganizationNotVerifiedException.class),
                conflict(CommandIdReusedForDifferentCommandException.class),
                conflict(ResponsibleAlreadyActiveInCampaignException.class),
                conflict(EmployeeAlreadyAssignedException.class),
                conflict(EmployeeSelfAssignmentNotAllowedException.class),
                // Q-B6A-2 (DD-05): el mismo 409 para destinatario ajeno, inexistente o INACTIVE
                conflict(InvalidResponsibleRecipientException.class),
                conflict(ResponsibleAssignmentOnClosedCampaignException.class),
                // B6-b: CV-11, consulta de la intención y webhook simulado (plan B6-b; DD-20)
                new ApiErrorMapping(IntentNotFoundException.class, HttpStatus.NOT_FOUND, ApiExceptionHandler.NOT_FOUND),
                new ApiErrorMapping(PaymentCorrelationNotFoundException.class, HttpStatus.NOT_FOUND, ApiExceptionHandler.NOT_FOUND),
                new ApiErrorMapping(InvalidWebhookSignatureException.class, HttpStatus.UNAUTHORIZED, null),
                badRequest(InvalidDonationAmountException.class),
                conflict(CampaignClosedException.class),
                conflict(CashDonationIntentNotSupportedException.class),
                conflict(CloseOnTargetCloseNotSupportedException.class),
                conflict(DonationCurrencyMismatchException.class),
                conflict(DonationTypeNotAcceptedException.class),
                conflict(PaymentMethodNotAcceptedException.class),
                conflict(PaymentEventMismatchException.class),
                conflict(SimulatedPaymentsNotAllowedException.class),
                conflict(PaymentProviderUnavailableException.class));
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
