# Estado del Backlog — Oleada 2 (Frontend)

Documento de seguimiento. Este archivo es de observación únicamente: no contiene decisiones de
implementación, solo el estado reportado/observado de lo que hacen los agentes en paralelo sobre
`web/`. No se toca nada bajo `backend/`.

Última actualización: 2026-09-16 — Oleada 2 CERRADA, y actualización posterior fuera de oleada:
UC12 pasó de sync mockeada a conectada de verdad, UC14 y UC16 pasaron de placeholder a conectadas de
verdad (backend cerró los 3 endpoints que faltaban). Los 9 UC de la Oleada 2 (UC11–UC19) quedan TODOS
conectados de verdad, cero placeholders pendientes. Ver "Cierre de la Oleada 2" y "Actualización
posterior a la oleada" al final del documento.

## Tabla de estado por UC

| UC | Responsable | Estado | Archivos tocados hasta ahora | Notas |
|----|-------------|--------|-------------------------------|-------|
| UC11 — Cola offline real (IndexedDB) | Agente 1 | **Terminado** | `web/src/store/useDiagramStore.ts` (mod., +185/-… líneas), `web/src/types/diagram.ts` (mod.), `web/src/offline/offlineQueue.ts` (nuevo), `web/src/offline/syncOffline.ts` (nuevo) | Alcance: implementación real. Cola real en IndexedDB (`modelcollab-offline-queue`, store `mutations` indexado por `diagramId`), con hook reactivo `usePendingMutationCount(diagramId)` que también relee la cola al montar (recupera una cola que haya quedado de una sesión offline anterior tras un F5). Reemplaza TODOS los puntos donde antes se descartaba en silencio una mutación sin transport (13+ operaciones), vía los helpers `sendOrQueueLocked`/`sendOrQueueFree`. `syncOfflineQueue` queda MOCKEADA con TODO explícito apuntando a `POST /sync-offline` (backend-b1 confirmó que arranca en su propia Oleada 2). Limitación honesta explícita del propio Agente 1, sin suavizar: no hay test runner de browser en este proyecto, así que el comportamiento de IndexedDB está verificado por tipos/compilación (`tsc -b`) pero NO ejecutado en un navegador real. |
| UC12 — Reconciliación al reconectar | Agente 1 (+ actualización posterior del orquestador) | **Terminado (conectado de verdad)** | `web/src/features/offline/ReconciliationModal.tsx` (nuevo) + `.css` (nuevo), `web/src/features/offline/useOfflineSync.ts` (nuevo), `web/src/features/offline/OfflineSyncBanner.tsx` (mod., +28/-…), `web/src/App.tsx` (mod., wiring del hook + modal), `web/src/offline/syncOffline.ts` (mod. fuera de oleada: mock → real) | Hook `useOfflineSync` orquesta: detecta cola pendiente al reconectar → pasa a `'syncing'` → sincroniza (ahora real) → limpia cola → re-hidrata con snapshot fresco → vuelve a `'online'`. `updateConnectionStatus` envuelto en `useCallback` (hallazgo de calidad de Agente 1, verificado leyendo `App.tsx` líneas ~103-123). **Actualización posterior fuera de oleada:** el orquestador reemplazó el mock de `syncOfflineQueue` por `POST /diagrams/{id}/sync-offline` real vía `authFetch`, ya que backend confirmó el cierre de UC12 del lado servidor. Nota de contrato verificada por mí leyendo el código: el DTO del backend no tiene `targetId` como campo separado, así que se fusiona dentro de `payload` (excepto `ADD_CLASS`/`ADD_RELATIONSHIP`, que ya encolan `targetId: null`). |
| UC13 — Botón "Generar Backend" | Agente 2 | **Terminado (conectado de verdad)** | `web/src/components/GenerateBackendButton.tsx` + `.css` (nuevos), `web/src/components/downloadUtils.ts` (nuevo, compartido), `web/src/App.tsx` (mod., grupo de toolbar), `web/src/collaboration/diagramBootstrap.ts` (mod., exportó `API_BASE`/`authFetch`) | Backend ya cerrado. Reportado por el orquestador como terminado; confirmado por mí vía `git status`/`git diff` independientes (archivo `GenerateBackendButton.tsx` existe como untracked, `App.tsx` lo importa y renderiza con prop `diagramId={storeDiagramId}`). |
| UC14 — "Generar App Móvil" | Agente 2 (placeholder) + orquestador (conexión real posterior) | **Terminado (conectado de verdad)** | `web/src/components/GenerateMobileAppButton.tsx` (reescrito completo fuera de oleada), `web/src/App.tsx` (mod., ahora pasa `diagramId={storeDiagramId}`) | Backend de UC14 cerró fuera de la Oleada 2; el orquestador reescribió el componente reemplazando el placeholder por el mismo patrón que `GenerateBackendButton` (sin paso de validación previa, no pedido para este caso). Confirmado por mí: `App.tsx` línea 378 pasa la prop `diagramId={storeDiagramId}` al componente. |
| UC15 — "Exportar a XMI" | Agente 2 | **Terminado (conectado de verdad)** | `web/src/components/ExportXmiButton.tsx` (nuevo), `web/src/components/downloadUtils.ts` (compartido con UC13), `web/src/App.tsx` (mod., toolbar, prop `diagramId={storeDiagramId}`) | Backend ya cerrado. Confirmado vía `git status`/`git diff`. |
| UC16 — "Importar XMI" | Agente 2 (placeholder) + orquestador (conexión real posterior) | **Terminado (conectado de verdad)** | `web/src/components/ImportXmiButton.tsx` (reescrito completo fuera de oleada), `web/src/App.tsx` (mod., ahora pasa `projectId={projectIdRef.current}`), `web/src/collaboration/diagramBootstrap.ts` (mod., exportó `writeCached`, antes privado), `web/src/components/GenerateBackendButton.css` (mod., clase nueva `--success` verde, reutilizada por este componente) | Backend de UC16 cerró fuera de la Oleada 2. Conecta a `POST /projects/{projectId}/import-xmi` **a nivel de PROYECTO, no de diagrama** (corrección de contrato que pasó el usuario/backend). Verificado leyendo el código completo: como el endpoint crea un diagrama nuevo (201 + `DiagramResponse`) sin tocar el lienzo actual, la UI muestra un panel explícito "Se creó un nuevo diagrama..." con botón "Abrir este diagrama" que reutiliza `writeCached` + `window.location.reload()` (mecanismo de caché de `ensureActiveDiagram`) en vez de asumir que el canvas cambia solo — decisión de UX correcta dado que no hay selector de proyectos/diagramas en la app. |
| UC17 — Crear proyecto | Agente 2 | **Terminado (conectado de verdad)** | `web/src/components/CreateProjectModal.tsx` (nuevo), `web/src/components/ProjectModals.css` (nuevo, compartido con UC18/UC19), `web/src/App.tsx` (mod., grupo "Gestión de proyecto") | Confirmado vía `git status`/`git diff`. |
| UC18 — Invitar colaborador | Agente 2 | **Terminado (conectado de verdad)** | `web/src/components/InviteMemberModal.tsx` (nuevo), `web/src/components/ProjectModals.css` (compartido), `web/src/App.tsx` (mod., prop `projectId={projectIdRef.current}`) | Confirmado vía `git status`/`git diff`. |
| UC19 — Eliminar proyecto | Agente 2 | **Terminado (conectado de verdad)** | `web/src/components/DeleteProjectButton.tsx` (nuevo), `web/src/components/ProjectModals.css` (compartido), `web/src/App.tsx` (mod., prop `projectId={projectIdRef.current}`) | Confirmado vía `git status`/`git diff`. |

## Riesgo de colisión conocido (monitoreo activo)

Dos puntos de mayor probabilidad de superposición real entre Agente 1 y Agente 2:

- `web/src/App.tsx` — Agente 1 lo toca para enganchar `useOfflineSync` + `ReconciliationModal`; Agente 2 lo toca para agregar botones nuevos al toolbar (UC13/14/15/16) y flujos de gestión de proyecto (UC17/18/19).
- `web/src/store/useDiagramStore.ts` — Agente 1 agrega `diagramId` y encolado de mutaciones offline; Agente 2 necesitaba `diagramId` para UC13/UC15.

En cada actualización de este documento se va a cruzar `git status --short` / `git diff --name-only`
(filtrado a `web/`) contra estos dos archivos en particular para detectar si hubo superposición real
(no solo teórica).

### Resultado: coordinación exitosa, no conflicto

Verificado por mí de forma independiente leyendo el diff completo de `web/src/App.tsx` (no solo el
reporte del orquestador): cuando Agente 2 llegó al punto de necesitar `diagramId` para
`GenerateBackendButton`/`ExportXmiButton`, encontró que Agente 1 ya había agregado `diagramId` /
`setDiagramId` a `useDiagramStore.ts` pero **todavía no estaba siendo llamado desde `App.tsx`** — es
decir, el store quedaba con `diagramId` en `null` para siempre. Agente 2 no construyó un mecanismo
paralelo: en el mismo bloque donde ya estaba tocando `App.tsx` (el efecto que llama a
`ensureActiveDiagram`), agregó la línea que faltaba:

```ts
diagramIdRef.current = diagramId
projectIdRef.current = projectId
setStoreDiagramId(diagramId)   // <- esta línea es la que faltaba (gap de Agente 1)
```

y expuso `storeDiagramId` vía `useDiagramStore((state) => state.diagramId)` para pasárselo como prop
a sus propios componentes nuevos. Esto es una coordinación exitosa entre agentes que además resolvió
un gap real que tenía el trabajo de Agente 1: sin ese `setDiagramId` llamado, la cola offline
(`web/src/offline/offlineQueue.ts`, UC11) no habría tenido forma de saber para qué `diagramId`
encolar una mutación offline — `enqueueMutation` recibe `diagramId` desde `get().diagramId` del store
en `sendOrQueueLocked`/`sendOrQueueFree` (ver `web/src/store/useDiagramStore.ts`), que sin esa línea
hubiera quedado siempre `null`.

No hubo superposición destructiva (nadie sobrescribió o revirtió trabajo del otro) en ninguno de los
dos archivos de riesgo.

## Snapshot inicial

Comando ejecutado desde el root del repo (`C:\Users\ALEJANDRA CALLEJAS G\Documents\Parcial`), filtrando
a rutas `web/` únicamente (se ignora cualquier cambio bajo `backend/`, no es incumbencia de este reporte):

```
git status --short -- web/
git diff --stat -- web/
```

### `git status --short -- web/`

```
 M web/docs/PLAN_ARQUITECTONICO.md
 M web/src/App.tsx
 M web/src/components/ClassContextMenu.css
 M web/src/components/ClassContextMenu.tsx
 M web/src/components/ErRelationshipEdge.css
 M web/src/components/ErRelationshipEdge.tsx
 M web/src/components/ErTableNode.css
 M web/src/components/ErTableNode.tsx
R  web/src/components/CollaboratorPresence.css -> web/src/features/colaboracion/CollaboratorPresence.css
RM web/src/components/CollaboratorPresence.tsx -> web/src/features/colaboracion/CollaboratorPresence.tsx
R  web/src/components/VisionModal.css -> web/src/features/ia-asistida/VisionModal.css
RM web/src/components/VisionModal.tsx -> web/src/features/ia-asistida/VisionModal.tsx
R  web/src/components/VoiceToolbar.css -> web/src/features/ia-asistida/VoiceToolbar.css
RM web/src/components/VoiceToolbar.tsx -> web/src/features/ia-asistida/VoiceToolbar.tsx
R  web/src/components/UmlClassNode.css -> web/src/features/modelado-manual/UmlClassNode.css
RM web/src/components/UmlClassNode.tsx -> web/src/features/modelado-manual/UmlClassNode.tsx
RM web/src/components/UmlRelationshipEdge.css -> web/src/features/modelado-manual/UmlRelationshipEdge.css
RM web/src/components/UmlRelationshipEdge.tsx -> web/src/features/modelado-manual/UmlRelationshipEdge.tsx
R  web/src/components/ViewModeToggle.css -> web/src/features/modelado-manual/ViewModeToggle.css
RM web/src/components/ViewModeToggle.tsx -> web/src/features/modelado-manual/ViewModeToggle.tsx
R  web/src/components/OfflineSyncBanner.css -> web/src/features/offline/OfflineSyncBanner.css
RM web/src/components/OfflineSyncBanner.tsx -> web/src/features/offline/OfflineSyncBanner.tsx
 M web/src/store/useDiagramStore.ts
 M web/src/types/diagram.ts
```

Nota sobre este listado: los pares `R`/`RM` (p. ej. `CollaboratorPresence`, `VisionModal`,
`VoiceToolbar`, `UmlClassNode`, `UmlRelationshipEdge`, `ViewModeToggle`, `OfflineSyncBanner`) muestran
renombres que ya estaban en el índice de git (staged) desde antes de esta oleada — corresponden a la
reorganización a `features/` de una oleada anterior — y que además tienen modificaciones adicionales
sin stagear encima (la `M` junto a la `R`). No son trabajo nuevo de Agente 1 o Agente 2 en sí mismos,
salvo `OfflineSyncBanner.tsx`, que sí es tocado por Agente 1 (UC12) según el plan.

### `git diff --stat -- web/`

```
 web/docs/PLAN_ARQUITECTONICO.md                    | 242 ++++++++++++++-------
 web/src/App.tsx                                    |  14 +-
 web/src/components/ClassContextMenu.css            |  10 +
 web/src/components/ClassContextMenu.tsx            |  20 +-
 web/src/components/ErRelationshipEdge.css          |  32 +++
 web/src/components/ErRelationshipEdge.tsx          |  60 +++--
 web/src/components/ErTableNode.css                 |  23 ++
 web/src/components/ErTableNode.tsx                 |  72 ++++--
 web/src/features/colaboracion/CollaboratorPresence.tsx |   3 +-
 web/src/features/ia-asistida/VisionModal.tsx       |   2 +
 web/src/features/ia-asistida/VoiceToolbar.tsx      |   2 +
 web/src/features/modelado-manual/UmlClassNode.tsx  |  41 +++-
 web/src/features/modelado-manual/UmlRelationshipEdge.css |  31 +++
 web/src/features/modelado-manual/UmlRelationshipEdge.tsx |  61 +++---
 web/src/features/modelado-manual/ViewModeToggle.tsx |   3 +-
 web/src/features/offline/OfflineSyncBanner.tsx     |   4 +-
 web/src/store/useDiagramStore.ts                   |  73 ++++++-
 web/src/types/diagram.ts                           |   6 +
 18 files changed, 510 insertions(+), 189 deletions(-)
```

Observaciones sobre el snapshot inicial:

- `web/src/App.tsx` ya tiene 14 líneas modificadas (+/-) al momento de este snapshot. Todavía no se
  puede atribuir con certeza a qué agente corresponden esos cambios sin ver el diff completo línea por
  línea — se va a revisar en la próxima actualización cuando llegue el reporte real de alguno de los
  dos agentes.
- `web/src/store/useDiagramStore.ts` tiene 73 líneas modificadas — consistente con que Agente 1 ya
  empezó a agregar `diagramId` / encolado offline, según el plan.
- `web/src/types/diagram.ts` modificado (+6) — posiblemente tipos nuevos relacionados a offline
  (UC11/12) o a las nuevas entidades de gestión de proyecto (UC17/18/19). A confirmar.
- Ni `web/src/offline/` (cola IndexedDB), ni `ReconciliationModal.tsx`, ni `useOfflineSync.ts`, ni
  archivos nuevos en `web/src/components/` para UC13–UC19 aparecen todavía en el status — son
  archivos nuevos (untracked) que aún no se crearon, o el snapshot se tomó muy al comienzo de la
  oleada.
- Ningún cambio bajo `backend/` fue incluido en este reporte (se observaron modificaciones ahí —
  incluyendo un reordenamiento de paquetes de `com.example.demo` a `com.modelcollab` — pero están
  fuera de mi incumbencia y no se documentan acá).

## Cierre de la Oleada 2

### Build final (`npm run build`) — resultado LITERAL

Cronología completa, sin suavizar nada:

1. Al cierre de Agente 2, `npm run build` daba verde (reportado por el orquestador).
2. Corrido de nuevo después del cierre de Agente 2: ROTO, con `App.tsx(21,1): 'ReconciliationModal' is
   declared but its value is never read` y `App.tsx(113,9): All destructured elements are unused` —
   causado por Agente 1 todavía trabajando en vivo sobre `App.tsx` (import y destructuring sin conectar
   al JSX todavía). No fue una regresión de Agente 2.
3. Ambas sesiones (la mía y la de Agente 1) sufrieron un corte por timeout de infraestructura (600s sin
   progreso) mientras corrían builds en curso.
4. El orquestador reanudó a Agente 1, que terminó de conectar `ReconciliationModal`/`useOfflineSync`
   al JSX.
5. El orquestador corrió `npm run build` **de forma independiente, sin confiar solo en el reporte del
   propio Agente 1**, y confirmó:

```
tsc -b && vite build
289 módulos transformados
✓ built in 1.91s
```

Sin errores. Este es el resultado final y definitivo de la Oleada 2.

### Verificación independiente mía (cruce de datos, no solo tomar la palabra del orquestador)

Corrí `git status --short --untracked-files=all -- web/` y `git diff --stat -- web/` una vez más
después del reporte de cierre de Agente 1, desde el root del repo. Resultado: exactamente los mismos
27 archivos afectados que ya venía registrando en las pasadas anteriores de este documento (ningún
archivo nuevo ni desaparecido), lo que es consistente con "Agente 1 y Agente 2 ya terminaron, nada
más está cambiando". Confirmé puntualmente:

- `web/src/offline/offlineQueue.ts` y `web/src/offline/syncOffline.ts` existen (untracked, paquete
  nuevo de Agente 1).
- `web/src/features/offline/useOfflineSync.ts`, `ReconciliationModal.tsx` y `ReconciliationModal.css`
  existen (untracked).
- `web/src/App.tsx` (líneas ~103-123): leí el código directamente y confirmé que `updateConnectionStatus`
  está envuelto en `useCallback`, con un comentario del propio Agente 1 explicando el bug de re-render
  en cadena que corrige — coincide con lo que reportó el orquestador, no es solo su palabra.
- **Una discrepancia menor, sin impacto real:** el orquestador listó `web/src/features/offline/OfflineSyncBanner.css`
  como archivo "nuevo" de este cierre. En mi verificación aparece en `git status` como `R`
  (renombrado desde `web/src/components/OfflineSyncBanner.css`, ya staged desde la reorganización de
  la oleada anterior a `features/`), no como archivo nuevo de Agente 1 propiamente. Lo dejo anotado
  como aclaración, no como error del reporte — el archivo de todas formas está en la ruta correcta y
  el contenido relevante (estilos del banner) es preexistente, no algo que haya que revisar de más.

No se detectó ninguna superposición destructiva entre Agente 1 y Agente 2 en `App.tsx` ni en
`useDiagramStore.ts` a lo largo de toda la oleada — ver sección "Riesgo de colisión conocido" más
arriba para el detalle de la coordinación (el caso `setDiagramId`).

### Resumen Hecho / Placeholder-con-TODO por UC

Estado al cierre de la Oleada 2 en sí (antes de la actualización posterior fuera de oleada, ver esa
sección más abajo para el estado final actual):

| UC | Resultado al cierre de la Oleada 2 |
|----|-----------|
| UC11 — Cola offline IndexedDB | Hecho (real, no placeholder). Sync mockeada por decisión explícita (backend `/sync-offline` no listo), con TODO. |
| UC12 — Reconciliación al reconectar | Hecho (real, no placeholder). Sync todavía mockeada (dependía de UC11). |
| UC13 — Generar Backend | Hecho (conectado de verdad). |
| UC14 — Generar App Móvil | Placeholder con TODO explícito (según lo pedido; backend UC14 en curso). |
| UC15 — Exportar a XMI | Hecho (conectado de verdad). |
| UC16 — Importar XMI | Placeholder con TODO explícito (según lo pedido; backend UC16 en curso). |
| UC17 — Crear proyecto | Hecho (conectado de verdad). |
| UC18 — Invitar colaborador | Hecho (conectado de verdad). |
| UC19 — Eliminar proyecto | Hecho (conectado de verdad). |

### Archivos tocados, por agente

**Agente 1 (UC11/UC12):**
- Nuevos: `web/src/offline/offlineQueue.ts`, `web/src/offline/syncOffline.ts`,
  `web/src/features/offline/useOfflineSync.ts`, `web/src/features/offline/ReconciliationModal.tsx`,
  `web/src/features/offline/ReconciliationModal.css`.
- Modificados: `web/src/store/useDiagramStore.ts` (+185/-…, helpers `sendOrQueueLocked`/
  `sendOrQueueFree`, `deleteClass`/`deleteRelationship` con cascade, `diagramId`/`setDiagramId`),
  `web/src/types/diagram.ts`, `web/src/features/offline/OfflineSyncBanner.tsx` (+28/-…), `web/src/App.tsx`
  (wiring de `useOfflineSync` + `ReconciliationModal`, fix de `useCallback` en
  `updateConnectionStatus`).

**Agente 2 (UC13/UC14/UC15/UC16/UC17/UC18/UC19):**
- Nuevos: `web/src/components/GenerateBackendButton.tsx` + `.css`, `web/src/components/GenerateMobileAppButton.tsx`,
  `web/src/components/ExportXmiButton.tsx`, `web/src/components/ImportXmiButton.tsx`,
  `web/src/components/CreateProjectModal.tsx`, `web/src/components/InviteMemberModal.tsx`,
  `web/src/components/DeleteProjectButton.tsx`, `web/src/components/ProjectModals.css`,
  `web/src/components/downloadUtils.ts`.
- Modificados: `web/src/collaboration/diagramBootstrap.ts` (exportó `API_BASE`/`authFetch`, antes
  privados del módulo), `web/src/App.tsx` (`projectIdRef`, captura de `projectId` de
  `ensureActiveDiagram`, dos grupos nuevos de toolbar, wiring de los 7 componentes nuevos, y el
  `setStoreDiagramId(diagramId)` que le faltaba al trabajo de Agente 1 — ver coordinación exitosa
  arriba).

Ambos agentes tocaron `web/src/App.tsx` y `web/src/store/useDiagramStore.ts` (los dos puntos de riesgo
de colisión identificados al inicio de la oleada), pero sin conflicto destructivo: se coordinaron
correctamente sobre `diagramId`/`setDiagramId`.

### Cierre

Oleada 2 completa (UC11–UC19) cerrada del lado de este reporte de observación. Build final verde
(`289 módulos, ✓ built in 1.91s`, confirmado de forma independiente por el orquestador). Única
limitación pendiente, dejada explícita y no suavizada por el propio Agente 1: el comportamiento de
IndexedDB (UC11) está verificado por tipos/compilación pero no ejecutado en un navegador real, por
falta de un test runner de browser en este proyecto.

## Actualización posterior a la oleada (fuera de Oleada 2)

Después del cierre formal de la Oleada 2, backend confirmó el cierre de los 3 endpoints que habían
quedado mockeados/placeholder (UC12 sync real, UC14, UC16). El orquestador hizo el reemplazo
directamente (sin lanzar un agente nuevo, por ser una tarea chica y mecánica) y me pasó el reporte
para que lo audite y documente.

**Verificación independiente que hice** (no me limité a transcribir el reporte): corrí
`git status --short --untracked-files=all -- web/` y `git diff --stat -- web/` de nuevo — mismo
conjunto de 27 archivos que ya venía registrando, sin sorpresas. Además leí directamente el contenido
de los tres archivos clave:

- `web/src/offline/syncOffline.ts` — confirmé que ya no hay mock: llama a `authFetch(token,
  \`/diagrams/${diagramId}/sync-offline\`, { method: 'POST', body: JSON.stringify({ operations }) })`.
  Confirmé también la nota de contrato tal cual la reportó el orquestador: el mapeo de cada
  `QueuedMutation` fusiona `targetId` dentro de `payload` cuando `targetId !== null`
  (`payload: mutation.targetId !== null ? { ...mutation.payload, targetId: mutation.targetId } :
  mutation.payload`), consistente con que `ADD_CLASS`/`ADD_RELATIONSHIP` ya encolan `targetId: null` y
  por lo tanto no llevan ese campo agregado.
- `web/src/components/ImportXmiButton.tsx` — confirmé que el fetch apunta a
  `${API_BASE}/projects/${projectId}/import-xmi` (nivel de PROYECTO, no de diagrama, tal como aclaró
  el orquestador), que usa `fetch` directo en vez de `authFetch` (comentario explícito en el código:
  `authFetch` forzaría `Content-Type: application/json`, que pisaría el `multipart/form-data` con
  boundary que arma el browser para el `FormData`), y que al recibir el `DiagramResponse` (201) muestra
  un panel con el botón "Abrir este diagrama" que llama a `writeCached(userId, { projectId:
  createdDiagram.projectId, diagramId: createdDiagram.id })` seguido de `window.location.reload()` —
  coincide exactamente con la descripción del orquestador.
- `web/src/App.tsx` — confirmé en la línea 378 `<GenerateMobileAppButton diagramId={storeDiagramId}
  />` y en la línea 380 `<ImportXmiButton projectId={projectIdRef.current} />`: ambos componentes,
  antes placeholders sin props, ahora reciben lo que necesitan para funcionar de verdad.
- `web/src/components/GenerateBackendButton.css` — confirmé que existe la clase
  `.generate-backend-btn__panel-title--success`, reutilizada por `ImportXmiButton` para el caso de
  éxito (el panel base es rojo, pensado originalmente solo para errores).

Todo lo reportado por el orquestador cruzó correctamente contra el código real; no encontré
discrepancias en esta actualización.

### Estado final tras esta actualización (reemplaza el resumen "al cierre de la Oleada 2" de más
arriba para los 3 UC afectados)

| UC | Resultado final |
|----|-----------|
| UC11 — Cola offline IndexedDB | Hecho (real). Sync ahora también real (ver UC12), ya no mockeada. |
| UC12 — Reconciliación al reconectar | Hecho (real). Sync real conectada a `POST /diagrams/{id}/sync-offline`. |
| UC13 — Generar Backend | Hecho (conectado de verdad). |
| UC14 — Generar App Móvil | Hecho (conectado de verdad) — ya no placeholder. |
| UC15 — Exportar a XMI | Hecho (conectado de verdad). |
| UC16 — Importar XMI | Hecho (conectado de verdad) — ya no placeholder. Crea un diagrama nuevo a nivel de proyecto, no fusiona sobre el diagrama abierto. |
| UC17 — Crear proyecto | Hecho (conectado de verdad). |
| UC18 — Invitar colaborador | Hecho (conectado de verdad). |
| UC19 — Eliminar proyecto | Hecho (conectado de verdad). |

**Los 9 UC de la Oleada 2 (UC11–UC19) quedan TODOS conectados de verdad. Cero placeholders
pendientes.** Build verde confirmado por el orquestador: `tsc -b && vite build`, 289 módulos, sin
errores.

## Historial de actualizaciones

- **2026-09-16 — snapshot inicial.** Archivo creado. Tabla poblada con las 9 UC en estado "En
  progreso" según lo informado por el orquestador. Falta primer reporte real de avance de Agente 1 o
  Agente 2.
- **2026-09-16 — cierre de Agente 2 + build transitorio roto (Agente 1 en curso).** Procesado el
  reporte del orquestador sobre el cierre completo de Agente 2 (UC13/UC15/UC17/UC18/UC19 conectados de
  verdad; UC14/UC16 como placeholder con TODO). Verificado de forma independiente con `git status
  --short --untracked-files=all -- web/` y `git diff -- web/src/App.tsx web/src/store/useDiagramStore.ts`
  que los 9 archivos nuevos reportados existen y que `App.tsx`/`useDiagramStore.ts` reflejan el
  wiring descrito. Documentada la coordinación exitosa entre agentes sobre `setDiagramId`. Anotado el
  build transitoriamente roto (por Agente 1 en curso, no por Agente 2) sin marcarlo como regresión
  real. Nota operativa: esta actualización se completó en una sesión posterior a un corte por timeout
  de infraestructura (no un error propio); el estado de git verificado en esta pasada coincide con el
  que ya se había leído en la pasada anterior (mismos 27 archivos en `git status`, mismo diff de
  `App.tsx`/`useDiagramStore.ts`), así que no hay indicios de que se haya perdido información del
  reporte del orquestador. Sigue pendiente el cierre real y confirmado de Agente 1, con el resultado
  literal de `npm run build` que va a mandar el orquestador.
- **2026-09-16 — cierre de Agente 1 y cierre de la Oleada 2.** Procesado el reporte del orquestador
  sobre el cierre real de Agente 1 (UC11/UC12 pasan a "Terminado") y el build final en verde
  (`289 módulos, ✓ built in 1.91s`, corrido por el orquestador de forma independiente). Verificado con
  una nueva pasada de `git status --short --untracked-files=all -- web/` y `git diff --stat -- web/`
  que el conjunto de archivos no cambió respecto a la pasada anterior (27 archivos, consistente con
  que ambos agentes ya habían terminado). Leí directamente el código de `web/src/App.tsx` líneas
  ~103-123 y confirmé el fix de `useCallback` en `updateConnectionStatus` que reportó Agente 1 como
  hallazgo de calidad. Detecté y anoté una discrepancia menor sin impacto (`OfflineSyncBanner.css`
  reportado como "nuevo" pero es en realidad un rename staged de la oleada anterior). Agregada la
  sección "Cierre de la Oleada 2" con resumen Hecho/Placeholder por UC, archivos por agente y el
  resultado literal del build. Oleada 2 (UC11–UC19) queda cerrada.
- **2026-09-16 — actualización posterior fuera de oleada: los 3 placeholders/mocks restantes pasan a
  conectados de verdad.** El orquestador reemplazó directamente (sin agente nuevo) el mock de
  `syncOfflineQueue` (UC12) por el endpoint real `POST /diagrams/{id}/sync-offline`, y reescribió
  `GenerateMobileAppButton.tsx` (UC14) e `ImportXmiButton.tsx` (UC16) para conectarlos a sus backends
  recién cerrados. Verifiqué de forma independiente leyendo el código completo de los 3 archivos
  afectados más `App.tsx` y `GenerateBackendButton.css`: todo coincide con lo reportado, incluida la
  nota de contrato sobre `targetId` fusionado en `payload` y la corrección de que el import de XMI es
  a nivel de proyecto (no de diagrama). Sin discrepancias encontradas. Con esto, los 9 UC de la
  Oleada 2 quedan todos conectados de verdad, cero placeholders pendientes.
