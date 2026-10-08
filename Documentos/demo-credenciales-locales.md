> **Copia de una ejecución real** de la semilla (encargo 6, P5) sobre la base vacía de la demo local, el 2026-10-08. Solo sirve en esa base local: al repetir la semilla tras `down -v`, los ids, `publicCode` y `trackingCode` cambian y el fichero se regenera en `app/demo-evidencia/credenciales-locales.md` (o en `TRACEABILITY_DEMO_SEED_OUTPUT_FILE`). La contraseña es la de ejemplo de `demo.env.example`.

# Credenciales y códigos de la demo — **de ejemplo, solo locales**

Generado por la semilla de la demo (perfil `demo-seed`, `runbook-demo-local.md`) el 2026-10-08T07:09:41Z. Son cuentas y códigos de una base local de demostración: **nunca** se usan en otro entorno ni son credenciales reales. Cada ejecución sobre una base vacía genera códigos nuevos.

## Cuentas (contraseña común: `demo-local-password`, la de `TRACEABILITY_DEMO_SEED_PASSWORD`)

| Papel | Email |
|---|---|
| Administrador de plataforma | plataforma@demo.paxfide.local |
| Representante (Fundación Demo PaxFide, verificada) | representante@demo.paxfide.local |
| Administrador (y empleado) de la fundación | administrador@demo.paxfide.local |
| Empleado 1 (responsable de la convocatoria activa) | empleado1@demo.paxfide.local |
| Empleado 2 (fue de la cerrada; ahora de la privada) | empleado2@demo.paxfide.local |
| Donante con cuenta | donante@demo.paxfide.local |
| Representante de Empresa Aliada Demo (pendiente de verificación) | representante@empresa-aliada.demo.paxfide.local |

## Organizaciones

| Organización | organizationId |
|---|---|
| Fundación Demo PaxFide (verificada) | `01M4D5H983BVYR02THNA1EYQW4` |
| Empresa Aliada Demo (en la cola) | `01M4D5H98YTC85RTWFN14RVK7D` |

## Convocatorias

| Clave | Convocatoria | publicCode |
|---|---|---|
| activa | Abrigo para el invierno (PUBLIC, del 2026-10-08T07:08:39Z al 2026-11-21T17:19:49Z) | `VTY1HF9GV2BNVG20CQ68NBAFFJ` |
| cerrada | Útiles escolares 2026 (CLOSED) | `RKWGQFRESZQQ1BJBPKCDMEDGM6` |
| privada | Kits de higiene (PRIVATE_LINK) | `Q968QBQQ91FZ5DTJM3B9GVWR30` |

`campaignRef` de la activa (para la predicción): `49b5f279-bb7b-4192-a085-5e7e9c382fa8`. El 2026-10-21T15:00:00Z estará en el 30 % de su duración: la predicción da cifra.

## trackingCode (seguimiento del donante)

| Donación | Convocatoria | trackingCode |
|---|---|---|
| donación a la convocatoria cerrada | Útiles escolares 2026 | `NWEwNjViM2QtZjRlYi00ZWU2LWI0NmYtYjc1MGQ2MGUyYTQxfDE4MjI5NzkzODAxNDk.bIPYP_oZmBqH9pBT49MhzAHWU1-fBioIFzXGlN43In4` |
| donación anónima | Abrigo para el invierno | `ODUwOGNhOTUtOTAyMi00NjdjLTkyMzYtMTVhZDkwMTIyYjUxfDE4MjI5NzkzNzk5MDM.c_4YKAVB-x3qh1LC60EYCrSYuJ7JIegvKYvh7XsAOMY` |
| donación con cuenta | Abrigo para el invierno | `MTY1ZGMxZTYtMzBlMS00MGY4LThlZjQtODg2NTE4ZmY1M2VkfDE4MjI5NzkzODAwMzY.fA9R1_Og7P3JYdQgtqLe9ynNKIMWDumtPcrGXxMq7qM` |
| donación privada | Kits de higiene (enlace privado) | `ODA3ODQ2YzktOGY5ZS00NGViLThkM2QtNzlkZTVkMDYzNGFifDE4MjI5NzkzODAyMDg.QAZJ9sTkqqbTlpijFHDxGSb5qZNik7OIYl1-n6dsQpA` |

Contenido: donación anónima de 600 000 COP y con cuenta de 400 000 COP a la activa, y un pago fallido; camino A (10 mantas, división en 6 + 4, las dos entregadas) y camino B (20 kits de comida en tránsito); 300 000 COP a la cerrada y 200 000 COP a la privada.
