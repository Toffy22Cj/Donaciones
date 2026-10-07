package com.traceability.convocatoria.domain.model;

import com.traceability.convocatoria.domain.exception.EmptyAcceptedDonationTypesException;
import com.traceability.convocatoria.domain.exception.IncompleteMonetaryConfigurationException;
import com.traceability.convocatoria.domain.exception.InvalidOnTargetReachedException;
import com.traceability.convocatoria.domain.exception.InvalidTargetAmountException;
import com.traceability.convocatoria.domain.exception.MissingCampaignCurrencyException;
import com.traceability.convocatoria.domain.exception.MonetaryTermsWithoutMonetaryDonationTypeException;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Parte versionada de la configuración de una convocatoria (Enmienda §3.1, §3.2; resumen §6.8.3).
 * Sus invariantes se comprueban en todo estado, incluida la creación (resumen §6.8.4):
 * <ul>
 *   <li>{@code acceptedDonationTypes} no vacío (N1);</li>
 *   <li>con {@code MONETARY}: medios de pago no vacíos, {@code targetAmount} y {@code targetPolicy} definidos
 *       (Enmienda §3.1) y {@code currency} presente (R1);</li>
 *   <li>solo {@code IN_KIND}: sin meta, política ni moneda (N2; R1);</li>
 *   <li>{@code onTargetReached} presente si y solo si {@code targetPolicy = CLOSE_ON_TARGET}.</li>
 * </ul>
 * {@code acceptedPaymentMethods} solo es obligatorio con {@code MONETARY} (Enmienda §3.1); ningún documento
 * prohíbe declararlo en una convocatoria solo {@code IN_KIND}, así que no se rechaza (reportado en §16).
 */
public record ConvocatoriaConfiguration(
        Set<DonationType> acceptedDonationTypes,
        Set<PaymentMethod> acceptedPaymentMethods,
        String currency,
        Long targetAmount,
        TargetPolicy targetPolicy,
        OnTargetReached onTargetReached
) {

    public ConvocatoriaConfiguration {
        if (acceptedDonationTypes == null || acceptedDonationTypes.isEmpty()) {
            throw new EmptyAcceptedDonationTypesException("acceptedDonationTypes must not be empty");
        }
        acceptedDonationTypes = Collections.unmodifiableSet(EnumSet.copyOf(acceptedDonationTypes));
        acceptedPaymentMethods = acceptedPaymentMethods == null || acceptedPaymentMethods.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(PaymentMethod.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(acceptedPaymentMethods));

        if (acceptedDonationTypes.contains(DonationType.MONETARY)) {
            if (acceptedPaymentMethods.isEmpty() || targetAmount == null || targetPolicy == null) {
                throw new IncompleteMonetaryConfigurationException(
                        "MONETARY requires non-empty acceptedPaymentMethods, targetAmount and targetPolicy");
            }
            if (currency == null || currency.isBlank()) {
                throw new MissingCampaignCurrencyException("currency is required when MONETARY is accepted");
            }
            if (targetAmount <= 0) {
                throw new InvalidTargetAmountException("targetAmount must be strictly positive");
            }
            boolean closeOnTarget = targetPolicy == TargetPolicy.CLOSE_ON_TARGET;
            if (closeOnTarget != (onTargetReached != null)) {
                throw new InvalidOnTargetReachedException(
                        "onTargetReached must be present if and only if targetPolicy = CLOSE_ON_TARGET");
            }
        } else if (currency != null || targetAmount != null || targetPolicy != null || onTargetReached != null) {
            throw new MonetaryTermsWithoutMonetaryDonationTypeException(
                    "An IN_KIND-only campaign has no currency, targetAmount, targetPolicy nor onTargetReached");
        }
    }

    public boolean acceptsMonetary() {
        return acceptedDonationTypes.contains(DonationType.MONETARY);
    }

    /** Acepta donaciones en especie (ADR-029 Enmienda 1, D3: requisito del {@code campaignRef} de un activo). */
    public boolean acceptsInKind() {
        return acceptedDonationTypes.contains(DonationType.IN_KIND);
    }

    /** Meta, política y moneda iguales: no existe operación para cambiarlas (implementation_plan.md §3.1). */
    boolean hasSameMonetaryTermsAs(ConvocatoriaConfiguration other) {
        return Objects.equals(currency, other.currency)
                && Objects.equals(targetAmount, other.targetAmount)
                && targetPolicy == other.targetPolicy
                && onTargetReached == other.onTargetReached;
    }
}
