package com.traceability.app.infrastructure.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Plan] P-R7-1 y P-R7-2: el archivo DIVIPOLA real que aportó Manuel ({@code src/main/resources/divipola/divipola.json},
 * copiado tal cual) tiene la forma del plan. Los tests de la aplicación usan la muestra de {@code src/test/resources},
 * así que este test lee el real directamente del disco.
 */
class DivipolaCatalogFileTest {

    @Test
    void theRealCatalog_has33Departments_and1122Entries_withUniqueCodesInsideTheirDepartment() throws Exception {
        JsonNode catalog = new ObjectMapper().readTree(Files.readString(Path.of("src/main/resources/divipola/divipola.json")));

        assertThat(catalog.get("catalogVersion").asText()).isEqualTo("DIVIPOLA-2026-09");
        assertThat(catalog.get("departments")).hasSize(33);
        Set<String> codes = new HashSet<>();
        int entries = 0;
        for (JsonNode d : catalog.get("departments")) {
            assertThat(d.get("code").asText()).matches("\\d{2}");
            assertThat(codes.add(d.get("code").asText())).isTrue();
            for (JsonNode m : d.get("municipalities")) {
                assertThat(m.get("code").asText()).matches("\\d{5}").startsWith(d.get("code").asText());
                assertThat(m.get("name").asText()).isNotBlank();
                assertThat(codes.add(m.get("code").asText())).isTrue();
                entries++;
            }
        }
        assertThat(entries).isEqualTo(1122);
        // el catálogo se lee también con la clase de producción
        DivipolaLocationCatalog real = DivipolaLocationCatalog.load(Files.newInputStream(Path.of("src/main/resources/divipola/divipola.json")));
        assertThat(real.contains("05", "05001")).isTrue();
        assertThat(real.contains("05", "11001")).isFalse();
    }
}
