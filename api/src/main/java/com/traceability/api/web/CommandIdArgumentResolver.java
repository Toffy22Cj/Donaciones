package com.traceability.api.web;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Resuelve {@link CommandId}: la cabecera debe ser un UUID en su forma canónica de 36 caracteres; se entrega en
 * minúsculas (Q-B60-3), para que la misma orden escrita en mayúsculas no cuente como otra. {@code UUID.fromString}
 * no sirve: acepta formas no canónicas como {@code 1-1-1-1-1}.
 */
public class CommandIdArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER = "Command-Id";

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CommandId.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String value = webRequest.getHeader(HEADER);
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw new InvalidCommandIdException();
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
