package com.traceability.api.web;

import com.traceability.api.auth.jwt.JwtAuthFilter;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.core.domain.event.HumanActor;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Optional;

/**
 * Resuelve {@link CurrentActor} con el principal que dejó {@link JwtAuthFilter} (plan B6-0 §2.1). Es la única pieza
 * que lee {@link JwtAuthFilter#PRINCIPAL_ATTRIBUTE}; ArchUnit impide que un controlador lo haga.
 */
public class CurrentActorArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentActor.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object attribute = webRequest.getAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        AuthorizationPrincipal principal = attribute instanceof AuthorizationPrincipal p ? p : null;
        Object actor = principal == null ? null : adapt(principal, targetType(parameter));
        if (isOptional(parameter)) {
            return Optional.ofNullable(actor);
        }
        if (actor == null) {
            // El filtro ya habría respondido 401 en una ruta protegida: llegar aquí es un error de programación.
            throw new MissingAuthenticatedActorException();
        }
        return actor;
    }

    static boolean isOptional(MethodParameter parameter) {
        return parameter.getParameterType() == Optional.class;
    }

    /** El tipo pedido, sin el {@code Optional}. */
    static Class<?> targetType(MethodParameter parameter) {
        return isOptional(parameter) ? parameter.nested().getNestedParameterType() : parameter.getParameterType();
    }

    static boolean isSupported(Class<?> type) {
        return type == HumanActor.class || type == AuthorizationPrincipal.class;
    }

    private static Object adapt(AuthorizationPrincipal principal, Class<?> type) {
        if (type == HumanActor.class) {
            return new HumanActor(principal.accountId());
        }
        if (type == AuthorizationPrincipal.class) {
            return principal;
        }
        throw new IllegalStateException("Unsupported @CurrentActor type: " + type.getName());
    }
}
