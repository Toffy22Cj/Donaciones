package com.traceability.convocatoria.domain.model;

import com.traceability.convocatoria.domain.exception.CampaignAlreadyClosedException;
import com.traceability.convocatoria.domain.exception.CampaignClosedException;
import com.traceability.convocatoria.domain.exception.CampaignDateInPastException;
import com.traceability.convocatoria.domain.exception.CampaignDescriptionTooLongException;
import com.traceability.convocatoria.domain.exception.CampaignVisibilityRequiredException;
import com.traceability.convocatoria.domain.exception.CampaignTitleRequiredException;
import com.traceability.convocatoria.domain.exception.CampaignTitleTooLongException;
import com.traceability.convocatoria.domain.exception.CashDonationIntentNotSupportedException;
import com.traceability.convocatoria.domain.exception.ConfigurationChangeOnClosedCampaignException;
import com.traceability.convocatoria.domain.exception.DonationCurrencyMismatchException;
import com.traceability.convocatoria.domain.exception.DonationTypeNotAcceptedException;
import com.traceability.convocatoria.domain.exception.InvalidCampaignDateRangeException;
import com.traceability.convocatoria.domain.exception.MonetaryTermsChangeNotSupportedException;
import com.traceability.convocatoria.domain.exception.PaymentMethodNotAcceptedException;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Convocatoria (ADR-037 §2.1; Enmienda §3.1, §3.2, §3.4; R1 de convocatoria-resumen.md §6.15).
 * CRUD + audit log, no Event Sourcing (ADR-037 §2). {@code title}, {@code description}, {@code visibility}
 * y las fechas se fijan al crear y no forman parte de la configuración versionada (implementation_plan.md §3.1).
 * Las fechas describen la convocatoria y no limitan la creación de intenciones en este corte (P7 abierto).
 */
public final class Convocatoria {

    /** Longitud máxima de {@code title}: valor de implementación (R1), reportado en implementation_plan.md §16. */
    public static final int TITLE_MAX_LENGTH = 200;
    /** Q-CV01-15 de la ficha CV-01 (deuda D-9). */
    public static final int DESCRIPTION_MAX_LENGTH = 5000;
    /** Q-CV01-9a de la ficha CV-01 (deuda D-2): tolerancia hacia el pasado al crear. */
    public static final Duration DATE_TOLERANCE = Duration.ofMinutes(5);

    public static final long INITIAL_CONFIGURATION_VERSION = 1L;

    private final String campaignRef;
    private final String organizationRef;
    private final String publicCode;
    private final String title;
    private final String description;
    private final Visibility visibility;
    private final Instant startDate;
    private final Instant endDate;
    private ConvocatoriaStatus status;
    private ConvocatoriaConfiguration configuration;
    private long configurationVersion;

    private Convocatoria(String campaignRef, String organizationRef, String publicCode, String title,
                         String description, Visibility visibility, Instant startDate, Instant endDate,
                         ConvocatoriaStatus status, ConvocatoriaConfiguration configuration,
                         long configurationVersion) {
        this.campaignRef = Objects.requireNonNull(campaignRef, "campaignRef");
        this.organizationRef = Objects.requireNonNull(organizationRef, "organizationRef");
        this.publicCode = Objects.requireNonNull(publicCode, "publicCode");
        this.title = title;
        this.description = description;
        this.visibility = Objects.requireNonNull(visibility, "visibility");
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = Objects.requireNonNull(status, "status");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.configurationVersion = configurationVersion;
    }

    public static Convocatoria create(String campaignRef, String organizationRef, String publicCode, String title,
                                      String description, Visibility visibility, Instant startDate, Instant endDate,
                                      ConvocatoriaConfiguration configuration) {
        if (title == null || title.isBlank()) {
            throw new CampaignTitleRequiredException("title is required");
        }
        if (title.length() > TITLE_MAX_LENGTH) {
            throw new CampaignTitleTooLongException("title exceeds " + TITLE_MAX_LENGTH + " characters");
        }
        if (startDate == null || endDate == null || !startDate.isBefore(endDate)) {
            throw new InvalidCampaignDateRangeException("startDate and endDate are required and startDate < endDate");
        }
        return new Convocatoria(campaignRef, organizationRef, publicCode, title, description, visibility,
                startDate, endDate, ConvocatoriaStatus.OPEN, configuration, INITIAL_CONFIGURATION_VERSION);
    }

    /**
     * Regla de creación (deuda D-2, Q-CV01-9a de la ficha CV-01): ninguna fecha anterior a {@code now − 5 min}. Solo
     * al crear: no es una máquina de estados. Las fechas ausentes las rechaza {@link #create}.
     */
    public static void requireDatesNotInPast(Instant startDate, Instant endDate, Instant now) {

    }

    /** Uso exclusivo de adaptadores de persistencia: no aplica reglas de creación. */
    public static Convocatoria reconstitute(String campaignRef, String organizationRef, String publicCode,
                                            String title, String description, Visibility visibility,
                                            Instant startDate, Instant endDate, ConvocatoriaStatus status,
                                            ConvocatoriaConfiguration configuration, long configurationVersion) {
        return new Convocatoria(campaignRef, organizationRef, publicCode, title, description, visibility,
                startDate, endDate, status, configuration, configurationVersion);
    }

    /**
     * Edición de configuración: produce una versión nueva con efecto prospectivo (Enmienda §3.2).
     * {@code CLOSED} no admite cambios; añadir {@code MONETARY} es una sola transición que incluye meta,
     * política, medios y moneda; meta, política y moneda no cambian mientras {@code MONETARY} se mantiene
     * (implementation_plan.md §3.1). Las invariantes de la nueva configuración las garantiza
     * {@link ConvocatoriaConfiguration}.
     */
    public MonetaryTransition reconfigure(ConvocatoriaConfiguration newConfiguration) {
        Objects.requireNonNull(newConfiguration, "newConfiguration");
        if (status == ConvocatoriaStatus.CLOSED) {
            throw new ConfigurationChangeOnClosedCampaignException("Campaign " + campaignRef + " is CLOSED");
        }
        boolean hadMonetary = configuration.acceptsMonetary();
        boolean hasMonetary = newConfiguration.acceptsMonetary();
        if (hadMonetary && hasMonetary && !configuration.hasSameMonetaryTermsAs(newConfiguration)) {
            throw new MonetaryTermsChangeNotSupportedException(
                    "targetAmount, targetPolicy, onTargetReached and currency cannot be changed");
        }
        this.configuration = newConfiguration;
        this.configurationVersion = configurationVersion + 1;
        if (!hadMonetary && hasMonetary) {
            return MonetaryTransition.ADDED;
        }
        if (hadMonetary && !hasMonetary) {
            return MonetaryTransition.REMOVED;
        }
        return MonetaryTransition.UNCHANGED;
    }

    /** Cierre manual {@code OPEN → CLOSED}; no existe {@code CLOSED → OPEN} (Enmienda §3.4). */
    public void close() {
        if (status != ConvocatoriaStatus.OPEN) {
            throw new CampaignAlreadyClosedException("Campaign " + campaignRef + " is already CLOSED");
        }
        this.status = ConvocatoriaStatus.CLOSED;
    }

    /**
     * Precondiciones de creación de {@code DonationIntent} que dependen de la convocatoria
     * (ADR-037 §2.6; N6, Enmienda §5.1; R1; implementation_plan.md §9.1). La verificación de la
     * organización (X1) la hace el caso de uso. Las fechas no se comprueban (P7).
     */
    public void assertAcceptsDonationIntent(PaymentMethod paymentMethod, String currency) {
        Objects.requireNonNull(paymentMethod, "paymentMethod");
        if (status != ConvocatoriaStatus.OPEN) {
            throw new CampaignClosedException("Campaign " + campaignRef + " is not OPEN");
        }
        if (!configuration.acceptsMonetary()) {
            throw new DonationTypeNotAcceptedException("Campaign " + campaignRef + " does not accept MONETARY");
        }
        if (!configuration.acceptedPaymentMethods().contains(paymentMethod)) {
            throw new PaymentMethodNotAcceptedException("Payment method " + paymentMethod + " is not accepted");
        }
        if (paymentMethod == PaymentMethod.CASH) {
            throw new CashDonationIntentNotSupportedException(
                    "CASH does not create a DonationIntent in this cut (pending P5)");
        }
        if (!configuration.currency().equals(currency)) {
            throw new DonationCurrencyMismatchException(
                    "Currency " + currency + " differs from campaign currency " + configuration.currency());
        }
    }

    public String getCampaignRef() { return campaignRef; }
    public String getOrganizationRef() { return organizationRef; }
    public String getPublicCode() { return publicCode; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Visibility getVisibility() { return visibility; }
    public Instant getStartDate() { return startDate; }
    public Instant getEndDate() { return endDate; }
    public ConvocatoriaStatus getStatus() { return status; }
    public ConvocatoriaConfiguration getConfiguration() { return configuration; }
    public long getConfigurationVersion() { return configurationVersion; }

    /** Efecto de una edición sobre {@code MONETARY}, usado para crear o retirar el ledger (N2). */
    public enum MonetaryTransition {
        ADDED,
        REMOVED,
        UNCHANGED
    }
}
