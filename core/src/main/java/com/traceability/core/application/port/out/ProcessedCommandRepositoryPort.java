package com.traceability.core.application.port.out;

public interface ProcessedCommandRepositoryPort {
    void save(String commandId);
    boolean exists(String commandId);
}
