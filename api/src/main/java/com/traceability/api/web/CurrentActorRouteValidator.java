package com.traceability.api.web;

import com.traceability.api.auth.PublicRoutes;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Comprueba al arrancar los parámetros de B6-0 (plan §2.1, test 3). La aplicación no arranca si:
 * <ul>
 *   <li>un {@link CurrentActor} no es {@code HumanActor}, {@code AuthorizationPrincipal} ni un {@code Optional} de ellos;</li>
 *   <li>un {@link CurrentActor} que no es {@code Optional} está en una ruta pública o de JWT opcional de
 *       {@link PublicRoutes}: ahí puede no haber principal, y un actor obligatorio daría un 500 en cada petición anónima;</li>
 *   <li>un {@link CommandId} no es {@code String}.</li>
 * </ul>
 * Solo en una aplicación servlet: un contexto sin web (arranque de {@code app} en tests) no tiene rutas que validar.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CurrentActorRouteValidator implements SmartInitializingSingleton {

    private final RequestMappingHandlerMapping mappings;

    public CurrentActorRouteValidator(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
        this.mappings = mappings;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> errors = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            for (MethodParameter parameter : handler.getMethodParameters()) {
                String where = handler.getBeanType().getName() + "#" + handler.getMethod().getName();
                if (parameter.hasParameterAnnotation(CommandId.class) && parameter.getParameterType() != String.class) {
                    errors.add(where + ": @CommandId must be a String");
                }
                if (!parameter.hasParameterAnnotation(CurrentActor.class)) {
                    continue;
                }
                if (!CurrentActorArgumentResolver.isSupported(CurrentActorArgumentResolver.targetType(parameter))) {
                    errors.add(where + ": @CurrentActor must be HumanActor, AuthorizationPrincipal or an Optional of them");
                } else if (!CurrentActorArgumentResolver.isOptional(parameter)) {
                    for (String route : anonymousRoutes(entry.getKey())) {
                        errors.add(where + ": " + route + " may have no principal; @CurrentActor there must be Optional");
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid B6-0 controller parameters:\n  " + String.join("\n  ", errors));
        }
    }

    /** Las combinaciones método + patrón de la ruta que {@link PublicRoutes} declara públicas o de JWT opcional. */
    private static Set<String> anonymousRoutes(RequestMappingInfo info) {
        Set<String> methods = new TreeSet<>();
        info.getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
        if (methods.isEmpty()) {
            for (RequestMethod m : RequestMethod.values()) methods.add(m.name());
        }
        Set<String> anonymous = new TreeSet<>();
        for (String pattern : info.getPatternValues()) {
            String concrete = pattern.replaceAll("\\{[^/]+}", "x").replace("**", "x");
            for (String method : methods) {
                if (PublicRoutes.accessFor(method, concrete).isPresent()) {
                    anonymous.add(method + " " + pattern);
                }
            }
        }
        return anonymous;
    }
}
