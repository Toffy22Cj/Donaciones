package com.traceability.api.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Parámetro {@code String} con la cabecera {@code Command-Id} (T-33, ampliado a {@code core} por D-API Q9): obligatoria,
 * UUID, normalizada a minúsculas (Q-B60-3). Ausente, vacía o no UUID → 400 antes de llamar al caso de uso.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CommandId {
}
