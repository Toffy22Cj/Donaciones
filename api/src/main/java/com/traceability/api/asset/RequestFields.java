package com.traceability.api.asset;

import com.traceability.api.web.InvalidRequestFieldException;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Validación de la forma de los cuerpos de activos (plan B6-c §2.2). Un fallo es un 400 sin eco del valor. */
final class RequestFields {

    static final int MAX_TEXT = 256;

    /** Decimal positivo con hasta 4 decimales, la escala del dominio; sin signo, exponente ni separador de miles. */
    private static final Pattern QUANTITY = Pattern.compile("^[0-9]{1,15}(\\.[0-9]{1,4})?$");

    private RequestFields() {}

    static String required(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT) {
            throw new InvalidRequestFieldException(field);
        }
        return value;
    }

    static String optional(String value, String field) {
        return value == null ? null : required(value, field);
    }

    static BigDecimal quantity(String value, String field) {
        if (value == null || !QUANTITY.matcher(value).matches()) {
            throw new InvalidRequestFieldException(field);
        }
        BigDecimal quantity = new BigDecimal(value);
        if (quantity.signum() <= 0) {
            throw new InvalidRequestFieldException(field);
        }
        return quantity;
    }
}
