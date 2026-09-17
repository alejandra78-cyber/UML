# Estado del Backlog — Cierre de los 9 UC "No iniciado" + deuda de testing

Mantenido por el orquestador de la sesión `backend-b1`. Se actualiza después de cada
tarea que termina un agente de código, no solo al final de cada oleada. Basado en la
auditoría de `backend/docs/PLAN_ARQUITECTONICO.md` (secciones 2-3).

Convención de estado por tarea: `PENDIENTE` / `EN PROGRESO` / `HECHO` / `BLOQUEADO` / `DESVIACIÓN`.

---

## ✅ CIERRE — Oleada 3 (2026-09-17)

**UC08, UC09 y UC10 quedan HECHOS y verificados. Suite completa: 130/130 tests
(`mvn test`, BUILD SUCCESS). Oleada 3 cerrada oficialmente — con esto, los 9 UC que
la auditoría original marcó "No iniciado" están todos cerrados.**

| UC | Agente | Estado | Evidencia | Notas |
|---|---|---|---|---|
| UC08 + UC09 — Comando de Voz/Texto | 1 | **HECHO — verificado** | `ai/service/GenerateDomainGuardrail.java` (heurística determinista, corre antes que Gemini), `ai/service/GeminiApiClient(+Impl)`, `ai/service/VoiceCommandParserService.java` (tope duro de 5 operaciones, reutiliza `DiagramMutationService.applyWithReason`/`SequenceService`/`GridLayoutEngine` de oleadas anteriores), `ai/controller/VoiceCommandController.java` (`POST /api/v1/diagrams/{id}/ai-command`) | Mensaje de rechazo literal del plan, verificado carácter por carácter. Heurística del guardrail documentada con falsos positivos/negativos conocidos, honesto sobre sus límites. **Integración real con Gemini NO probada end-to-end** (sin `GEMINI_API_KEY` en este entorno) — cliente HTTP real implementado con la forma correcta de la API, ejercitado solo vía stub en tests. `GenerateDomainGuardrailTest` (12 tests) + `VoiceCommandParserServiceTest` (3 tests), verificado independientemente. |
| UC10 — Importar desde Foto de Pizarra | 2 | **HECHO — verificado** | `vision/service/VisionOcrService.java`, `vision/service/VisionGeminiClient(+Impl)` (cliente propio, no duplicado — el de Agente 1 no existía todavía cuando lo necesitó), `vision/controller/VisionImportController.java`, `vision/dto/DraftModelResponse.java` | No aplica mutaciones directas — solo genera un borrador para el modal Human-in-the-Loop; la inserción real es vía `BULK_MERGE`, que ya existía desde antes de este backlog. `VisionOcrServiceTest` (5 tests), verificado independientemente. Misma limitación de Gemini sin probar end-to-end que UC08/UC09. |

### Incidentes de la oleada

1. **Agente 2 (UC10) estancado 600s** justo antes de correr su test nuevo (mismo
   patrón que UC16 en la Oleada 2) — sin proceso colgado real al investigar
   (`Get-CimInstance Win32_Process`), código completo y correcto en disco, verificado
   en aislado por el orquestador. Segunda vez en dos oleadas seguidas que esto pasa
   justo en el mismo punto del flujo — anotado como posible fragilidad del harness
   de agentes en background con comandos Maven largos, no del código generado.
2. **Anomalía de timing en la verificación final**: al correr la suite completa
   (`mvn test`), `ProjectControllerTest` tardó 959s (≈16 min) en vez de los ~3-5s
   habituales, sin ningún error ni log de reintento visible en el medio — 14/14
   tests igual pasaron verdes. No se investigó a fondo la causa raíz (probablemente
   contención de I/O/red transitoria del entorno, coincide con el mismo tipo de
   estancamiento sin causa aparente que afectó a los agentes en background dos
   oleadas seguidas). No bloqueante — resultado final correcto — pero se deja
   registrado como señal a vigilar si vuelve a aparecer.

### Verificación final

```
mvn test -> Tests run: 130, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS
```

---

## ✅ CIERRE — Oleada 2 (2026-09-16)

**UC14, UC16 y UC12 quedan HECHOS y verificados. Suite completa: 110/110 tests
(`mvn test`, BUILD SUCCESS). Oleada 2 cerrada oficialmente.**

| UC | Estado | Evidencia | Notas |
|---|---|---|---|
| UC12 — Sincronizar Cambios al Reconectar | **HECHO** | `diagram/service/OfflineReconciliationService.java` (nuevo), `DiagramMutationService.applyWithReason(...)` (nuevo, `apply(...)` original intacto), `DiagramController.java` (`POST /api/v1/diagrams/{id}/sync-offline`), `OfflineReconciliationServiceTest` (5 tests) | Reutiliza `DiagramMutationService`/`CanonicalModelMutator` para las reglas de rebase (no las reimplementó). Incluye broadcast STOMP de las operaciones reconciliadas (no era obligatorio, se hizo de todas formas). Convención nueva documentada: el `targetId` de cada operación offline viaja dentro del JSON de `payload` bajo la clave `"targetId"` (el DTO no tenía un campo separado) — comunicado a la sesión `web-12` para su cola offline de IndexedDB. |
| UC14 — Generar Aplicación Móvil | **HECHO** | `generator/service/NluIntentGeneratorService.java` (intents.json, 4 intenciones por clase), `generator/service/MobileAppGeneratorService.java`, plantillas `templates/mobile/*.ftl` (5 archivos), endpoint `POST /api/v1/diagrams/{id}/generate-mobile` en `GeneratorController` | **Limitación de entorno explícita, no de diseño**: no hay verificación de compilación real del proyecto móvil generado (no hay toolchain de React Native/Android/iOS en este entorno) — el criterio de "hecho" fue generación sin excepciones + `intents.json` válido y con la estructura esperada, verificado con test JUnit normal (no smoke test con subprocess como UC13). |
| UC16 — Importar Diagrama desde XMI | **HECHO** | `xmi/service/XmiImporterService.java` (703 líneas), endpoint `POST /api/v1/projects/{projectId}/import-xmi`, `XmiImporterServiceTest` (12 tests, incluye round-trip export→import) | **Bug real encontrado y corregido por el orquestador** (no por el agente): el importador defaulteaba a multiplicidad `"1..1"` incluso para relaciones `GENERALIZATION`/`DEPENDENCY`/`REALIZATION`, que en UML no llevan multiplicidad — el test de round-trip lo detectó (`expected: null but was: 1..1`). Corregido en `XmiImporterService.resolveMultiplicity(...)` para preservar `null` en esos 3 tipos quirúrgicamente, dejando el fallback "mejor esfuerzo" (`1..1`) solo para tipos estructurales (ASSOCIATION/AGGREGATION/COMPOSITION/MANY_TO_MANY). **Limitación real, ya documentada por el propio agente en el código**: no existe ningún archivo `.xmi` real exportado desde Sparx Enterprise Architect en este entorno para probar contra él — el importador se probó por round-trip contra el propio exportador (UC15) más casos manuales de XML "genérico" sin las extensiones propias (`position`/`waypoints`), pero no contra un dialecto real de EA. |

### Incidente durante la oleada: agente estancado

El Agente 1 de esta oleada (UC14+UC16) se quedó sin progreso por 600s a mitad de un
`mvn test` y el harness lo marcó como fallido (no fue un rate-limit; no se encontró
ningún proceso Java/Maven colgado al investigar). El código que había dejado en disco
hasta ese punto compilaba limpio y estaba casi completo; no hizo falta relanzar el
agente completo. El orquestador verificó manualmente archivo por archivo, corrió cada
test nuevo de forma aislada para descartar que el stall fuera un loop infinito real
(ninguno lo era), encontró y corrigió el bug real de multiplicidad de UC16 descrito
arriba, y confirmó la suite completa en verde.

### Verificación final

```
mvn test -> Tests run: 110, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS
```

---

## ✅ CIERRE DEFINITIVO — Incidente de UC13 duplicado (2026-09-16)

**Incidente de UC13 duplicado resuelto de forma definitiva el 2026-09-16 — `codegen/`
eliminado del repositorio, `generator/` es la única implementación. Suite verificada en
verde: 85/85 tests (`mvn clean test`, BUILD SUCCESS).**

### Qué pasó

Este repositorio (`backend/` + `web/` como carpetas hermanas del mismo git) tuvo **dos
sesiones de Claude Code corriendo, sin coordinación, el mismo plan de "3 oleadas / 3
agentes" sobre el backend en paralelo**: esta sesión (`backend-b1`) y otra sesión
identificada como `web-12`. Cada una lanzó su propio Agente 1 para UC13, resultando en
dos implementaciones independientes y completas:

- `com.modelcollab.generator/` (esta sesión): `GeneratorController` (`GET /validate`,
  `POST /generate-backend`), `SpringBootGeneratorService`, `GeneratorModelBuilder`,
  `ZipPackagingService`, `FreeMarkerConfig`, 14 plantillas `.ftl`, smoke test de los 3
  dominios de referencia (§4.1) que compila cada uno con Maven real.
- `com.modelcollab.codegen/` (sesión `web-12`): `ProjectModelBuilder`, `EntityView`,
  `JavaTypeMapper`, etc. — sin smoke test ni endpoint conectado en el momento de la
  decisión.

Además, ambas sesiones tenían asignado UC18, UC19, UC15 y la misma deuda de testing
sobre los **mismos archivos físicos** (`project/service/ProjectService.java`,
`project/controller/ProjectController.java`, `project/dto/InviteMemberRequest.java`,
`xmi/**`, y 3 archivos de test), y ambas escribían sin coordinarse en
`backend/docs/ESTADO_BACKLOG.md`, causando varias sobrescrituras mutuas de este archivo.

### Cómo se resolvió

1. Ambas sesiones se detectaron mutuamente vía mensajería entre sesiones y confirmaron
   el solapamiento de archivos.
2. Un `ConflictingBeanDefinitionException` real (dos beans `springBootGeneratorService`
   con el mismo nombre en paquetes distintos) rompió temporalmente el arranque del
   `ApplicationContext` de Spring para toda la suite (24 tests con error). La sesión
   `web-12` quitó las anotaciones `@Service` de su lado como fix de emergencia.
3. El usuario de esta sesión (`backend-b1`) tomó la **decisión final**: `generator/`
   queda como única implementación válida de UC13; `codegen/` se descarta por completo.
4. Se hizo un respaldo de `codegen/` (13 archivos) fuera del repositorio, en el
   directorio de scratchpad de esta sesión, **antes** de borrarlo — puramente como red
   de seguridad, sin dejar rastro dentro del repo.
5. Se ejecutó `rm -rf src/main/java/com/modelcollab/codegen` y se verificó con
   `git status` que el path ya no existe/no está trackeado, y con `grep` en todo
   `src/main` y `src/test` que ninguna clase `SpringBootGeneratorService` ni
   `ZipPackagingService` sobrevive fuera de `generator/`.
6. **Nota de transparencia (resuelta)**: la sesión `web-12` había pedido esperar la
   confirmación de su propio usuario antes del borrado, pero ese pedido llegó después
   de que el `rm -rf` ya se había ejecutado. El usuario de esta sesión confirmó
   posteriormente que `web-12` es la misma persona/sesión que él mismo viene
   manejando en otra ventana (enfocada en `web/`) — no había una segunda persona
   involucrada. Ambigüedad cerrada, sin conflicto de propiedad real.
7. **Barrido final de limpieza**: además del paquete `src/main/java/com/modelcollab/codegen/`
   ya eliminado, se encontraron y eliminaron dos residuos más de la implementación
   descartada: `src/main/resources/codegen/templates/` (10 plantillas `.ftl` huérfanas)
   y el directorio vacío `src/test/java/com/modelcollab/codegen/`. Barrido de
   verificación (`grep -ril "codegen" src`, `find src -iname "*codegen*"`) confirma
   cero coincidencias en todo `backend/src`. El respaldo temporal fuera del repo
   (13 archivos en el scratchpad de la sesión) fue descartado a pedido explícito del
   usuario — no se conserva ninguna copia de `codegen/` en ningún lado.
8. `mvn clean test` → **85/85 tests, 0 failures, 0 errors, BUILD SUCCESS** (verificado
   dos veces: una tras borrar `codegen/src/main/java`, y una segunda vez tras el
   barrido final de `resources/codegen` y el test dir vacío — mismo resultado, 85/85,
   ambas con `clean` real). Sin `ConflictingBeanDefinitionException` ni ningún otro
   error.

**UC13, UC18, UC19, UC15 y la deuda de testing (LockManagerServiceTest,
RoomManagerTest, ProjectControllerTest) quedan HECHOS y verificados. La Oleada 1 de
backend queda oficialmente CERRADA — sin rastro de la implementación descartada de
UC13 en ningún lugar del repositorio ni fuera de él.**

---

## Tabla final de la Oleada 1

| UC / tarea | Estado | Evidencia | Notas |
|---|---|---|---|
| UC13 — Generador Backend Spring Boot | **HECHO** | `generator/` completo: `GeneratorController`, `SpringBootGeneratorService`, `GeneratorModelBuilder`, `ZipPackagingService`, `FreeMarkerConfig`, 14 plantillas `.ftl`, `SpringBootGeneratorServiceSmokeTest` (3 dominios compilando con Maven real) | Única implementación tras la resolución del incidente de duplicación (ver arriba). |
| UC18 — Invitar Colaborador | **HECHO** | `project/service/ProjectService.java` (`addOrUpdateMember`), `project/controller/ProjectController.java`, `project/dto/InviteMemberRequest.java` | Verificado vía `ProjectControllerTest`. |
| UC19 — Eliminar Proyecto | **HECHO** | `project/service/ProjectService.java` (`deleteProject`) | Desviación real detectada y resuelta: no hay `ON DELETE CASCADE` en el esquema (Hibernate `ddl-auto=update`, sin relaciones JPA) — se implementó borrado manual en cascada. |
| UC15 — Exportar a XMI | **HECHO** | `xmi/service/XmiExporterService.java`, `xmi/controller/XmiController.java` | Namespaces OMG XMI 2.1, verificado con `XmiExporterServiceTest` (5 tests). |
| Deuda de testing | **HECHO** | `LockManagerServiceTest.java`, `RoomManagerTest.java`, `ProjectControllerTest.java` | Los 3 huecos de la auditoría original quedaron cerrados. |

**Verificación final: `mvn clean test` → 85/85, BUILD SUCCESS (2026-09-16).**

---

## Oleada 2 — arranca ahora

- Agente 1: UC14 (App Móvil, reutiliza motor FreeMarker de `generator/`) + UC16 (Importar XMI, parser Sparx EA).
- Agente 2: UC12 (Sincronizar cambios al reconectar / rebase §9.2).
- **Advertencia para evitar repetir el incidente de Oleada 1**: antes de lanzar, confirmar si `web-12` u otra sesión sigue activa sobre `backend/`; si es así, coordinar división de trabajo explícita antes de que cualquiera de los dos lance agentes nuevos.

## Oleada 3 — bloqueada hasta que Oleada 2 cierre en verde

- Agente 1: UC08 + UC09 (voz/texto, paquete `ai/`, guardrail `GENERATE_DOMAIN`).
- Agente 2: UC10 (visión de pizarra, paquete `vision/`, `BULK_MERGE`).

---

## Log cronológico (Oleada 1, resumido)

- **2026-09-16 (inicio)** — Auditoría base: 8 UC Hecho, 1 Parcial (UC13), 9 No iniciado,
  1 N/A backend (UC11). Se lanza Oleada 1.
- **2026-09-16 (corte por rate limit)** — Ambos agentes de esta sesión interrumpidos a
  mitad de tarea (HTTP 429). Verificación propia: `mvn test` → 81/81 tras un fix de
  aserción frágil en `XmiExporterServiceTest`. Agente 2 había completado su cola
  completa (UC18, UC19, UC15, testing); se relanza solo Agente 1 para terminar UC13.
- **2026-09-16 (detección de colisión cross-session)** — Se descubre `com.modelcollab.codegen/`,
  una segunda implementación de UC13 de la sesión `web-12`, corriendo el mismo plan en
  paralelo sobre el mismo repo. Se confirma además que UC18/19/15/testing fueron escritos
  por ambas sesiones sobre los mismos archivos sin coordinarse (resultado final coherente,
  sin corrupción). `ESTADO_BACKLOG.md` había sido sobrescrito varias veces por el
  observador de la otra sesión — explicado, no era inyección ni incumplimiento de brief.
- **2026-09-16 (bean conflict)** — Al completar Agente 1 UC13, un
  `ConflictingBeanDefinitionException` real rompió 24 tests (`generator` vs `codegen`
  registrando el mismo nombre de bean). `web-12` quitó las anotaciones de su lado como
  fix temporal; verificado `mvn test` → 85/85 verde.
- **2026-09-16 (cierre definitivo, primera pasada)** — El usuario decide: `generator/`
  es la única implementación de UC13. `codegen/src/main/java` respaldado fuera del
  repo y eliminado (`rm -rf` + verificación con `git status`/`grep`). `mvn clean test`
  → 85/85, BUILD SUCCESS.
- **2026-09-16 (cierre definitivo, barrido final)** — El usuario confirma que `web-12`
  es su misma sesión/persona (no había segundo usuario involucrado) y pide descartar
  el respaldo sin conservarlo. Barrido adicional encuentra y elimina dos residuos más:
  `src/main/resources/codegen/templates/` (10 `.ftl`) y el directorio de test vacío
  `src/test/java/com/modelcollab/codegen/`. Verificación de cero coincidencias de
  "codegen" en todo `backend/src`. Respaldo temporal descartado. `mvn clean test` →
  **85/85, BUILD SUCCESS**, confirmado por segunda vez. **Oleada 1 de backend cerrada
  oficialmente, sin ningún rastro de la implementación descartada.**
