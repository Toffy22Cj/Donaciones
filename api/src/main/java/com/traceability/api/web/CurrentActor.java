package com.traceability.api.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Parámetro de controlador con el actor autenticado por el JWT (plan B6-0 §2.1). Tipos admitidos: {@code HumanActor},
 * {@code AuthorizationPrincipal}, y {@code Optional} de cualquiera de los dos. En una ruta de JWT opcional (o pública)
 * el parámetro debe ser {@code Optional}; si no, la aplicación no arranca.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentActor {
}
