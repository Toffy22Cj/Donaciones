package com.traceability.api.archfixture;

import com.traceability.convocatoria.application.query.CampaignTitleQuery;
import identity.application.service.OrganizationPublicNameQuery;

/**
 * Violación deliberada para {@code ApiModuleDependenciesArchitectureTest} (ficha R-6, test 4): una clase de
 * {@code api} que usa {@code convocatoria} e {@code identity} directamente, en lugar del puerto de {@code contracts}.
 */
public class ApiUsingConvocatoriaAndIdentity {

    CampaignTitleQuery titles;
    OrganizationPublicNameQuery names;
}
