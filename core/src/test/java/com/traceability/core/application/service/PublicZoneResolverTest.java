package com.traceability.core.application.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Enmienda 2 de ADR-029, D5 (plan de la zona, test 8): la zona sellada gana; sin ella, el respaldo de
 * {@code location_reference} con el texto libre del mismo evento; sin entrada, nada.
 */
class PublicZoneResolverTest {

    private final LocationReferenceService locations = mock(LocationReferenceService.class);
    private final PublicZoneResolver resolver = new PublicZoneResolver(locations);

    @Test
    void test8_theSealedZoneWins_andLocationReferenceIsNotEvenRead() {
        when(locations.resolveZone(anyString())).thenReturn(Optional.of("Zona de la tabla"));

        assertThat(resolver.resolve("MEDELLÍN, ANTIOQUIA", "Bodega norte")).contains("MEDELLÍN, ANTIOQUIA");
        verify(locations, never()).resolveZone(anyString());
    }

    @Test
    void test8_withoutSealedZone_itFallsBackToLocationReference_withTheSameEventsText() {
        when(locations.resolveZone("Bodega vieja")).thenReturn(Optional.of("Barranquilla, Atlántico"));

        assertThat(resolver.resolve(null, "Bodega vieja")).contains("Barranquilla, Atlántico");
        assertThat(resolver.resolve(null, "Sin entrada")).isEmpty();
        assertThat(resolver.resolve(null, null)).isEmpty();
    }
}
