import { applyEdgeChanges, applyNodeChanges, type Edge, type EdgeChange, type Node, type NodeChange } from 'reactflow'
import { create } from 'zustand'
import type { OperationType, StompBroadcastMessage } from '../types/collaboration'
import type { Attribute, CanonicalModel, ClassEntity, Method, Relationship } from '../types/diagram'
import { applyOperation } from './applyOperation'

// El diagrama arranca vacío: el estado real vive en PostgreSQL (current_state JSONB)
// y llega a este cliente exclusivamente vía broadcasts STOMP (ver DiagramMutationService).
const emptyModel: CanonicalModel = {
  schemaVersion: '1.0.0',
  mutationVersion: 1,
  classes: [],
  relationships: [],
}

function classToNode(classEntity: ClassEntity): Node {
  return {
    id: classEntity.id,
    type: 'umlClass',
    position: classEntity.position,
    data: { classEntity },
    style: { width: classEntity.width },
  }
}

function relationshipToEdge(relationship: Relationship): Edge {
  return {
    id: relationship.id,
    source: relationship.sourceClassId,
    target: relationship.targetClassId,
    type: 'umlRelationship',
    data: { relationship },
  }
}

// `previousNodes`/`previousEdges` preservan el estado de selección de React Flow
// (`.selected`, solo UI, nunca se serializa al backend): classToNode/
// relationshipToEdge no lo conocen, y sin este merge, CUALQUIER llamada a
// deriveGraph (un broadcast STOMP entrante, por ejemplo) regeneraba los arrays
// desde cero y borraba la selección de quien estuviera con una clase o relación
// seleccionada en ese momento -- confirmado con Playwright: seleccionar un
// elemento y que llegara cualquier otro broadcast lo des-seleccionaba sin que el
// usuario tocara nada (afectaba tanto a nodos como a edges).
function deriveGraph(
  model: CanonicalModel,
  previousNodes: Node[] = [],
  previousEdges: Edge[] = [],
): { nodes: Node[]; edges: Edge[] } {
  const selectedNodeById = new Map(previousNodes.map((n) => [n.id, n.selected]))
  const selectedEdgeById = new Map(previousEdges.map((e) => [e.id, e.selected]))
  return {
    nodes: model.classes.map((classEntity) => {
      const node = classToNode(classEntity)
      const selected = selectedNodeById.get(classEntity.id)
      return selected ? { ...node, selected } : node
    }),
    edges: model.relationships.map((relationship) => {
      const edge = relationshipToEdge(relationship)
      const selected = selectedEdgeById.get(relationship.id)
      return selected ? { ...edge, selected } : edge
    }),
  }
}

// El backend deserializa id/sourceClassId/targetClassId/etc. como java.util.UUID
// (ver ClassEntity, Attribute, Method, Relationship en metamodel/model): un id que
// no tenga formato UUID (p.ej. "class-abc123") hace que Jackson falle al convertir
// el payload y el backend rechace la mutación en silencio (solo avisa por
// /user/queue/errors, que hasta ahora nadie escuchaba). Por eso acá SIEMPRE se usa
// crypto.randomUUID(), nunca un id "amigable" con prefijo.
function createId(): string {
  return crypto.randomUUID()
}

/** Diferencia campo a campo dos objetos planos; usado para armar el payload parcial de UPDATE_ATTRIBUTE/UPDATE_METHOD. */
function diffFields<T extends Record<string, unknown>>(oldObj: T, newObj: T): Partial<T> {
  const diff: Partial<T> = {}
  for (const key of Object.keys(newObj) as (keyof T)[]) {
    if (JSON.stringify(oldObj[key]) !== JSON.stringify(newObj[key])) {
      diff[key] = newObj[key]
    }
  }
  return diff
}

type MutationTransport = {
  send: (operationType: OperationType, targetId: string | null, payload: Record<string, unknown>) => void
  /** Debe resolverse antes de enviar cualquier operación LOCK_REQUIRED (sección 8.1: RENAME_CLASS,
   * ADD_ATTRIBUTE, UPDATE_ATTRIBUTE, ADD_METHOD, UPDATE_METHOD, ...) — el backend las rechaza en
   * silencio si el emisor no tiene el lock vigente del target. */
  acquireLock: (targetId: string) => Promise<boolean>
  releaseLock: (targetId: string) => void
}

/** Adquiere el lock, ejecuta la mutación y libera; si el lock se deniega, no envía nada. */
async function withLock(transport: MutationTransport, targetId: string, send: () => void) {
  const granted = await transport.acquireLock(targetId)
  if (!granted) {
    console.warn('No se pudo adquirir el lock de', targetId, '(en uso por otro colaborador); mutación descartada')
    return
  }
  try {
    send()
  } finally {
    transport.releaseLock(targetId)
  }
}

interface DiagramState {
  model: CanonicalModel
  nodes: Node[]
  edges: Edge[]
  transport: MutationTransport | null
  connectTransport: (transport: MutationTransport) => void
  disconnectTransport: () => void
  hydrate: (model: CanonicalModel) => void
  applyBroadcast: (broadcast: StompBroadcastMessage) => void
  addClass: (partial?: Partial<ClassEntity>) => string
  updateClass: (id: string, patch: Partial<ClassEntity>) => void
  addAttribute: (classId: string) => string
  addMethod: (classId: string) => string
  moveAttribute: (classId: string, attributeId: string, direction: 'up' | 'down') => void
  moveMethod: (classId: string, methodId: string, direction: 'up' | 'down') => void
  togglePrimaryKey: (classId: string, attributeId: string) => void
  addRelationship: (relationship: Omit<Relationship, 'id'>) => void
  updateRelationship: (id: string, patch: Partial<Relationship>) => void
  updateWaypoints: (id: string, waypoints: Relationship['waypoints']) => void
  onNodesChange: (changes: NodeChange[]) => void
  onEdgesChange: (changes: EdgeChange[]) => void
}

export const useDiagramStore = create<DiagramState>((set, get) => ({
  model: emptyModel,
  ...deriveGraph(emptyModel),
  transport: null,

  connectTransport: (transport) => set({ transport }),
  disconnectTransport: () => set({ transport: null }),

  /** Reemplaza el modelo completo (usado al cargar el GET /snapshot inicial). */
  hydrate: (model) => {
    set({ model, ...deriveGraph(model) })
  },

  applyBroadcast: (broadcast) => {
    set((state) => {
      const model = applyOperation(state.model, broadcast.operationType, broadcast.targetId, broadcast.payload)
      return { model, ...deriveGraph(model, state.nodes, state.edges) }
    })
  },

  // NOTA DE DISEÑO (optimistic UI): addClass/updateClass/addRelationship mutan el
  // estado LOCAL de inmediato (el usuario ve el resultado sin esperar el viaje de
  // ida y vuelta por STOMP) Y ADEMÁS envían la mutación real al backend cuando hay
  // transport. El eco del propio broadcast vuelve a aplicar la misma operación,
  // pero applyOperation es idempotente por id para los ADD_* (ver applyOperation.ts)
  // y los UPDATE_*/RENAME/MOVE/RESIZE son reemplazos de campo, así que reaplicar el
  // eco es inofensivo. Antes de este cambio, con transport conectado NO se tocaba
  // el estado local y había que esperar el broadcast para ver cualquier cambio;
  // combinado con que varias operaciones (RENAME_CLASS, ADD_ATTRIBUTE, etc.)
  // requieren un soft-lock previo que nunca se pedía, la mutación se rechazaba en
  // silencio y visualmente "no pasaba nada".

  addClass: (partial) => {
    const id = partial?.id ?? createId()
    const newClass: ClassEntity = {
      id,
      name: partial?.name ?? 'NuevaClase',
      visibility: partial?.visibility ?? 'PUBLIC',
      isAbstract: partial?.isAbstract ?? false,
      position: partial?.position ?? { x: 120, y: 120 },
      width: partial?.width ?? 240,
      height: partial?.height ?? 180,
      attributes: partial?.attributes ?? [],
      methods: partial?.methods ?? [],
    }

    set((state) => {
      const model: CanonicalModel = { ...state.model, classes: [...state.model.classes, newClass] }
      return { model, ...deriveGraph(model, state.nodes, state.edges) }
    })

    const { transport } = get()
    if (transport) {
      // ADD_CLASS es NONE (sin exclusión mutua): no requiere lock.
      transport.send('ADD_CLASS', null, newClass as unknown as Record<string, unknown>)
    }

    return id
  },

  updateClass: (id, patch) => {
    const before = get().model.classes.find((c) => c.id === id)
    if (!before) return

    set((state) => {
      const nextModel: CanonicalModel = {
        ...state.model,
        classes: state.model.classes.map((c) => (c.id === id ? { ...c, ...patch } : c)),
      }
      return { model: nextModel, ...deriveGraph(nextModel, state.nodes, state.edges) }
    })

    const { transport } = get()
    if (!transport) return

    // RENAME_CLASS es LOCK_REQUIRED: hay que adquirir el lock sobre la CLASE.
    if (patch.name !== undefined) {
      void withLock(transport, id, () => transport.send('RENAME_CLASS', id, { name: patch.name }))
    }
    // MOVE_CLASS/RESIZE_CLASS son LWW_FREE: no requieren lock.
    if (patch.position !== undefined) {
      transport.send('MOVE_CLASS', id, { x: patch.position.x, y: patch.position.y })
    }
    if (patch.width !== undefined || patch.height !== undefined) {
      transport.send('RESIZE_CLASS', id, {
        width: patch.width ?? before.width,
        height: patch.height ?? before.height,
      })
    }
    if (patch.attributes !== undefined) {
      const nextIds = new Set(patch.attributes.map((a) => a.id))
      const existingIds = new Set(before.attributes.map((a) => a.id))
      for (const attribute of patch.attributes) {
        if (!existingIds.has(attribute.id)) {
          // ADD_ATTRIBUTE es LOCK_REQUIRED: el lock se pide sobre la CLASE (todavía
          // no existe un id de atributo del lado del servidor para bloquear).
          void withLock(transport, id, () =>
            transport.send('ADD_ATTRIBUTE', id, attribute as unknown as Record<string, unknown>),
          )
        } else {
          const previous = before.attributes.find((a) => a.id === attribute.id) as Attribute
          const diff = diffFields(previous as unknown as Record<string, unknown>, attribute as unknown as Record<string, unknown>)
          if (Object.keys(diff).length > 0) {
            // UPDATE_ATTRIBUTE es LOCK_REQUIRED: el lock se pide sobre el ATRIBUTO.
            void withLock(transport, attribute.id, () => transport.send('UPDATE_ATTRIBUTE', attribute.id, diff))
          }
        }
      }
      // Atributos que estaban en "before" y ya no están en el patch = eliminados.
      for (const attribute of before.attributes) {
        if (!nextIds.has(attribute.id)) {
          void withLock(transport, attribute.id, () => transport.send('DELETE_ATTRIBUTE', attribute.id, {}))
        }
      }
    }
    if (patch.methods !== undefined) {
      const nextIds = new Set(patch.methods.map((m) => m.id))
      const existingIds = new Set(before.methods.map((m) => m.id))
      for (const method of patch.methods) {
        if (!existingIds.has(method.id)) {
          void withLock(transport, id, () =>
            transport.send('ADD_METHOD', id, method as unknown as Record<string, unknown>),
          )
        } else {
          const previous = before.methods.find((m) => m.id === method.id) as Method
          const diff = diffFields(previous as unknown as Record<string, unknown>, method as unknown as Record<string, unknown>)
          if (Object.keys(diff).length > 0) {
            void withLock(transport, method.id, () => transport.send('UPDATE_METHOD', method.id, diff))
          }
        }
      }
      for (const method of before.methods) {
        if (!nextIds.has(method.id)) {
          void withLock(transport, method.id, () => transport.send('DELETE_METHOD', method.id, {}))
        }
      }
    }
  },

  // Centraliza la construcción de un Attribute/Method con sus defaults del esquema
  // canónico (sección 7): antes UmlClassNode y ErTableNode duplicaban esta misma
  // lógica cada uno por su lado. Ahora también la usa el menú contextual (punto 2)
  // para poder abrir el editor inline sobre la fila recién creada, algo que
  // requiere conocer el id generado -- por eso devuelve `string`, a diferencia de
  // `addClass` que ya hacía lo mismo.
  addAttribute: (classId) => {
    const newAttribute: Attribute = {
      id: createId(),
      name: 'nuevoAtributo',
      type: 'VARCHAR',
      visibility: 'PRIVATE',
      length: 255,
      precision: 10,
      scale: 2,
      isPrimaryKey: false,
      isNullable: true,
      isUnique: false,
      defaultValue: null,
    }
    const before = get().model.classes.find((c) => c.id === classId)
    if (before) {
      get().updateClass(classId, { attributes: [...before.attributes, newAttribute] })
    }
    return newAttribute.id
  },

  addMethod: (classId) => {
    const newMethod: Method = {
      id: createId(),
      name: 'nuevoMetodo',
      returnType: 'void',
      visibility: 'PUBLIC',
      parameters: [],
    }
    const before = get().model.classes.find((c) => c.id === classId)
    if (before) {
      get().updateClass(classId, { methods: [...before.methods, newMethod] })
    }
    return newMethod.id
  },

  // "Mover arriba/abajo" (punto 2 del menú contextual) sin ninguna operación STOMP
  // nueva: el esquema canónico no tiene un campo de "orden" explícito, así que en
  // vez de reordenar el ARRAY (lo que no dispararía ningún UPDATE_ATTRIBUTE, ya que
  // updateClass diffea por id y un simple reorder no cambia ningún campo de ningún
  // id) se intercambia el CONTENIDO entre los dos ids adyacentes, dejando los ids
  // fijos en su posición del array. Para cualquier cliente (incluido el emisor), el
  // resultado visible es idéntico a un reorder real -- y viaja por dos
  // UPDATE_ATTRIBUTE/UPDATE_METHOD normales, ya existentes.
  moveAttribute: (classId, attributeId, direction) => {
    const before = get().model.classes.find((c) => c.id === classId)
    if (!before) return
    const index = before.attributes.findIndex((a) => a.id === attributeId)
    const swapIndex = direction === 'up' ? index - 1 : index + 1
    if (index === -1 || swapIndex < 0 || swapIndex >= before.attributes.length) return

    const attributes = [...before.attributes]
    const a = attributes[index]
    const b = attributes[swapIndex]
    attributes[index] = { ...b, id: a.id }
    attributes[swapIndex] = { ...a, id: b.id }
    get().updateClass(classId, { attributes })
  },

  // Único punto de mutación de isPrimaryKey (antes UmlClassNode y ErTableNode
  // tenían cada uno su propia copia de este toggle, sin coordinarse entre sí:
  // se podía marcar un segundo atributo como PK sin que el primero se
  // desmarcara -- reproducido con datos nuevos, no era un dato de prueba
  // viejo). Una clase solo puede tener un PK activo a la vez: al marcar uno,
  // se desmarca cualquier otro que ya lo tuviera. Viaja por los mismos
  // UPDATE_ATTRIBUTE de siempre (updateClass diffea attribute por attribute),
  // uno por cada atributo que cambió.
  togglePrimaryKey: (classId, attributeId) => {
    const before = get().model.classes.find((c) => c.id === classId)
    if (!before) return
    const target = before.attributes.find((a) => a.id === attributeId)
    if (!target) return
    const makingPk = !target.isPrimaryKey
    const attributes = before.attributes.map((a) => {
      if (a.id === attributeId) return { ...a, isPrimaryKey: makingPk }
      if (makingPk && a.isPrimaryKey) return { ...a, isPrimaryKey: false }
      return a
    })
    get().updateClass(classId, { attributes })
  },

  moveMethod: (classId, methodId, direction) => {
    const before = get().model.classes.find((c) => c.id === classId)
    if (!before) return
    const index = before.methods.findIndex((m) => m.id === methodId)
    const swapIndex = direction === 'up' ? index - 1 : index + 1
    if (index === -1 || swapIndex < 0 || swapIndex >= before.methods.length) return

    const methods = [...before.methods]
    const a = methods[index]
    const b = methods[swapIndex]
    methods[index] = { ...b, id: a.id }
    methods[swapIndex] = { ...a, id: b.id }
    get().updateClass(classId, { methods })
  },

  addRelationship: (relationship) => {
    const id = createId()
    const newRelationship: Relationship = { ...relationship, id }

    set((state) => {
      const model: CanonicalModel = { ...state.model, relationships: [...state.model.relationships, newRelationship] }
      return { model, ...deriveGraph(model, state.nodes, state.edges) }
    })

    const { transport } = get()
    if (transport) {
      // ADD_RELATIONSHIP es NONE: no requiere lock.
      transport.send('ADD_RELATIONSHIP', null, newRelationship as unknown as Record<string, unknown>)
    }
  },

  updateRelationship: (id, patch) => {
    const before = get().model.relationships.find((r) => r.id === id)
    if (!before) return

    set((state) => {
      const nextModel: CanonicalModel = {
        ...state.model,
        relationships: state.model.relationships.map((r) => (r.id === id ? { ...r, ...patch } : r)),
      }
      return { model: nextModel, ...deriveGraph(nextModel, state.nodes, state.edges) }
    })

    const { transport } = get()
    if (!transport) return

    // UPDATE_RELATIONSHIP es LOCK_REQUIRED: el lock se pide sobre la RELACIÓN.
    void withLock(transport, id, () => transport.send('UPDATE_RELATIONSHIP', id, patch as Record<string, unknown>))
  },

  updateWaypoints: (id, waypoints) => {
    set((state) => {
      const nextModel: CanonicalModel = {
        ...state.model,
        relationships: state.model.relationships.map((r) => (r.id === id ? { ...r, waypoints } : r)),
      }
      return { model: nextModel, ...deriveGraph(nextModel, state.nodes, state.edges) }
    })

    const { transport } = get()
    if (transport) {
      // UPDATE_WAYPOINTS es LWW_FREE (igual que MOVE_CLASS): no requiere lock, para
      // que arrastrar el punto intermedio de la línea sea fluido.
      transport.send('UPDATE_WAYPOINTS', id, { waypoints: waypoints ?? [] })
    }
  },

  onNodesChange: (changes) => {
    // updatedNodes se captura DENTRO del set() para poder leer, después, la posición
    // final ya resuelta por applyNodeChanges -- ver por qué es necesario en el
    // comentario de más abajo.
    let updatedNodes: Node[] = []
    set((state) => {
      const nodes = applyNodeChanges(changes, state.nodes)
      updatedNodes = nodes
      const classes = state.model.classes.map((classEntity) => {
        const node = nodes.find((n) => n.id === classEntity.id)
        return node ? { ...classEntity, position: node.position } : classEntity
      })
      return { nodes, model: { ...state.model, classes } }
    })

    const { transport } = get()
    if (!transport) return
    for (const change of changes) {
      if (change.type === 'position' && change.dragging === false) {
        // BUG REAL encontrado con Playwright (navegador real, no un script propio):
        // React Flow dispara el evento de "fin de arrastre" llamando internamente a
        // updateNodePositions(items, positionChanged=false, dragging=false) -- ese
        // "positionChanged=false" hace que ESE change puntual NUNCA traiga
        // change.position (@reactflow/core, updateNodePositions). La condición
        // anterior ("change.dragging === false && change.position") exigía ambas
        // cosas en el MISMO evento, y nunca se cumplía: los eventos con
        // dragging:true sí traen position pero no pasan el chequeo de dragging, y el
        // evento con dragging:false nunca trae position. Resultado: MOVE_CLASS nunca
        // se enviaba al soltar el mouse (aunque la posición local sí se veía bien,
        // porque applyNodeChanges preserva la última posición conocida). La posición
        // final ya quedó bien resuelta en updatedNodes (arriba, vía applyNodeChanges,
        // que conserva el último valor de position aunque este evento puntual no lo
        // traiga), así que se lee de ahí en vez de depender de change.position.
        const node = updatedNodes.find((n) => n.id === change.id)
        if (node) {
          // MOVE_CLASS es LWW_FREE: no requiere lock.
          transport.send('MOVE_CLASS', change.id, { x: node.position.x, y: node.position.y })
        }
      }
    }
  },

  // Sin esto, <ReactFlow edges={edges}> queda "controlado" sin forma de aplicar
  // los cambios que reactflow dispara internamente (selección al hacer clic,
  // borrado con teclado, etc.): el clic en una relación nunca llegaba a marcarla
  // `selected` en el store, así que el highlight de selección jamás se activaba
  // (confirmado con Playwright: la clase del <g> del edge nunca ganaba "selected").
  // Solo se persiste la selección en memoria -- no hay ninguna operación STOMP de
  // "seleccionar edge", es puramente estado de UI local.
  onEdgesChange: (changes) => {
    set((state) => ({ edges: applyEdgeChanges(changes, state.edges) }))
  },
}))
