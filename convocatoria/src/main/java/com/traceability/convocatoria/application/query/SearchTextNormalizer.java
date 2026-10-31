package com.traceability.convocatoria.application.query;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalización de la búsqueda del descubrimiento (ficha R-1 §4), la misma para el título ({@code titleSearch}) y para
 * {@code q}: NFD, sin marcas diacríticas combinantes ({@code \p{M}}) y en minúsculas con {@link Locale#ROOT}. Así
 * "Educación", "EDUCACION" y "educación" quedan como {@code educacion}.
 */
public final class SearchTextNormalizer {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private SearchTextNormalizer() {
    }

    public static String normalize(String text) {
        return MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
