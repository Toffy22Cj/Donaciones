package com.traceability.app.web.campaign;

import com.traceability.api.web.InvalidRequestFieldException;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Traducción de los campos de texto de CV-01 al dominio (plan B6-a §2.1). Todo fallo es
 * {@link InvalidRequestFieldException} (400, sin eco del valor).
 */
final class CampaignRequestFields {

    /** ISO-8601 UTC terminado en {@code Z} (T-34; la ficha CV-01 lo exige; Jackson aceptaría desplazamientos). */
    private static final Pattern UTC_INSTANT =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,19}$");

    private CampaignRequestFields() {}

    static Instant instant(String value, String field) {
        if (value == null || !UTC_INSTANT.matcher(value).matches()) {
            throw new InvalidRequestFieldException(field);
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            throw new InvalidRequestFieldException(field);
        }
    }

    /** Dígitos que caben en un {@code long}; ausente → {@code null} (lo decide el dominio). */
    static Long amount(String value, String field) {
        if (value == null) {
            return null;
        }
        if (!DIGITS.matcher(value).matches()) {
            throw new InvalidRequestFieldException(field);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new InvalidRequestFieldException(field);
        }
    }

    /** Literal del dominio; ausente → {@code null} (lo decide el dominio); desconocido → 400. */
    static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestFieldException(field);
        }
    }

    static <E extends Enum<E>> Set<E> enumSet(Class<E> type, List<String> values, String field) {
        EnumSet<E> set = EnumSet.noneOf(type);
        if (values != null) {
            for (String value : values) {
                if (value == null) {
                    throw new InvalidRequestFieldException(field);
                }
                set.add(enumValue(type, value, field));
            }
        }
        return set;
    }

    static <T> T required(T value, String field) {
        if (value == null) {
            throw new InvalidRequestFieldException(field);
        }
        return value;
    }
}
