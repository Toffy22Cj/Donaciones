package com.traceability.app.infrastructure.media;

import com.traceability.contracts.media.MediaStoragePort;
import com.traceability.contracts.media.MediaStorageUnavailableException;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Adaptador en memoria para tests (ADR-051 F1, F9). Cumple el contrato de {@link MediaStoragePort} con copias
 * defensivas, y permite simular que el proveedor falla en los siguientes {@code put}.
 */
public class InMemoryMediaStorageAdapter implements MediaStoragePort {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Map<String, String> contentTypes = new ConcurrentHashMap<>();
    private final AtomicInteger failingPuts = new AtomicInteger();

    /** Los siguientes {@code times} {@code put} lanzan {@link MediaStorageUnavailableException} sin guardar nada. */
    public void failNextPuts(int times) {
        failingPuts.set(times);
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        if (failingPuts.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new MediaStorageUnavailableException("Media storage unavailable (simulated)");
        }
        objects.put(objectKey, content.clone());
        contentTypes.put(objectKey, contentType);
    }

    @Override
    public Optional<byte[]> get(String objectKey) {
        return Optional.ofNullable(objects.get(objectKey)).map(byte[]::clone);
    }

    @Override
    public void delete(String objectKey) {
        objects.remove(objectKey);
        contentTypes.remove(objectKey);
    }

    public Set<String> keys() {
        return Set.copyOf(objects.keySet());
    }

    public String contentTypeOf(String objectKey) {
        return contentTypes.get(objectKey);
    }
}
