package com.traceability.app.infrastructure.media;

import com.traceability.contracts.media.MediaStoragePort;
import com.traceability.contracts.media.MediaStorageUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Adaptador local, solo con el perfil {@code dev} (ADR-051 F1: "directorio temporal"; lo decide
 * {@link MediaStorageConfig}). Un fichero por clave en {@code traceability.media.local.directory}. Guardar escribe un
 * temporal del mismo directorio y lo mueve de forma atómica, así que repetir la misma clave es idempotente (F2). Un
 * fallo de disco es {@link MediaStorageUnavailableException}, sin reintento.
 */
public class LocalMediaStorageAdapter implements MediaStoragePort {

    private static final Logger log = LoggerFactory.getLogger(LocalMediaStorageAdapter.class);
    /** Las claves las genera {@code MediaUploadService} (ULID, ADR-051 F2); otra cosa es un error de programación. */
    private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

    private final Path directory;

    public LocalMediaStorageAdapter(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, objectKey, ".tmp");
            try {
                Files.write(temporary, content);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw failure("put", objectKey, e);
        }
    }

    @Override
    public Optional<byte[]> get(String objectKey) {
        Path target = resolve(objectKey);
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw failure("get", objectKey, e);
        }
    }

    @Override
    public void delete(String objectKey) {
        Path target = resolve(objectKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw failure("delete", objectKey, e);
        }
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || !ULID.matcher(objectKey).matches()) {
            throw new IllegalArgumentException("The object key must be a ULID");
        }
        Path target = directory.resolve(objectKey).normalize();
        if (!target.getParent().equals(directory)) {
            throw new IllegalArgumentException("The object key must name a file inside the media directory");
        }
        return target;
    }

    private static MediaStorageUnavailableException failure(String operation, String objectKey, IOException e) {
        log.error("media.storage.{}.failed objectKey={} attempt=1 retry=false cause={}", operation, objectKey,
                e.getClass().getSimpleName());
        return new MediaStorageUnavailableException("Media storage unavailable");
    }
}
