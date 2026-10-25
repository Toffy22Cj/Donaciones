package com.traceability.app.config;

import identity.domain.model.LegalVersions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Versiones vigentes de los textos legales (ADR-050 D2; plan R-5, P-R5-3): se leen de {@code traceability.legal.*} y se
 * entregan a {@code identity} como {@link LegalVersions}. Si alguna falta o está en blanco, la aplicación no arranca,
 * como con el correo (DD-61). En {@code application.yml} no tienen valor por defecto; dev y los tests usan
 * {@code v1-2026-10}.
 */
@Configuration
public class LegalVersionsConfig {

    @Bean
    public LegalVersions legalVersions(@Value("${traceability.legal.terms-version:}") String terms,
                                       @Value("${traceability.legal.data-policy-version:}") String dataPolicy,
                                       @Value("${traceability.legal.organization-terms-version:}") String organizationTerms) {
        return of(terms, dataPolicy, organizationTerms);
    }

    static LegalVersions of(String terms, String dataPolicy, String organizationTerms) {
        requireText(terms, "TRACEABILITY_LEGAL_TERMS_VERSION");
        requireText(dataPolicy, "TRACEABILITY_LEGAL_DATA_POLICY_VERSION");
        requireText(organizationTerms, "TRACEABILITY_LEGAL_ORGANIZATION_TERMS_VERSION");
        return new LegalVersions(terms.strip(), dataPolicy.strip(), organizationTerms.strip());
    }

    private static void requireText(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variable + " es obligatoria (ADR-050 D2)");
        }
    }
}
