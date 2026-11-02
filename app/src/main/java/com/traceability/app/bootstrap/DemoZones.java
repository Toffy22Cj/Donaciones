package com.traceability.app.bootstrap;

import com.traceability.core.application.service.LocationReferenceService;

/**
 * ADR-052 D4: el catálogo de zonas públicas de la demo, solo local. Cada referencia de lugar que usan las semillas
 * ({@link DemoScenarioSeeder}) y {@code scripts/demo/recorrido.py} ({@link DemoSeedRunner}) va a la zona de su ciudad,
 * con el punto del centro de la ciudad: nunca el de la bodega o el punto de entrega (D1). Guardar otra vez la misma
 * entrada la sustituye, así que es idempotente.
 */
final class DemoZones {

    static final String CARTAGENA = "Cartagena, Bolívar";
    static final String BARRANQUILLA = "Barranquilla, Atlántico";

    private DemoZones() {
    }

    static void register(LocationReferenceService locations) {
        // escenario de la presentación (DemoScenarioSeeder)
        locations.register("bodega-central", CARTAGENA, 10.3932, -75.4832);
        locations.register("centro-comunitario", BARRANQUILLA, 10.9639, -74.7964);
        // golden path de scripts/demo/recorrido.py (semilla mínima, DemoSeedRunner)
        locations.register("bodega-1", CARTAGENA, 10.3932, -75.4832);
        locations.register("centro-1", BARRANQUILLA, 10.9639, -74.7964);
    }
}
