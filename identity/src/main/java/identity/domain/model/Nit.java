package identity.domain.model;

import identity.domain.exception.InvalidOrganizationProfileFieldException;

import java.util.regex.Pattern;

/**
 * [Plan] NIT de 9 dígitos y su dígito de verificación (ficha R-7 §3 y §7). El dígito se comprueba con el algoritmo de
 * la DIAN: pesos 3, 7, 13, 17, 19, 23, 29, 37 y 41 de derecha a izquierda; {@code r = suma mod 11}; el dígito es
 * {@code r} si {@code r} es 0 o 1, y {@code 11 − r} si no.
 */
public record Nit(String number, String checkDigit) {

    private static final int[] WEIGHTS = {3, 7, 13, 17, 19, 23, 29, 37, 41};
    private static final Pattern NINE_DIGITS = Pattern.compile("\\d{9}");
    private static final Pattern ONE_DIGIT = Pattern.compile("\\d");

    public Nit {
        if (number == null || !NINE_DIGITS.matcher(number).matches()) {
            throw new InvalidOrganizationProfileFieldException("nit");
        }
        if (checkDigit == null || !ONE_DIGIT.matcher(checkDigit).matches()
                || Integer.parseInt(checkDigit) != checkDigitOf(number)) {
            throw new InvalidOrganizationProfileFieldException("nitCheckDigit");
        }
    }

    public static Nit of(String number, String checkDigit) {
        return new Nit(number, checkDigit);
    }

    /** El dígito de verificación de 9 dígitos (§7). */
    public static int checkDigitOf(String nineDigits) {
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += (nineDigits.charAt(8 - i) - '0') * WEIGHTS[i];
        }
        int r = sum % 11;
        return r <= 1 ? r : 11 - r;
    }
}
