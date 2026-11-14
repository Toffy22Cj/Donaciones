# Runbook — Respaldo y restauración probada (T-04)

| Campo | Valor |
|---|---|
| Origen | `plan-evolucion-producto.md` §2.1 (T-04, P0), aprobado el 2026-11-14 |
| Herramienta | `scripts/servidor/paxfide.sh` (`respaldo`, `restaurar-prueba`, `instalar-temporizador`) |
| ADR | No (operación). La verificación de la restauración reutiliza la de la Enmienda 1 de ADR-039 |

## 1. Qué se copia y por qué

- **MongoDB completo** con `mongodump --archive --gzip --oplog` (se puede porque MongoDB corre como *replica set*). El Event Store es la única fuente de verdad. `--oplog` hace que la copia sea consistente aunque el backend esté escribiendo.
- **El bucket de MinIO** (`MEDIA_S3_BUCKET`), con `mc mirror` dentro del contenedor. Las fotos y evidencias cuyo `sha256:` está en los eventos no se pueden volver a generar.
- **El manifiesto** (`manifiesto.txt`) lleva la fecha, la base, el número de eventos, el bucket, el número de objetos, y el tamaño y el SHA-256 de cada fichero antes y después de cifrarlo. `manifiesto.sha256` permite comprobar los ficheros cifrados con `sha256sum -c`.

Cada parte se cifra con **age** (`PAXFIDE_BACKUP_AGE_RECIPIENT`, clave pública) o con **gpg** (`PAXFIDE_BACKUP_GPG_RECIPIENT`). La clave privada **no vive en el servidor**. Si el portátil se pierde, las copias son ilegibles para quien lo encuentre. Las copias en claro se destruyen con `shred` en cuanto se cifran.

## 2. Configuración (en `deploy/backend.env`, nunca en git)

| Variable | Obligatoria | Uso |
|---|---|---|
| `PAXFIDE_BACKUP_AGE_RECIPIENT` | Sí (o la de gpg) | Clave pública `age1…` |
| `PAXFIDE_BACKUP_GPG_RECIPIENT` | Alternativa | Destinatario de gpg |
| `PAXFIDE_BACKUP_DEST` | Sí | Destino externo: ruta montada (disco USB, carpeta sincronizada), `usuario@host:ruta` (rsync por ssh) o `rclone:remoto:ruta`. `solo-local` lo omite a sabiendas y avisa en cada ejecución |
| `PAXFIDE_BACKUP_DIR` | No | Carpeta local (por defecto `deploy/respaldos`, permisos 700) |
| `PAXFIDE_BACKUP_AGE_IDENTITY` | Solo para restaurar | Fichero con la clave privada de age; se pasa en el momento, desde el USB |

Para crear la clave, en un equipo distinto del servidor:

```bash
age-keygen -o clave-respaldo.txt
```

El comando imprime la clave pública (`Public key: age1…`). Esa va en `PAXFIDE_BACKUP_AGE_RECIPIENT`; `clave-respaldo.txt` se guarda fuera del servidor (USB y gestor de contraseñas).

> **Pendiente del aprobador (plan §9, pregunta 3):** el destino externo definitivo. Hasta que se decida, `respaldo` falla (y el temporizador lo registra como fallo) salvo `PAXFIDE_BACKUP_DEST=solo-local` explícito.

## 3. Operación

| Orden | Qué hace |
|---|---|
| `paxfide.sh respaldo` | Copia, cifra, verifica el manifiesto, aplica la retención (los 7 diarios más recientes y el primero de cada semana ISO, 4 semanas) y copia `diarios/` y `semanales/` al destino externo, reflejando también los borrados. Sale con un código distinto de 0 si cualquier paso falla |
| `paxfide.sh restaurar-prueba [carpeta]` | Ver §4. Por defecto, el último diario |
| `paxfide.sh instalar-temporizador` | Instala `paxfide-respaldo.timer` (systemd de usuario): cada día a las 03:17 ±15 min; `Persistent=true` lo ejecuta al encender si el equipo estaba apagado. Para que corra sin sesión abierta: `sudo loginctl enable-linger $USER` |
| `paxfide.sh semilla` | **Antes de `dropDatabase()` ejecuta un respaldo completo. Si falla, la base no se borra** |

Para ver si el temporizador corre y sus últimas ejecuciones:

```bash
systemctl --user list-timers paxfide-respaldo.timer
```

```bash
journalctl --user -u paxfide-respaldo.service --since "3 days ago"
```

## 4. Restauración de prueba (lo que demuestra que la copia sirve)

`restaurar-prueba` nunca toca la base del servidor:

1. Comprueba `manifiesto.sha256`. Si un byte cambió, se detiene con «respaldo corrupto».
2. Descifra en una carpeta temporal (700) y compara el SHA-256 en claro con el manifiesto. Comprueba también que la copia de MinIO tiene los objetos del manifiesto.
3. Levanta un MongoDB **aislado y desechable** (contenedor propio, solo en `127.0.0.1`, puerto aleatorio) y restaura con `mongorestore --oplogReplay`.
4. Compara el número de eventos con el del manifiesto.
5. Arranca el backend contra esa copia con `traceability.restore-check.enabled=true`. Escucha solo en `127.0.0.1`, con un puerto aleatorio. La cadena, el correo, la IA y S3 apuntan a un puerto cerrado, y la cartera es una clave aleatoria de un solo uso: **nada sale del equipo**. `RestoreVerificationRunner` verifica cada lote `ANCHORED`: recalcula la raíz, cada `eventHash` y la cadena `previousHash` (Enmienda 1 de ADR-039). Después escribe el informe.
6. Destruye el backend, el contenedor y los ficheros descifrados (`shred`), también si algo falla a mitad de camino.
7. Deja el registro en `respaldos/registros/restauracion-<fecha>.txt`.

La restauración es **CORRECTA** solo si coincide el número de eventos y el informe dice `resultado=OK`: ningún `MISMATCH` y ningún lote sin verificar. Un `INCONCLUSIVE` se cuenta aparte y no hace fallar la prueba.

**Restauración real (desastre):** pasos 1 a 3 sobre el MongoDB definitivo, en lugar del desechable. Para MinIO, `tar -xf minio.tar` y `mc mirror` de vuelta al bucket. Después se ejecuta `restaurar-prueba` sobre la misma copia para confirmar que la integridad da `MATCH`.

## 5. Evidencia (2026-11-14, contenedor de desarrollo, sin el servidor CachyOS)

El entorno de prueba era un `deploy/` equivalente: MongoDB 6.0 en `rs0`, MinIO `bitnamilegacy/minio:2025.7.23`, Ganache con `AnchorRegistry` desplegado y el backend del commit de este cambio con el perfil `demo-seed` (17 eventos, 2 lotes `ANCHORED`), más dos objetos de prueba en el bucket. Salida literal en `Documentos/evidencia-ci/t04-respaldo-2026-11-14.txt`.

| Prueba del plan | Resultado |
|---|---|
| Restauración real con `MATCH` en todos los lotes | `match=2 mismatch=0`, 17/17 eventos. **CORRECTA**; 0 contenedores y 0 carpetas temporales tras terminar |
| Respaldo corrupto (un byte cambiado) | `mongo.archive.gz.age: FAILED` → «respaldo corrupto», salida 1 |
| `dropDatabase()` sin respaldo previo impedido | `semilla` con el destinatario de age inválido → «el respaldo previo falló: la base NO se ha borrado»; 17 eventos antes y después |
| (Adicional) Un evento alterado se detecta | `resultado=FALLO`, `mismatch=1`, con el lote nombrado; salida 1 |
| (Adicional) Retención | 8 diarios antiguos + 3 de hoy → quedan los 7 más recientes, también en el destino externo |
| **El temporizador corrió 3 días seguidos** | **Pendiente:** solo se puede demostrar en el servidor tras `instalar-temporizador` (registros de `journalctl`) |

T-04 se considera **hecho** cuando exista además, en el servidor: el destino externo decidido, una restauración de prueba registrada con los datos reales y los tres días del temporizador.
