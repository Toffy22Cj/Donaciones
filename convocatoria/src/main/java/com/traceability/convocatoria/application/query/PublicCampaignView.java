package com.traceability.convocatoria.application.query;

import java.time.Instant;
import java.util.Set;

/**
 * Detalle público de una convocatoria por su {@code publicCode} (CV-07; plan B6-a §2.3; Q7 de D-API con
 * {@code acceptedPaymentMethods}, DD-03). {@code organizationRef} es interno: lo usa {@code app} para pedir el nombre
 * de la organización y nunca sale en la respuesta. Sin {@code publicCode}, {@code targetPolicy},
 * {@code onTargetReached}, {@code campaignRef} ni {@code configurationVersion}.
 *
 * @param currency      solo con {@code MONETARY}
 * @param targetAmount  solo con {@code MONETARY}
 * @param clearedAmount solo con {@code MONETARY}
 */
public record PublicCampaignView(String organizationRef, String title, String description, String status,
                                 Instant startDate, Instant endDate, Set<String> acceptedDonationTypes,
                                 Set<String> acceptedPaymentMethods, String currency, Long targetAmount,
                                 Long clearedAmount) {}
