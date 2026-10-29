package com.traceability.convocatoria.application.query;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Ficha R-1 §4 y §8, test 1: la misma normalización para el título y para {@code q}. */
class SearchTextNormalizerTest {

    @Test
    void capitalsAndAccents_normalizeToTheSameText() {
        assertThat(SearchTextNormalizer.normalize("Educación Rural")).isEqualTo("educacion rural");
        assertThat(SearchTextNormalizer.normalize("EDUCACION rural")).isEqualTo("educacion rural");
        assertThat(SearchTextNormalizer.normalize("educación rural")).isEqualTo("educacion rural");
        assertThat(SearchTextNormalizer.normalize("Niños de Bogotá ÜÑÍ")).isEqualTo("ninos de bogota uni");
    }

    @Test
    void anAlreadyDecomposedAccent_isRemovedToo() {
        // "e" + U+0301 (tilde combinante), como lo enviaría un teclado que no compone
        assertThat(SearchTextNormalizer.normalize("Educación")).isEqualTo("educacion");
    }

    @Test
    void regexCharactersInTheQuery_areSearchedLiterally() {
        for (String q : new String[] {".*", "(", "[", "\\E", "a+b", "$^|?{}"}) {
            String title = SearchTextNormalizer.normalize("Antes " + q + " después");
            Pattern literal = Pattern.compile(Pattern.quote(SearchTextNormalizer.normalize(q)));
            assertThat(literal.matcher(title).find()).as(q).isTrue();
            assertThat(literal.matcher(SearchTextNormalizer.normalize("Antes sin nada después")).find())
                    .as(q + " no actúa como operador").isFalse();
        }
    }
}
