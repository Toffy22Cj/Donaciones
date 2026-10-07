# Diseño UX contractual — `paxfide-web` v1

*Nota de numeración (2026-10-07, decisión de Carlos):* el ADR del frontend web `paxfide-web` se renumera de ADR-042 a **ADR-046** (`ADR-046-frontend-web-paxfide-web.md`, repositorio `Toffy22Cj/PaxFide`) por colisión con `ADR-042-orquestacion-centralizada-reintentos-proyeccion.md`, que conserva su número. Las referencias de este documento se actualizaron.

**Estado:** **APROBADO (2026-10-05)**, revisión 4 (revisión 2 + enmienda de §3.3 + `PanelHome`, todo del mismo día, ver §9). Aprobación humana explícita de Carlos, en bloque, de las 9 decisiones de §9. Ver el registro en §9.
**Qué significa APROBADO aquí:** las decisiones marcadas PROPUESTO en este texto quedan aprobadas. **No** habilita ninguna superficie: Login, `AssetPage`, `split` y `/c` siguen BLOQUEADOS por D5 hasta resolver sus dependencias. Tampoco aprueba la Enmienda 1 de ADR-041, ni convierte en normativas las fichas de contratos.

**Cambios de la revisión 4:**
- Entra en el alcance el contenido de `PanelHome` (§5.6) y el estado "sin entradas" (§6), según `claude/delta-ux-panelhome.md` (APROBADO 2026-10-05), que aplica `claude/delta-front-fase2-N1.md` (APROBADO 2026-10-05).
- `EmptyState` pasa a usarse (§4). Nuevo frame de Penpot (§8).
- **No habilita nada:** `/panel` y `/panel/campaigns` siguen aprobado-bloqueadas (404 por D5). N1 sigue sin ser normativa (Enmienda 1 de ADR-041).

**Cambios de la revisión 3:**
- §3.3 completa los nombres y valores que faltaban: `shadow-modal`, pesos, alto de control y anchos. Aprobado por Carlos el 2026-10-05 (§9, decisión 10).

**Cambios de la revisión 2:**
- `split` pasa de "frame futuro" a "diseñado, BLOQUEADO para despliegue" (§5.3).
- Se precisa la presentación de `quantity` (§5.3).
- R-UX-2 deja de ser requisito y pasa a nota de dependencia. El destino `/panel` se mantiene porque es HEREDADO de ADR-046 D4 (§5.2).

**No es un ADR ni un mockup.** Fija estructura, estados, componentes, contenido permitido y comportamiento. **Penpot es la fuente visual** y traduce este documento; no puede añadir campos, acciones ni pantallas que aquí no estén.
**Alcance:** Shell, Login, `AssetPage`, `PanelHome` (desde la revisión 4), estados globales, componentes base y `/c` solo como estructura. **El listado de convocatorias (`OrgCampaignsPage` con datos) y el formulario de "Registrar activo" quedan fuera** hasta que existan sus contratos.

**Fuentes:** `ADR-046-frontend-web-paxfide-web.md` (APROBADO), `claude/front-fase2.md`, `hallazgos-front-fase2.md`, `sistema-visual-paxfide-web.md` (APROBADO), `api-contract-matrix.md`, fichas de contratos API Fase 6 (material de trabajo, **no normativo** hasta que se apruebe la Enmienda 1 de ADR-041), y las decisiones de Carlos del 2026-10-05.

### Etiquetas

| Etiqueta | Significado |
|---|---|
| **HEREDADO** | Decisión aprobada en otro documento; aquí solo se aplica |
| **PROPUESTO** | Decisión nueva de este documento. **Aprobada el 2026-10-05** (§9); la etiqueta se conserva para distinguir lo que este documento decide de lo que hereda |
| **BLOQUEADO** | Diseño definido, pero la superficie o acción no se habilita (D5) hasta resolver la dependencia nombrada |
| **PENDIENTE** | Falta una decisión o un contrato; no se diseña |

---

## 1. Decisiones de partida (HEREDADO, Carlos 2026-10-05)

1. `/assets` en web: alcance de lectura + `split` (T3). Sin Dispatch, Receive ni Deliver, y sin `ActionResolver`. `split` está diseñado aquí; su despliegue lo decide D5.
2. `/c/:publicCode`: sin Donar, sin imagen ni meta mientras E3 no cierre esos campos.
3. Navegación: solo rutas del árbol aprobado (`front-fase2` §7). Sin Dashboard, Administración ni Platform Admin.
4. Listado de convocatorias bloqueado por N1.
5. Colores y tipografía no se reabren; se diseña solo lo que falta.
6. Listados por cursor con "Cargar más"; no hay detalle de convocatoria sin contrato.
7. Consecuencias absorbidas de las fichas (se aplican cuando sean normativas): `Command-Id` UUID en header; JWT de 1 h sin refresh; importes como `String`; instantes UTC mostrados en hora local; `ProblemDetail` nunca se muestra crudo; CV-03 es una acción transitoria aprobada pero bloqueada.
8. **Iconografía: SVG propios**, sin librería (D8).
9. **Dirección de estilo:** referencias Dovetail y Zapier, aplicadas con las restricciones de §2.

---

## 2. Dirección de estilo (PROPUESTO)

Superficies claras y neutras, mucho espacio en blanco, bordes de 1 px y color reservado a **acción** y **estado**.

| Rasgo | Regla |
|---|---|
| Fondo | Página en `neutral-50`; superficies de contenido (paneles, modal, formularios) en `white` |
| Color | `brand-green-900` solo para acción primaria, foco y títulos. Amarillo, rojo y azul solo para estados (`sistema-visual` §4) |
| Densidad | Holgada; una idea por bloque |
| Jerarquía | Por tamaño y peso tipográfico, no por color |
| Adoptado de las referencias | Ítem activo resaltado, botón primario sólido, modal con fondo atenuado y Cancelar + Primario, tablas con cabecera y separadores finos |
| **Excluido** de las referencias | Buscador, filtros, pestañas, paginación numérica, avatares, tiempos relativos sin fecha absoluta, banners promocionales, chat flotante, texto gris claro por debajo de AA |

---

## 3. Extensión del sistema visual (PROPUESTO)

Amplía `sistema-visual-paxfide-web.md` **sin modificar** ningún token existente. Los colores nuevos derivan de `brand-neutral-700` (`#405656`) variando solo la luminosidad, con el mismo método y fórmula del Anexo A de ese documento. El script de verificación está en el Anexo A de este documento.

### 3.1 Colores neutros nuevos

| Token | Valor | Uso | Contraste relevante |
|---|---|---|---|
| `neutral-50` | `#F8FAFA` | Fondo de página | `brand-green-900` 8,72:1 · `brand-neutral-700` 7,47:1 |
| `neutral-100` | `#F0F4F4` | Fila con hover, fondo de cabecera de tabla | `brand-neutral-700` 7,06:1 · `brand-green-900` 8,24:1 |
| `neutral-200` | `#E2E9E9` | Separadores y bordes **decorativos** (no transmiten información) | 1,23:1 sobre `white`: solo decorativo |
| `neutral-400` | `#739797` | Borde de **controles** (campos, botón secundario) | 3,18:1 sobre `white` · 3,04:1 sobre `neutral-50` (WCAG 1.4.11 ≥ 3:1) |
| `overlay` | `brand-green-900` al 50 % | Fondo atenuado del modal | No transporta texto |

### 3.2 Restricciones nuevas derivadas del cálculo

1. **Enlaces sobre `neutral-50`:** `brand-blue-700` baja a **4,37:1** (no cumple 4,5:1). Sobre fondo de página, los enlaces usan **siempre `brand-blue-800`** (6,78:1). `brand-blue-700` solo se usa sobre `white`.
2. **Controles nunca sobre `neutral-100`:** `neutral-400` baja a 2,87:1. Los controles van sobre `white` o `neutral-50`.
3. **`neutral-200` nunca delimita un control** ni transmite estado.
4. **Controles nunca sobre `danger-50` ni `brand-yellow-50`** (decisión 13): el borde `neutral-400` baja a 2,93:1 y 2,91:1 (no cumple WCAG 1.4.11). Las acciones de `ErrorState` y `AmbiguousState` ("Reintentar", "Cerrar") van **debajo** del aviso, sobre el fondo de la página.

### 3.3 Tokens no cromáticos

| Grupo | Tokens |
|---|---|
| Espaciado (base 4 px) | `space-1` 4 · `space-2` 8 · `space-3` 12 · `space-4` 16 · `space-6` 24 · `space-8` 32 · `space-12` 48 |
| Radio | `radius-control` 6 px (campos, botones) · `radius-surface` 8 px (paneles, modal) |
| Sombra | Solo el modal: `shadow-modal` = `0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06)`. Las superficies planas usan borde, no sombra |
| Tipografía (sobre `system-ui`, HEREDADO) | `text-sm` 14 px (metadatos) · `text-md` 16 px (cuerpo) · `text-lg` 20 px (títulos de sección) · `text-xl` 24 px (título de página); pesos `weight-regular` 400 y `weight-semibold` 600 |
| Foco | Contorno de 2 px en `brand-green-900` con separación de 2 px (8,72:1 sobre `neutral-50`). Nunca amarillo (HEREDADO) |
| Objetivo táctil | `control-height` 40 px: alto mínimo de los controles (supera WCAG 2.5.8) |
| Ancho de contenido | `width-login` 400 px · `width-detail` 720 px · `gutter-mobile` 16 px (márgenes laterales en móvil) |

**Regla de texto pequeño (HEREDADO, `sistema-visual` §5.2):** en `text-sm`, los enlaces van en `brand-blue-800`.

### 3.4 Iconos SVG propios (PROPUESTO)

Conjunto cerrado de **5** iconos: información, advertencia (ambiguo), error, confirmación y cerrar (modal).
- Siempre acompañados de texto. Ningún estado se comunica solo con un icono (WCAG 1.4.1).
- Decorativos: `aria-hidden="true"`. El de cerrar, sin texto visible, lleva nombre accesible ("Cerrar").
- 20 px, `currentColor`, mismo contraste que el texto que acompañan.
- Añadir un icono es un cambio revisable de este documento.

---

## 4. Componentes base (PROPUESTO)

| Componente | Variantes y estados | Reglas |
|---|---|---|
| `Button` | Primario (`brand-green-900`, texto `white`), secundario (`white`, borde `neutral-400`, texto `brand-green-900`); estados normal, foco, deshabilitado, enviando. **Deshabilitado (decisión 12):** primario con fondo `neutral-400` y texto `white` (3,18:1); secundario con fondo `white`, borde y texto `neutral-400` (3,18:1); sin opacidad ni tokens nuevos | Deshabilitar al enviar es protección de UX, **no** idempotencia (HEREDADO, ADR-041 §2.6). "Enviando" conserva el texto y añade "…" |
| `TextField` / `PasswordField` | Normal, foco, error, deshabilitado | Etiqueta visible siempre (no solo placeholder), en `text-sm`, peso 600, `brand-neutral-700`. Deshabilitado: borde y texto `neutral-400` (misma regla que `Button`, decisión 13). "Mostrar/Ocultar" en `brand-blue-700` sobre `white`. Error debajo del campo, en `danger-700`, enlazado con `aria-describedby`. `PasswordField` con "Mostrar/Ocultar" en texto |
| `Modal` | Un título, contenido, Cancelar + Primario | Fondo `overlay`. Foco atrapado; Escape = Cancelar. No es una ruta y no se restaura (HEREDADO) |
| `StatusNotice` | Éxito, Rechazado, **Ambiguo**, Informativo | Colores **HEREDADOS** de `sistema-visual` §4. Ambiguo en amarillo, nunca rojo. Icono + título + texto. `role="status"` (informativo y éxito) o `role="alert"` (rechazado y ambiguo) |
| `DefinitionList` | Pares etiqueta → valor | Para detalles (`AssetPage`). Etiqueta en `brand-neutral-700`, valor en `brand-green-900` |
| `PageHeader` | Título + referencia secundaria opcional | Una sola `h1` por página |
| `LoadingState` | — | Texto "Cargando…" con `aria-live="polite"`. Sin esqueletos en v1 |
| `ErrorState` | — | Ver §6 |
| `ForbiddenState` | — | Ver §6 |
| `NotFoundState` | — | Ver §6 |
| `AmbiguousState` | — | `StatusNotice` ambiguo + acciones "Reintentar" y "Cerrar" (D6, P-W1b) |
| `EmptyState` | — | Fondo `white`, texto `brand-neutral-700`, sin acciones. Se usa en `PanelHome` sin entradas y en `/panel/campaigns` sin organización (§5.6). Desde la revisión 4 |
| `Table`, `LoadMore` | — | **Definidos pero sin uso en v1.** Su consumidor es el listado de `/panel/campaigns`, que no está diseñado (falta su contrato de lectura) |

No se añade `Card` ni otros componentes sin una pantalla que los necesite.

---

## 5. Pantallas

### 5.1 Shell (PROPUESTO)

```text
┌────────────────────────────────────────────────────────────┐
│ [logo] PaxFide                         [Cerrar sesión]*    │
├────────────────────────────────────────────────────────────┤
│                                                            │
│                    contenido de la ruta                    │
│                                                            │
└────────────────────────────────────────────────────────────┘
* solo en estado AUTHENTICATED
```

- **Sin barra lateral ni navegación por rol en v1.** El árbol solo da hoy para `/panel` (bloqueado por N1). Las referencias de barra lateral se aplican cuando existan al menos dos superficies navegables habilitadas.
- "Cerrar sesión" ejecuta el logout manual y emite la señal entre pestañas (HEREDADO, G-W3).
- Logo: el raster existente reducido. Recomendado sustituirlo por un vector (`sistema-visual` §6).
- Las rutas públicas (`/c`) usan el mismo encabezado, sin "Cerrar sesión".

### 5.2 Login (`/login`) — BLOQUEADO

**Bloqueo:** ID-01 debe ser normativo (Enmienda 1 de ADR-041) y el endpoint estar implementado (hallazgo E4). Hasta entonces, la ruta responde 404 (D5).
**Contrato de trabajo (ficha ID-01, no normativo):** `POST /api/v1/auth/login` con `{email, password}` → `200 {token}`.

```text
┌──────────────────────────────┐
│ Iniciar sesión               │
│ [aviso de sesión, si aplica] │
│ Correo electrónico           │
│ [                    ]       │
│ Contraseña                   │
│ [                    ] Mostrar│
│ [      Iniciar sesión     ]  │
└──────────────────────────────┘
```

| Estado | Disparador | Presentación |
|---|---|---|
| Formulario | Inicial | Dos campos y botón primario |
| Validación local | Algún campo vacío al enviar | Error en el campo: "Este campo es obligatorio." **No se valida el formato del correo**: el contrato no lo exige |
| Enviando | Petición en curso | Botón en "Enviando…", campos deshabilitados |
| Credenciales no válidas | `401` | `StatusNotice` rechazado: **"Correo o contraseña incorrectos."** Un único texto para los tres fallos; nunca sugiere "cuenta inactiva" (HEREDADO, ADR-038 §2.6, ADR-041 §2.5) |
| Petición inválida | `400` | Mismo tratamiento que la validación local. No debería ocurrir si esta funciona |
| Error técnico | `500` u otro 5xx | Rechazado: "No pudimos iniciar sesión. Inténtalo de nuevo más tarde." |
| Error de red | Timeout o conexión | Rechazado: "No pudimos conectar. Revisa tu conexión e inténtalo de nuevo." Reintentar es seguro (el login no tiene efectos duplicables, HEREDADO) |
| **Sesión expirada** | Llegada a `/login` por T-1 | `StatusNotice` informativo: "Tu sesión expiró. Vuelve a iniciar sesión." |
| Sesión cerrada en otra pestaña | Llegada por señal G-W3 | Informativo: "Cerraste sesión en otra pestaña." |
| Éxito | `200` | **HEREDADO (ADR-046 D4; `front-fase2` §8, tabla de guards):** navega al destino retenido en memoria (G-W2) o, si no lo hay, a `/panel`. Este documento no redefine ese comportamiento; solo deja fuera el **contenido** de `/panel` (ver nota N1). Mientras `/panel` no esté habilitado (D5), su resultado es el 404 de G-W4 |

**Excluido:** recuperar contraseña, crear cuenta, "recordarme" y cualquier persistencia del token.

**Requisito que este diseño crea (PROPUESTO):**
- **R-UX-1:** la máquina de sesión debe registrar **en memoria** el motivo del último `LOGGED_OUT` (manual, expirado, otra pestaña) para poder mostrar el aviso. Es un cambio pequeño en W-3 y no persiste nada.

**Nota de dependencia (N1), no requisito:** `PanelHome` (`/panel`) es PROVISIONAL según `front-fase2` §7 y su contenido no se diseña aquí. La ruta existe como destino normativo (ADR-046 D4); lo que falta es su contenido y su habilitación. Hasta que exista N1, la entrada útil a la web autenticada es por QR (`/assets/…` → login → vuelta al activo mediante el destino retenido).

### 5.3 `AssetPage` (`/assets/:assetRef`) — lectura, BLOQUEADO para despliegue

**Bloqueo de la lectura:** `PhysicalAssetOperationalReadPort` y su controller no existen en el backend (F5). R10 (`REPRESENTATIVE` no puede leer).
**Contrato:** `PhysicalAssetOperationalReadModel` (matriz §4b), con 7 campos y ninguno más.
**Uso principal:** móvil, al escanear el QR. El diseño parte de 360 px de ancho.

```text
┌──────────────────────────────────────┐
│ Activo                               │
│ AS-…(assetRef)                       │
├──────────────────────────────────────┤
│ Estado            Recibido           │
│ Cantidad          120                │
│ Unidad de medida  kg                 │
│ Ubicación actual  …                  │
│ Custodio actual   …                  │
│ Convocatoria      …                  │
├──────────────────────────────────────┤
│ [ Dividir activo ]   ← solo si la    │
│                        acción está    │
│                        habilitada (D5)│
└──────────────────────────────────────┘
```

| Campo del contrato | Etiqueta | Presentación |
|---|---|---|
| `assetRef` | (en el `PageHeader`) | Tal cual, en monoespaciada |
| `lifecycleStatus` | Estado | Texto traducido: REGISTERED → "Registrado", DISPATCHED → "Despachado", RECEIVED → "Recibido", DELIVERED → "Entregado". **Sin color por estado**, porque el contrato no le da semántica visual |
| `quantity` | Cantidad | **Valor recibido mostrado como texto, sin transformación semántica:** sin redondear, sin convertir unidades y sin suponer su tipo. Nunca se muestra el JSON crudo. **Formato de presentación PENDIENTE** (separadores, decimales) hasta que T-34 sea normativo |
| `unitOfMeasure` | Unidad de medida | Tal cual |
| `currentLocation` | Ubicación actual | Tal cual; si es `null`, "Sin registrar" |
| `currentCustodianRef` | Custodio actual | **Referencia opaca, tal cual.** No se resuelve a un nombre: no hay contrato para hacerlo. Si es `null`, "Sin registrar" |
| `campaignRef` | Convocatoria | Referencia opaca, tal cual, sin enlace (no existe detalle de convocatoria) |

| Estado | Presentación |
|---|---|
| Carga | `LoadingState` |
| Contenido | `PageHeader` + `DefinitionList` |
| Entregado (`DELIVERED`) | Contenido + `StatusNotice` informativo: "Este activo ya fue entregado. Solo lectura." |
| `403` | `ForbiddenState` (§6). Nunca redirige |
| `404` del recurso | "No encontramos este activo. Verifica el código QR." Es un estado de la página, distinto del 404 de G-W4 |
| `401` | T-1 → `/login` con el destino retenido y el aviso de sesión expirada |
| Error | `ErrorState` (§6) |

#### Acción `split` — diseñada, BLOQUEADA para despliegue

**Alcance (HEREDADO, T3):** `split` forma parte de `AssetPage` en web.
**Bloqueos de despliegue** (`hallazgos-front-fase2.md` y plan de implementación, revisión 2):
- **R11/F3:** bloqueo duro según D6. Falta contrato con `Command-Id` y "mismo resultado ante duplicado"; hoy el backend devuelve `void`.
- Cuerpo de `split` sin especificar.
- R10.
- P7: integrado en el código según F2, pendiente de verificación con los tests ejecutados.

Mientras alguno siga abierto, por **D5** la acción **no se renderiza** en ningún build desplegado.

**Diseño:**
- Botón secundario "Dividir activo", debajo de la `DefinitionList`.
- **No aparece en `DELIVERED`** (matriz §4b: `DELIVERED` → solo lectura).
- Para los demás estados, la disponibilidad **no** se decide aquí (HEREDADO, T3: la matriz solo fija `DELIVERED`).
- **Modal "Dividir activo":**
  - texto explicativo: "Separa una parte de la cantidad de este activo en un activo nuevo." (ADR-005: división parcial y repetible);
  - **zona de campos rotulada "Campos: PENDIENTE (cuerpo de `split` sin especificar)"**, sin ningún campo dibujado;
  - botones Cancelar y **Confirmar, dibujado deshabilitado** mientras la zona de campos esté pendiente, para que nadie implemente un envío con cuerpo vacío.
- **Estados del comando:** enviando, rechazado y ambiguo, según §6 y D6. **Éxito: PENDIENTE.** Sin contrato de respuesta (F3) no se sabe qué mostrar ni a dónde navegar.

### 5.4 `/c/:publicCode` — estructura, BLOQUEADO

**Bloqueo:** módulo `convocatoria` sin código; campos de `ConvocatoriaReadModel` sin especificar (E3); narrativa (ADR-040); código HTTP de "no encontrada".

```text
┌──────────────────────────────────────┐
│ [logo] PaxFide                       │
├──────────────────────────────────────┤
│ ⟦ zona de contenido público ⟧        │
│   campos: PENDIENTE (E3)             │
│ ⟦ zona de narrativa ⟧                │
│   estados: disponible / pendiente /  │
│   no disponible                      │
└──────────────────────────────────────┘
```

- Sin botón Donar, imagen, barra de meta ni campos de ejemplo (HEREDADO).
- En Penpot se dibuja con **bloques grises rotulados**, sin texto de muestra que parezca un dato.
- "No encontrada" es un estado de la página (P-W3): "No encontramos esta convocatoria. Verifica el enlace."
- SSR + OG + `noindex` es comportamiento técnico (D7) y no tiene representación visual.

### 5.5 Página 404 (G-W4) — HEREDADO + PROPUESTO

"No encontramos esta página." Una sola variante para cualquier ruta no aprobada o deshabilitada, con o sin sesión. **No menciona** permisos, "próximamente" ni que la ruta exista. Sin enlaces a rutas autenticadas.

### 5.6 `PanelHome` (`/panel`) — APROBADO-BLOQUEADO (revisión 4)

**Fuente:** `claude/delta-ux-panelhome.md` (APROBADO 2026-10-05), sobre `claude/delta-front-fase2-N1.md` (APROBADO) y `claude/ficha-N1-quien-soy.md` (CONGELADA).
**Bloqueo:** Enmienda 1 de ADR-041 (N1 normativa) y `GET /api/v1/me` implementado. Hasta entonces, 404 (D5).
**Regla:** `PanelHome` representa; no autoriza. Las entradas visibles las decide solo D-N1-1/D-N1-2 del delta de N1.

```text
┌──────────────────────────────────────┐
│ Panel                                │  PageHeader (h1)
│ [ Convocatorias de mi organización ] │  <a>, Button secundario, ancho completo
│ [ Registrar activo ]                 │  <button>, Button secundario — BLOQUEADO (D5)
└──────────────────────────────────────┘
```

- Mobile-first (360 px); en 1280 px, ancho `width-detail`. Una columna, orden fijo. Entradas en `<ul>`.
- Textos de las entradas HEREDADOS de `front-fase2` §7. Sin texto descriptivo ni iconos.
- "Registrar activo" (P7, cuerpo de `register`, R10, R11): **no se renderiza** en ningún build desplegado; existe solo como variante de diseño. Su formulario no se diseña.

| Estado | Presentación |
|---|---|
| Carga (`/me` en curso) | `LoadingState` |
| Con entradas | `PageHeader` + entradas |
| Sin entradas | `PageHeader` + `EmptyState`: "No hay opciones disponibles para tu cuenta en este panel." |
| Error (red, 5xx o `/me` que viola la ficha) | `ErrorState` con "Reintentar" |
| 401 | T-1 → `/login` con el aviso de sesión expirada (§5.2) |

**`/panel/campaigns` sin `organizationId`:** no se llama al backend; `PageHeader` "Convocatorias de mi organización" + `EmptyState` con el mismo texto. Nunca `ForbiddenState` (no hay 403 del backend).

---

## 6. Estados globales — textos (PROPUESTO)

Regla común: **nunca se muestra el `detail` de un `ProblemDetail`** (consecuencia aprobada). El texto depende solo del código HTTP y del contexto de la pantalla.

| Estado | Fondo / texto (HEREDADO, `sistema-visual` §4) | Texto |
|---|---|---|
| Carga | — | "Cargando…" |
| Error (red o 5xx en una lectura) | `danger-50` / `danger-700` | "No pudimos cargar la información. Inténtalo de nuevo." + botón "Reintentar" (es una lectura, así que reintentar es seguro) |
| 403 | `white` / `brand-neutral-700` | "No tienes acceso a este recurso." Sin redirección y sin sugerir causas |
| No encontrado (recurso) | `white` / `brand-neutral-700` | Según la pantalla (§5.3, §5.4) |
| **Ambiguo** (comandos) | `brand-yellow-50` / `brand-green-900`, título en `brand-yellow-900` | Título "No pudimos confirmar la operación". Texto: "Puede que se haya realizado. Revisa antes de repetirla." Acciones "Reintentar" (mismo `Command-Id`) y "Cerrar". Al cerrar: "No sabemos si la operación se realizó; revísalo antes de repetirla." |
| Rechazado (comandos, 4xx) | `danger-50` / `danger-700` | Por código. Los textos de los 409 de Convocatoria quedan **PENDIENTES** hasta que el mapeo sea normativo (HEREDADO, `front-fase2` §9) |
| Éxito (comandos) | `brand-green-50` / `brand-green-900` | Por comando; PENDIENTE donde falte el contrato de respuesta (p. ej. `split`, F3) |
| Sesión expirada | `brand-blue-50` / `brand-blue-800` | §5.2 |
| Sin entradas (`EmptyState`) | `white` / `brand-neutral-700` | "No hay opciones disponibles para tu cuenta en este panel." No sugiere causas, no menciona permisos ni "próximamente" (revisión 4) |

---

## 7. Accesibilidad (HEREDADO + PROPUESTO)

- WCAG 2.2 AA en toda combinación (HEREDADO). Solo se usan las combinaciones de `sistema-visual` §3 y de §3.1 de este documento.
- `lang="es"`; una sola `h1` por página; orden de foco igual al orden visual.
- Los avisos se anuncian (`role="status"` / `role="alert"`).
- El color nunca es la única señal (HEREDADO).
- Modal: foco atrapado, Escape cierra y el foco vuelve al botón que lo abrió.

---

## 8. Entrega a Penpot

| Frame | Variantes | Ancho |
|---|---|---|
| Tokens y componentes | Todas las de §3–§4 | — |
| Shell | Público / autenticado | 360 y 1280 |
| Login | Los 10 estados de §5.2 | 360 y 1280 |
| AssetPage | Carga, contenido, entregado, 403, 404, error | 360 (principal) y 1280 |
| AssetPage — `split` (BLOQUEADO para despliegue) | Botón visible (variante habilitada), modal con zona de campos PENDIENTE y Confirmar deshabilitado, estados de comando §6 | 360 |
| `/c` — estructura | Bloques rotulados; no encontrada | 360 y 1280 |
| 404 | Una | 360 y 1280 |
| PanelHome (BLOQUEADO para despliegue) | Carga, con dos entradas, con una entrada, sin entradas, error; y `/panel/campaigns` sin organización (solo 360) | 360 y 1280 |
| Estados globales | Los de §6 | — |

**Regla de PanelHome:** sus variantes representan estados que se derivan de D-N1-1/D-N1-2; no deciden qué combinaciones de entradas son posibles ni crean reglas de producto (precisión de Carlos, 2026-10-05).

**Regla:** cada frame lleva en su título la sección de este documento que representa. Un elemento de Penpot sin sección correspondiente es un error del mockup, no una decisión nueva.

---

## 9. Registro de aprobación (2026-10-05)

| # | Decisión | Estado |
|---|---|---|
| 1 | Dirección de estilo y exclusiones (§2) | **APROBADO** |
| 2 | Tokens neutros nuevos y sus restricciones (§3.1–§3.2), como **extensión** de `sistema-visual` | **APROBADO** |
| 3 | Tokens no cromáticos (§3.3) | **APROBADO** |
| 4 | Conjunto de 5 iconos SVG (§3.4) | **APROBADO** |
| 5 | Componentes (§4) | **APROBADO** |
| 6 | Shell sin navegación lateral en v1 (§5.1) | **APROBADO** |
| 7 | Login: estados y textos; R-UX-1 (motivo de cierre de sesión en memoria) | **APROBADO** |
| 8 | AssetPage: etiquetas, traducción de `lifecycleStatus` sin color, referencias opacas tal cual, `quantity` sin transformación, `split` diseñado con zona de campos PENDIENTE y bloqueado para despliegue | **APROBADO** |
| 9 | Textos de estados globales (§6) | **APROBADO** |
| 10 | Enmienda de §3.3: valor de `shadow-modal` y tokens `weight-regular`, `weight-semibold`, `control-height`, `width-login`, `width-detail`, `gutter-mobile`. Corrige un hueco del documento: §3.3 no daba esos nombres ni el valor de la sombra, y W-7 los necesitaba | **APROBADO** (Carlos, 2026-10-05, tras implementarse en W-7) |
| 11 | Revisión 4: `PanelHome` (§5.6), `EmptyState` en uso (§4), texto "sin entradas" (§6), frame de Penpot (§8). U-P1 "Panel"; U-P2 `Button` secundario; U-P3 texto | **APROBADO** (Carlos, 2026-10-05, `claude/delta-ux-panelhome.md`) |
| 12 | Estilo de `Button` deshabilitado (§4): tokens existentes, sin opacidad. Hueco detectado al dibujar los frames: ni este documento ni `sistema-visual` lo definían | **APROBADO** (Carlos, 2026-10-06) |
| 13 | Detalles de traducción visual aprobados con el lote 1 de frames: restricción §3.2.4 (controles fuera de `danger-50` y `brand-yellow-50`), campo deshabilitado, estilo de etiquetas y de "Mostrar". "Enviando" de `split` es inalcanzable mientras Confirmar esté deshabilitado; el rechazo de `split` sigue sin texto (PENDIENTE). Los ajustes finos de los frames los hace Carlos en Penpot | **APROBADO** (Carlos, 2026-10-06) |

**Aprobación:** Carlos, 2026-10-05, en bloque, sobre la revisión 2 (decisiones 1–9); decisiones 10 y 11 aprobadas por separado el mismo día; decisiones 12 y 13 el 2026-10-06.

**Decisiones explícitas registradas con la aprobación:**
- Se mantiene `/panel` como destino post-login por defecto. Es HEREDADO de ADR-046 D4 y este documento no puede rebajarlo a PENDIENTE.
- No se abre ninguna enmienda a ADR-046.
- R-UX-2 permanece como nota de dependencia (N1), no como requisito.
- Se distingue "la ruta existe como destino normativo" de "su contenido está diseñado o habilitado". Mientras `/panel` no esté habilitado (D5), su resultado es el 404 de G-W4.

**Pendientes que este documento no resuelve:** N1 normativa (Enmienda 1 de ADR-041) y su implementación; listado de convocatorias; formulario de "Registrar activo"; Enmienda 1 de ADR-041; formato de presentación de `quantity`; cuerpo y respuesta de `split`; campos de `ConvocatoriaReadModel`; textos de los 409; logo vectorial.

---

## Anexo A — Verificación de los tokens nuevos

Reutiliza sin cambios `h2r`, `r2h`, `lum`, `cr` y `with_l` del Anexo A de `sistema-visual-paxfide-web.md`.

**No** se usa `darken_to` de ese anexo: parte de la luminosidad del propio color, y con `#405656` (que ya supera 3:1) devolvería el mismo color. Para obtener el tono **más claro** que alcanza un umbral se añade esta función, que parte de una luminosidad casi blanca y oscurece:

```python
def lightest_reaching(hexc, target, bg='#FFFFFF'):  # nueva en este anexo
    l = 0.99
    while l > 0:
        c = with_l(hexc, l)
        if cr(c, bg) >= target:
            return c
        l -= 0.005

N = '#405656'                                    # brand-neutral-700
n50  = with_l(N, 0.975)                          # -> #F8FAFA
n100 = with_l(N, 0.95)                           # -> #F0F4F4
n200 = with_l(N, 0.90)                           # -> #E2E9E9
n400 = lightest_reaching(N, 3.0, bg=n50)         # -> #739797

for a, b in [('#1B5141', n50), ('#405656', n50), ('#405656', n100),
             ('#1B5141', n100), ('#255E79', n50), ('#327DA2', n50),
             (n400, '#FFFFFF'), (n400, n50), (n400, n100), ('#B42318', n50)]:
    print(a, b, round(cr(a, b), 2))
# 8.72  7.47  7.06  8.24  6.78  4.37(no AA)  3.18  3.04  2.87(no AA)  6.28
```

Salida verificada ejecutando el script el 2026-10-05.
