package com.traceability.app.infrastructure.media;

import com.traceability.contracts.media.MediaStorageUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adaptador local del perfil {@code dev} (ADR-051 F1): el contrato de {@link com.traceability.contracts.media.MediaStoragePort}
 * sobre un directorio, sin recorrido de rutas (P-5).
 */
class LocalMediaStorageAdapterTest {

    static final String KEY = "01JB2Z3Y4X5W6V7T8S9R0QPNMK";
    static final byte[] BYTES = {1, 2, 3, 4, 5};

    @TempDir
    Path directory;

    @Test
    void putGetDelete_roundTrip() {
        LocalMediaStorageAdapter adapter = new LocalMediaStorageAdapter(directory);

        adapter.put(KEY, BYTES, "image/png");
        assertThat(adapter.get(KEY)).hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(BYTES));

        adapter.delete(KEY);
        assertThat(adapter.get(KEY)).isEmpty();
    }

    // ADR-051 F2: guardar con la misma clave y los mismos bytes es idempotente
    @Test
    void put_sameKeyTwice_isIdempotent_andLeavesNoTemporaryFiles() throws IOException {
        LocalMediaStorageAdapter adapter = new LocalMediaStorageAdapter(directory);

        adapter.put(KEY, BYTES, "image/png");
        adapter.put(KEY, BYTES, "image/png");

        assertThat(adapter.get(KEY)).hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(BYTES));
        try (Stream<Path> files = Files.list(directory)) {
            assertThat(files).extracting(p -> p.getFileName().toString()).containsExactly(KEY);
        }
    }

    @Test
    void get_missingKey_isEmpty_andDelete_missingKey_doesNotFail() {
        LocalMediaStorageAdapter adapter = new LocalMediaStorageAdapter(directory);

        assertThat(adapter.get(KEY)).isEmpty();
        assertThatCode(() -> adapter.delete(KEY)).doesNotThrowAnyException();
    }

    @Test
    void theReturnedBytes_areACopy() {
        LocalMediaStorageAdapter adapter = new LocalMediaStorageAdapter(directory);
        byte[] content = BYTES.clone();
        adapter.put(KEY, content, "image/png");
        content[0] = 99;

        assertThat(adapter.get(KEY).orElseThrow()).isEqualTo(BYTES);
    }

    @Test
    void aMissingDirectory_isCreatedOnFirstPut() {
        Path nested = directory.resolve("paxfide-media");
        LocalMediaStorageAdapter adapter = new LocalMediaStorageAdapter(nested);

        adapter.put(KEY, BYTES, "image/png");

        assertThat(nested.resolve(KEY)).exists();
    }

    @Test
    void aDiskFailure_isUnavailable() throws IOException {
        Path notADirectory = Files.writeString(directory.resolve("file"), "x");
        LocalMediaStorageAdapter adapter = new LocalMediaStorageAdapter(notADirectory);

        assertThatThrownBy(() -> adapter.put(KEY, BYTES, "image/png"))
                .isInstanceOf(MediaStorageUnavailableException.class);
    }

    // P-5: una clave que no es ULID es un error de programación, y nunca sale del directorio
    @ParameterizedTest
    @ValueSource(strings = {"../escape", "a/b", "..", "", "01jb2z3y4x5w6v7t8s9r0qpnmk", "01JB2Z3Y4X5W6V7T8S9R0QPNM"})
    void aKeyThatIsNotAUlid_isRejected(String key) {
        LocalMediaStorageAdapter adapter = new LocalMediaStorageAdapter(directory.resolve("media"));

        assertThatThrownBy(() -> adapter.put(key, BYTES, "image/png")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.get(key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.delete(key)).isInstanceOf(IllegalArgumentException.class);
        assertThat(directory.resolve("escape")).doesNotExist();
    }
}
