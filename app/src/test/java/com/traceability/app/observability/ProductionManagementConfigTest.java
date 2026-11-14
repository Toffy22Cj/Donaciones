package com.traceability.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * ADR-054 D1 sobre el {@code application.yml} de producción (en los tests lo sustituye el de {@code src/test}): la
 * salud solo en 127.0.0.1, en un puerto distinto del de la API, y solo health e info.
 */
class ProductionManagementConfigTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> at(Map<String, Object> root, String... path) {
        Map<String, Object> node = root;
        for (String key : path) {
            node = (Map<String, Object>) node.get(key);
            assertThat(node).as(String.join(".", path)).isNotNull();
        }
        return node;
    }

    @Test
    void managementIsLocalOnlyOnItsOwnPortWithOnlyHealthAndInfo() throws Exception {
        Map<String, Object> yml = new Yaml().load(Files.readString(Path.of("src/main/resources/application.yml")));
        Map<String, Object> server = at(yml, "management", "server");
        assertThat(server.get("address")).isEqualTo("127.0.0.1");
        assertThat(String.valueOf(server.get("port"))).isEqualTo("${MANAGEMENT_SERVER_PORT:8081}");
        assertThat(at(yml, "management", "endpoints", "access").get("default")).isEqualTo("none");
        assertThat(at(yml, "management", "endpoints", "web", "exposure").get("include")).isEqualTo("health,info");
        assertThat(at(yml, "management", "health", "mail").get("enabled")).isEqualTo(false);
    }

    @Test
    void theServerProfileTurnsOnJsonLogs() throws Exception {
        Map<String, Object> yml = new Yaml().load(Files.readString(Path.of("src/main/resources/application-servidor.yml")));
        assertThat(at(yml, "logging", "structured", "format").get("console")).isEqualTo("ecs");
    }
}
