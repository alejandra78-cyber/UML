import { useCallback, useEffect, useRef, useState } from 'react'
import ReactFlow, {
  Background,
  Controls,
  MiniMap,
  type Connection,
  type EdgeTypes,
  type NodeTypes,
  type ReactFlowInstance,
} from 'reactflow'
import 'reactflow/dist/style.css'
import { LoginScreen } from './auth/LoginScreen'
import { useAuthStore } from './auth/useAuthStore'
import { ProjectSelector } from './components/ProjectSelector'
import { UmlClassNode } from './features/modelado-manual/UmlClassNode'
import { UmlRelationshipEdge, RELATIONSHIP_TYPE_OPTIONS } from './features/modelado-manual/UmlRelationshipEdge'
import { ErTableNode } from './components/ErTableNode'
import { ErRelationshipEdge } from './components/ErRelationshipEdge'
import { ViewModeToggle } from './features/modelado-manual/ViewModeToggle'
import { CollaboratorPresence } from './features/colaboracion/CollaboratorPresence'
import { OfflineSyncBanner } from './features/offline/OfflineSyncBanner'
import { ReconciliationModal } from './features/offline/ReconciliationModal'
import { useOfflineSync } from './features/offline/useOfflineSync'
import { VoiceToolbar } from './features/ia-asistida/VoiceToolbar'
import { VisionModal } from './features/ia-asistida/VisionModal'
import { GenerateBackendButton } from './components/GenerateBackendButton'
import { ExportXmiButton } from './components/ExportXmiButton'
import { ImportXmiButton } from './components/ImportXmiButton'
import { CreateProjectModal } from './components/CreateProjectModal'
import { InviteMemberModal } from './components/InviteMemberModal'
import { DeleteProjectButton } from './components/DeleteProjectButton'
import { ToolbarDropdown } from './components/ToolbarDropdown'
import { diagramStompClient } from './collaboration/stompClient'
import {
  resolveDiagramForProject,
  tryResolveCachedActiveDiagram,
  type ActiveDiagramResult,
  type ProjectResponse,
  type SnapshotResponse,
} from './collaboration/diagramBootstrap'
import { useDiagramStore } from './store/useDiagramStore'
import { useViewModeStore } from './store/useViewModeStore'
import { usePresenceStore } from './store/usePresenceStore'
import { useConnectionStore, type ConnectionStatus } from './store/useConnectionStore'
import type { CanonicalModel, RelationshipType } from './types/diagram'
import './App.css'

const umlNodeTypes: NodeTypes = { umlClass: UmlClassNode }
const erNodeTypes: NodeTypes = { umlClass: ErTableNode }
const umlEdgeTypes: EdgeTypes = { umlRelationship: UmlRelationshipEdge }
const erEdgeTypes: EdgeTypes = { umlRelationship: ErRelationshipEdge }

// Color determinístico por usuario (presencia: cursores remotos y bordes de
// lock necesitan un color estable por sesión sin depender de un campo de
// backend). Paleta fija de 8 colores + hash simple del userId.
const PRESENCE_COLOR_PALETTE = [
  '#e6194b',
  '#3cb44b',
  '#4363d8',
  '#f58231',
  '#911eb4',
  '#46f0f0',
  '#f032e6',
  '#bcf60c',
]

function colorForUser(userId: string): string {
  let hash = 0
  for (let i = 0; i < userId.length; i++) {
    hash = (hash * 31 + userId.charCodeAt(i)) | 0
  }
  return PRESENCE_COLOR_PALETTE[Math.abs(hash) % PRESENCE_COLOR_PALETTE.length]
}

/**
 * Botón "Paquete" de la barra superior: placeholder inerte, igual patrón que
 * VoiceToolbar/VisionModal (Fase 4). El frontend hoy no tiene ningún soporte de
 * paquetes (CanonicalModel no trae `packages`, no hay acción addPackage en el
 * store) aunque el backend y el plan ya definen ADD_PACKAGE/UPDATE_PACKAGE/
 * DELETE_PACKAGE -- implementarlo de verdad es lógica nueva, fuera del alcance de
 * "solo capa de presentación" de esta tarea. Queda listo para activarse después.
 */
function PackageToolButton() {
  const [showComingSoon, setShowComingSoon] = useState(false)

  function handleClick() {
    setShowComingSoon(true)
    window.setTimeout(() => setShowComingSoon(false), 2000)
  }

  return (
    <span className="diagram-toolbar__package-btn">
      <button type="button" className="diagram-toolbar__icon-btn" title="Paquete (próximamente)" onClick={handleClick}>
        <span aria-hidden>📦</span> Paquete
      </button>
      {showComingSoon && <span className="diagram-toolbar__tooltip">Próximamente</span>}
    </span>
  )
}

interface DiagramWorkspaceProps {
  projectId: string
  diagramId: string
  initialSnapshot: SnapshotResponse
  /** Vuelve al selector de proyectos (botón "Mis proyectos" y post-borrado del
   * proyecto activo, ver DeleteProjectButton). */
  onBackToSelector: () => void
  /** Cambia el workspace a OTRO proyecto ya resuelto (p.ej. uno recién creado
   * desde el dropdown "Proyecto" sin salir del lienzo) -- el padre (ProjectGate)
   * lo usa para remontar este componente con `key={diagramId}` apuntando al
   * nuevo proyecto/diagrama. */
  onProjectActivated: (result: ActiveDiagramResult) => void
}

function DiagramWorkspace({ projectId, diagramId, initialSnapshot, onBackToSelector, onProjectActivated }: DiagramWorkspaceProps) {
  const token = useAuthStore((state) => state.token)
  const userId = useAuthStore((state) => state.userId)
  const fullName = useAuthStore((state) => state.fullName)
  const logout = useAuthStore((state) => state.logout)

  const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>('connecting')
  const setGlobalConnectionStatus = useConnectionStore((state) => state.setStatus)

  // Memoizado a propósito: se pasa a useOfflineSync, que a su vez lo mete en las
  // dependencias de un useCallback (`handleReconnect`) que el efecto principal
  // (más abajo) también lista como dependencia. Sin useCallback acá,
  // updateConnectionStatus sería una función NUEVA en cada render (nodes/edges
  // cambian en CADA mutación del diagrama), lo que habría vuelto inestable a
  // handleReconnect y, en cadena, habría hecho que el efecto de conexión STOMP se
  // desmontara y reconectara en cada edición -- no solo al reconectar de verdad.
  const updateConnectionStatus = useCallback(
    (status: ConnectionStatus) => {
      setConnectionStatus(status)
      setGlobalConnectionStatus(status)
    },
    [setGlobalConnectionStatus],
  )

  // PKG-04 Resiliencia Offline (UC12): al reconectar, revisa si quedaron
  // mutaciones encoladas en IndexedDB mientras el transport era null (ver
  // useDiagramStore.ts / offline/offlineQueue.ts) y, si las hay, sincroniza antes
  // de pasar a 'online' -- ver el llamado a `handleReconnect` dentro de
  // `onConnected` más abajo.
  const { reconciliationReport, dismissReport, handleReconnect } = useOfflineSync(updateConnectionStatus)

  const nodes = useDiagramStore((state) => state.nodes)
  const edges = useDiagramStore((state) => state.edges)
  const onNodesChange = useDiagramStore((state) => state.onNodesChange)
  const onEdgesChange = useDiagramStore((state) => state.onEdgesChange)
  const addClass = useDiagramStore((state) => state.addClass)
  const addRelationship = useDiagramStore((state) => state.addRelationship)
  const connectTransport = useDiagramStore((state) => state.connectTransport)
  const disconnectTransport = useDiagramStore((state) => state.disconnectTransport)
  const applyBroadcast = useDiagramStore((state) => state.applyBroadcast)
  const hydrate = useDiagramStore((state) => state.hydrate)
  // diagramId del store (usado internamente por la cola offline, ver
  // useDiagramStore.ts -- cada acción de mutación lo lee de `get()` para
  // encolar en IndexedDB si el transport está caído): sigue existiendo y
  // sincronizándose en el efecto de abajo, pero YA NO hace falta leerlo de
  // vuelta acá para las props de la barra -- `diagramId`/`projectId` llegan
  // resueltos por props desde ProjectGate, conocidos desde el primer render.
  const setStoreDiagramId = useDiagramStore((state) => state.setDiagramId)

  const viewMode = useViewModeStore((state) => state.viewMode)
  const applyPresenceMessage = usePresenceStore((state) => state.applyPresenceMessage)
  const setLock = usePresenceStore((state) => state.setLock)
  const clearLock = usePresenceStore((state) => state.clearLock)
  const setCurrentUserId = usePresenceStore((state) => state.setCurrentUserId)
  const reactFlowInstanceRef = useRef<ReactFlowInstance | null>(null)
  const lastCursorSendRef = useRef(0)
  const [projectActivationError, setProjectActivationError] = useState<string | null>(null)

  // "Modo herramienta" de relación (punto 6, barra superior): un clic en uno de los
  // 6 botones de tipo "arma" ese tipo para la SIGUIENTE conexión manual que el
  // usuario arrastre entre dos handles -- no crea nada por sí solo (una relación
  // necesita origen y destino, a diferencia de una clase suelta). Se consume (vuelve
  // a null) apenas se usa en handleConnect.
  const [armedRelationshipType, setArmedRelationshipType] = useState<RelationshipType | null>(null)

  useEffect(() => {
    if (!token || !userId) return
    // React.StrictMode (ver main.tsx) invoca este efecto dos veces en dev
    // (mount -> cleanup -> mount) para detectar efectos no cancelables. Sin el
    // guard "cancelled" en cada callback de diagramStompClient.connect, la
    // primera ejecución (ya "cancelada") seguía completando su conexión --
    // como el cliente STOMP es un singleton, cualquiera de las dos ejecuciones
    // podía ganar la carrera y desconectar a la otra. Si la ejecución
    // "cancelada" ganaba, su propio guard de "cancelled" descartaba el
    // onConnected y CONNECT_TRANSPORT nunca se llamaba: la conexión WebSocket
    // quedaba viva pero sin transport asignado en el store, por lo que
    // useDiagramStore.addClass() caía siempre en el fallback local sin red
    // (transport === null) y "+ Clase" no disparaba ninguna petición.
    //
    // A diferencia de antes, `diagramId`/`initialSnapshot` ya llegan resueltos
    // por props (ver ProjectGate) -- no hay ninguna resolución async previa que
    // cancelar acá, así que ya no hace falta el AbortController ni el
    // try/catch alrededor de esa parte.
    let cancelled = false

    updateConnectionStatus('connecting')

    const parsed = JSON.parse(initialSnapshot.currentState) as Partial<CanonicalModel>
    hydrate({
      schemaVersion: parsed.schemaVersion ?? '1.0.0',
      mutationVersion: parsed.mutationVersion ?? 1,
      classes: parsed.classes ?? [],
      relationships: parsed.relationships ?? [],
    })
    setStoreDiagramId(diagramId)

    diagramStompClient.connect(diagramId, token, {
      onConnected: () => {
        if (cancelled) return
        // Antes de pasar a 'online' de verdad, revisa si quedó algo encolado de
        // una sesión offline anterior (o de una caída de STOMP en esta misma
        // sesión, gracias al reconnectDelay del cliente) y lo sincroniza primero
        // -- ver useOfflineSync.ts. `connectNow` es exactamente lo que este
        // callback hacía antes de que existiera la cola offline: conectar el
        // transport real y unirse a la sala de presencia.
        void handleReconnect(diagramId, token, () => {
          connectTransport({
            send: (operationType, targetId, payload) =>
              diagramStompClient.sendMutation(operationType, targetId, userId, payload),
            acquireLock: (targetId) => diagramStompClient.acquireLock(targetId, userId),
            releaseLock: (targetId) => diagramStompClient.releaseLock(targetId, userId),
          })
          // Presencia (RF-04.3): alta en la sala del diagrama para que los demás
          // colaboradores nos vean (cursor, nombre, color) y para que LOCK_ACQUIRED
          // pueda resolver nuestro userName/color reales en vez del genérico
          // "Usuario"/#999999 (ver RoomManager.findMember en el backend).
          setCurrentUserId(userId)
          diagramStompClient.joinPresence(diagramId, userId, fullName ?? 'Usuario', colorForUser(userId))
        })
      },
      onDisconnected: () => {
        if (cancelled) return
        updateConnectionStatus('offline')
        disconnectTransport()
      },
      onError: (message) => {
        console.error('Error STOMP:', message)
      },
      onServerError: (message) => {
        console.error('El backend rechazó la última mutación:', message)
      },
      onBroadcast: applyBroadcast,
      onPresence: applyPresenceMessage,
      onLockChange: (info) =>
        'released' in info ? clearLock(info.targetId) : setLock(info.targetId, info.userId, info.userName, info.color),
    })

    return () => {
      cancelled = true
      diagramStompClient.disconnect()
      disconnectTransport()
      setStoreDiagramId(null)
    }
  }, [
    token,
    userId,
    fullName,
    diagramId,
    initialSnapshot,
    connectTransport,
    disconnectTransport,
    applyBroadcast,
    applyPresenceMessage,
    setLock,
    clearLock,
    setCurrentUserId,
    hydrate,
    setStoreDiagramId,
    handleReconnect,
  ])

  function handleConnect(connection: Connection) {
    if (!connection.source || !connection.target) return
    // Si hay un tipo "armado" desde la barra superior (punto 6), se consume acá y
    // se apaga -- una sola conexión por armado, como una herramienta de un solo uso.
    const type = armedRelationshipType ?? 'ASSOCIATION'
    if (armedRelationshipType) setArmedRelationshipType(null)
    addRelationship({
      sourceClassId: connection.source,
      targetClassId: connection.target,
      type,
      owningSide: 'SOURCE',
      joinTableName: null,
      sourceMultiplicity: '1..1',
      targetMultiplicity: '0..*',
      sourceRole: '',
      targetRole: '',
      isNavigable: true,
      waypoints: [],
    })
  }

  function toggleArmedRelationshipType(type: RelationshipType) {
    setArmedRelationshipType((current) => (current === type ? null : type))
  }

  // Crear un proyecto adicional desde DENTRO del lienzo (dropdown "Proyecto")
  // ahora también lo activa de inmediato -- requiere resolver su diagrama (ver
  // resolveDiagramForProject) antes de poder avisarle a ProjectGate, así que
  // acá sí puede fallar (proyecto creado bien, pero sin poder listar/crear su
  // diagrama) de forma independiente del propio CreateProjectModal.
  async function handleProjectCreatedInToolbar(project: ProjectResponse) {
    if (!token || !userId) return
    setProjectActivationError(null)
    try {
      const result = await resolveDiagramForProject(token, userId, project.id)
      onProjectActivated(result)
    } catch (err) {
      setProjectActivationError(err instanceof Error ? err.message : 'No se pudo abrir el proyecto recién creado')
    }
  }

  // Cursor remoto (RF-04.3): reenvía la posición del mouse sobre el lienzo a los
  // demás colaboradores, throttleado a ~15 envíos/seg para no saturar la red.
  const CURSOR_THROTTLE_MS = 65
  function handlePaneMouseMove(event: React.MouseEvent) {
    const instance = reactFlowInstanceRef.current
    if (!instance || !userId) return

    const now = Date.now()
    if (now - lastCursorSendRef.current < CURSOR_THROTTLE_MS) return
    lastCursorSendRef.current = now

    const flowPosition = instance.screenToFlowPosition({ x: event.clientX, y: event.clientY })
    diagramStompClient.sendCursorUpdate(
      diagramId,
      userId,
      fullName ?? 'Usuario',
      colorForUser(userId),
      flowPosition.x,
      flowPosition.y,
      null,
    )
  }

  const statusLabel =
    connectionStatus === 'online'
      ? 'Conectado'
      : connectionStatus === 'connecting'
        ? 'Conectando…'
        : connectionStatus === 'syncing'
          ? 'Sincronizando…'
          : 'Desconectado'

  const armedOption = RELATIONSHIP_TYPE_OPTIONS.find((o) => o.value === armedRelationshipType)

  return (
    <div className="diagram-app">
      <OfflineSyncBanner />
      {reconciliationReport && <ReconciliationModal report={reconciliationReport} onClose={dismissReport} />}
      <header className="diagram-toolbar">
        <h1>Diagramador UML/ER</h1>
        <span className={`connection-badge connection-badge--${connectionStatus}`}>{statusLabel}</span>

        <div className="diagram-toolbar__group" role="group" aria-label="Insertar elemento">
          <button type="button" className="diagram-toolbar__icon-btn" title="Agregar clase" onClick={() => addClass()}>
            <span aria-hidden>▭</span> Clase
          </button>
          <PackageToolButton />
        </div>

        <div
          className="diagram-toolbar__group diagram-toolbar__group--relations"
          role="group"
          aria-label="Herramienta de relación (clic para armar, luego arrastra entre dos clases)"
        >
          {RELATIONSHIP_TYPE_OPTIONS.map((option) => (
            <button
              key={option.value}
              type="button"
              className={`diagram-toolbar__icon-btn${
                armedRelationshipType === option.value ? ' diagram-toolbar__icon-btn--armed' : ''
              }`}
              title={`${option.label}: clic para armar esta herramienta, luego arrastra entre dos clases para conectarlas`}
              onClick={() => toggleArmedRelationshipType(option.value)}
            >
              <span aria-hidden>{option.icon}</span>
            </button>
          ))}
        </div>

        {armedOption && (
          <span className="diagram-toolbar__armed-hint">
            {armedOption.icon} {armedOption.label}: arrastra entre dos clases
          </span>
        )}

        {/* Uso frecuente durante el modelado: se queda siempre visible, sin
            agrupar (ver reorganización de la barra más abajo). */}
        <div className="diagram-toolbar__group" role="group" aria-label="Vista y comandos de IA">
          <ViewModeToggle />
          <VoiceToolbar diagramId={diagramId} />
        </div>

        <div className="diagram-toolbar__spacer" />

        <button type="button" className="diagram-toolbar__icon-btn" title="Volver a la lista de proyectos" onClick={onBackToSelector}>
          <span aria-hidden>📂</span> Mis proyectos
        </button>

        {/* Uso ocasional: agrupados detrás de menús desplegables (antes eran 7
            botones sueltos entre estos dos grupos + VisionModal, y la barra ya
            se cortaba en el borde derecho en pantallas angostas -- ver
            ToolbarDropdown y el flex-wrap de .diagram-toolbar como red de
            seguridad adicional). */}
        <ToolbarDropdown icon="📁" label="Proyecto" ariaLabel="Menú de gestión de proyecto">
          <CreateProjectModal onCreated={handleProjectCreatedInToolbar} />
          <InviteMemberModal projectId={projectId} />
          <DeleteProjectButton projectId={projectId} onDeleted={onBackToSelector} />
          {projectActivationError && <span className="diagram-toolbar__tooltip">{projectActivationError}</span>}
        </ToolbarDropdown>

        <ToolbarDropdown icon="⚙️" label="Generar / Exportar" ariaLabel="Menú de generación e interoperabilidad">
          <GenerateBackendButton diagramId={diagramId} />
          <ExportXmiButton diagramId={diagramId} />
          <ImportXmiButton projectId={projectId} />
          <VisionModal diagramId={diagramId} />
        </ToolbarDropdown>

        <button type="button" className="diagram-toolbar__logout" onClick={logout}>
          Salir
        </button>
      </header>
      <div className="diagram-canvas">
        {/* Sin fitView: la posición real (x, y) de cada clase persiste correctamente
            (verificado contra Postgres), pero fitView re-encuadra la CÁMARA (pan/zoom)
            en cada montaje de <ReactFlow> -- es decir, en cada recarga de página. Eso
            hacía parecer que las clases "perdían su posición y se centraban", cuando en
            realidad los datos nunca cambiaron: era la cámara la que se reajustaba. */}
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={viewMode === 'ER' ? erNodeTypes : umlNodeTypes}
          edgeTypes={viewMode === 'ER' ? erEdgeTypes : umlEdgeTypes}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={handleConnect}
          onInit={(instance) => {
            reactFlowInstanceRef.current = instance
          }}
          onPaneMouseMove={handlePaneMouseMove}
        >
          <Background />
          <Controls />
          <MiniMap />
          <CollaboratorPresence />
        </ReactFlow>
      </div>
    </div>
  )
}

/**
 * Reemplaza a la vieja `ensureActiveDiagram` como punto único de "qué proyecto
 * abrir" -- antes esa función lo decidía sola (caché o `projects[0]`) sin darle
 * al usuario ninguna opción real; ahora esa decisión es siempre explícita (ver
 * ProjectSelector) o el último proyecto que el usuario mismo activó en este
 * navegador (validado de nuevo antes de confiar en él, por si mientras tanto
 * perdió acceso o el proyecto se borró).
 */
function ProjectGate() {
  const token = useAuthStore((state) => state.token)
  const userId = useAuthStore((state) => state.userId)

  const [phase, setPhase] = useState<'checking' | 'selector' | 'workspace'>('checking')
  const [active, setActive] = useState<ActiveDiagramResult | null>(null)

  useEffect(() => {
    if (!token || !userId) return
    let cancelled = false
    const controller = new AbortController()

    async function check() {
      try {
        const cached = await tryResolveCachedActiveDiagram(token as string, userId as string, controller.signal)
        if (cancelled) return
        if (cached) {
          setActive(cached)
          setPhase('workspace')
        } else {
          setPhase('selector')
        }
      } catch (err) {
        if (err instanceof DOMException && err.name === 'AbortError') return
        if (!cancelled) setPhase('selector')
      }
    }

    check()
    return () => {
      cancelled = true
      controller.abort()
    }
  }, [token, userId])

  function handleActivated(result: ActiveDiagramResult) {
    setActive(result)
    setPhase('workspace')
  }

  function handleBackToSelector() {
    setActive(null)
    setPhase('selector')
  }

  if (phase === 'checking') {
    return (
      <div className="project-gate-loading">
        <p>Cargando tu espacio de trabajo…</p>
      </div>
    )
  }

  if (phase === 'workspace' && active) {
    return (
      <DiagramWorkspace
        // Fuerza un remonte limpio al cambiar de proyecto/diagrama -- reutiliza
        // tal cual toda la lógica de conexión/limpieza que ya tenía el efecto
        // principal (STOMP connect/disconnect, hydrate) en vez de agregarle
        // manejo de "cambiar de diagramId en caliente" a ese efecto ya delicado
        // (ver los comentarios de StrictMode ahí mismo).
        key={active.diagramId}
        projectId={active.projectId}
        diagramId={active.diagramId}
        initialSnapshot={active.snapshot}
        onBackToSelector={handleBackToSelector}
        onProjectActivated={handleActivated}
      />
    )
  }

  return <ProjectSelector onActivated={handleActivated} />
}

function App() {
  const token = useAuthStore((state) => state.token)
  return token ? <ProjectGate /> : <LoginScreen />
}

export default App
