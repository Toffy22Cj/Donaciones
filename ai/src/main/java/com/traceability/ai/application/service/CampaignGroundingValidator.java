package com.traceability.ai.application.service;

import com.traceability.ai.domain.narrative.CampaignCitedFact;
import com.traceability.ai.domain.narrative.CampaignFactType;
import com.traceability.ai.domain.narrative.CampaignLlmNarrativeResponse;
import com.traceability.ai.domain.narrative.CampaignNarrativeFacts;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grounding de la narrativa de convocatoria (plan B5 §3; condiciones 2 y 3 de Carlos). Se valida contra los mismos
 * hechos que recibió el LLM. Rechaza si:
 * <ul>
 *   <li>no hay texto o no cita ningún hecho;</li>
 *   <li>cita un hecho que no existe o con un valor distinto del exacto;</li>
 *   <li>el texto contiene una cifra que no es el valor de un hecho citado;</li>
 *   <li>el texto habla de familias u hogares (ADR-040 §2.1: "receptores distintos", nunca "familias alcanzadas").</li>
 * </ul>
 */
@Component
public class CampaignGroundingValidator {

    private static final Pattern NUMBER = Pattern.compile("\\d(?:[\\d.,]*\\d)?");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(familia|familias|familiar|familiares|hogar|hogares|family|families|household|households)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public boolean validate(CampaignLlmNarrativeResponse response, CampaignNarrativeFacts facts) {
        if (response == null || response.narrativeText() == null || response.narrativeText().isBlank()
                || response.citedFacts() == null || response.citedFacts().isEmpty()) {
            return false;
        }
        Map<CampaignFactType, String> citable = facts.citable();
        Set<String> citedNumbers = new HashSet<>();
        for (CampaignCitedFact cited : response.citedFacts()) {
            if (cited == null || cited.type() == null || cited.value() == null
                    || !cited.value().equals(citable.get(cited.type()))) {
                return false;
            }
            citedNumbers.add(digitsOf(cited.value()));
        }
        String text = response.narrativeText();
        if (FORBIDDEN.matcher(text).find()) {
            return false;
        }
        Matcher numbers = NUMBER.matcher(text);
        while (numbers.find()) {
            if (!citedNumbers.contains(digitsOf(numbers.group()))) {
                return false;
            }
        }
        return true;
    }

    /** "60.000", "60,000" y "60000" son la misma cifra; "15,5" y "15.5" también. */
    private static String digitsOf(String number) {
        return number.replace(".", "").replace(",", "");
    }
}
