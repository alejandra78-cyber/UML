import type { OperationType } from '../types/collaboration'
import type { Attribute, CanonicalModel, ClassEntity, Method, Relationship, Waypoint } from '../types/diagram'

/**
 * Espejo en TypeScript de CanonicalModelMutator (backend): aplica sobre una copia
 * en memoria del CanonicalModel el mismo catálogo de operaciones de la sección 8.1,
 * usado para reconstruir el estado local a partir de los StompBroadcastMessage
 * recibidos en /topic/diagrams/{id}. Solo cubre las operaciones que este cliente
 * puede emitir o necesita reflejar (ver alcance de la Fase 1: sin paquetes,
 * waypoints, bulk-merge ni cursores).
 */
export function applyOperation(
  model: CanonicalModel,
  operationType: OperationType,
  targetId: string | null,
  payload: Record<string, unknown>,
): CanonicalModel {
  switch (operationType) {
    case 'ADD_CLASS':
      return addClass(model, payload)
    case 'MOVE_CLASS':
      return updateClassById(model, targetId, (c) => ({
        ...c,
        position: { x: numberField(payload, 'x', c.position.x), y: numberField(payload, 'y', c.position.y) },
      }))
    case 'RESIZE_CLASS':
      return updateClassById(model, targetId, (c) => ({
        ...c,
        width: numberField(payload, 'width', c.width),
        height: numberField(payload, 'height', c.height),
      }))
    case 'RENAME_CLASS':
      return updateClassById(model, targetId, (c) => ({
        ...c,
        name: stringField(payload, 'name') ?? c.name,
      }))
    case 'DELETE_CLASS':
      return {
        ...model,
        classes: model.classes.filter((c) => c.id !== targetId),
        relationships: model.relationships.filter(
          (r) => r.sourceClassId !== targetId && r.targetClassId !== targetId,
        ),
      }
    case 'ADD_ATTRIBUTE':
      return updateClassById(model, targetId, (c) => {
        const newAttribute = payload as unknown as Attribute
        if (c.attributes.some((a) => a.id === newAttribute.id)) return c
        return { ...c, attributes: [...c.attributes, newAttribute] }
      })
    case 'UPDATE_ATTRIBUTE':
      return updateAttributeById(model, targetId, (a) => ({ ...a, ...payload }))
    case 'DELETE_ATTRIBUTE':
      return {
        ...model,
        classes: model.classes.map((c) => ({
          ...c,
          attributes: c.attributes.filter((a) => a.id !== targetId),
        })),
      }
    case 'ADD_METHOD':
      return updateClassById(model, targetId, (c) => {
        const newMethod = payload as unknown as Method
        if (c.methods.some((m) => m.id === newMethod.id)) return c
        return { ...c, methods: [...c.methods, newMethod] }
      })
    case 'UPDATE_METHOD':
      return updateMethodById(model, targetId, (m) => ({ ...m, ...payload }))
    case 'DELETE_METHOD':
      return {
        ...model,
        classes: model.classes.map((c) => ({
          ...c,
          methods: c.methods.filter((m) => m.id !== targetId),
        })),
      }
    case 'ADD_RELATIONSHIP': {
      const newRelationship = payload as unknown as Relationship
      if (model.relationships.some((r) => r.id === newRelationship.id)) return model
      return { ...model, relationships: [...model.relationships, newRelationship] }
    }
    case 'DELETE_RELATIONSHIP':
      return { ...model, relationships: model.relationships.filter((r) => r.id !== targetId) }
    case 'UPDATE_WAYPOINTS': {
      // A diferencia de UPDATE_RELATIONSHIP (que preserva los waypoints existentes
      // a propósito, ver CanonicalModelMutator.updateRelationship), esta es la ÚNICA
      // operación que los modifica -- LWW_FREE, igual que MOVE_CLASS, para arrastre fluido.
      const waypoints = (payload.waypoints as Waypoint[] | undefined) ?? []
      return {
        ...model,
        relationships: model.relationships.map((r) => (r.id === targetId ? { ...r, waypoints } : r)),
      }
    }
    case 'UPDATE_RELATIONSHIP': {
      // Solo type/owningSide/joinTableName/multiplicidades/roles/isNavigable son mutables
      // (id, sourceClassId, targetClassId y waypoints se preservan siempre, igual que en
      // CanonicalModelMutator.updateRelationship del backend, para no "destruir y recrear
      // el conector").
      return {
        ...model,
        relationships: model.relationships.map((r) => (r.id === targetId ? { ...r, ...payload } : r)),
      }
    }
    default:
      // ACQUIRE_LOCK/RELEASE_LOCK/paquetes/BULK_MERGE/USER_CURSOR:
      // fuera de alcance de este núcleo (sin locks, sin paquetes, sin cursores todavía).
      return model
  }
}

function addClass(model: CanonicalModel, payload: Record<string, unknown>): CanonicalModel {
  const newClass = payload as unknown as ClassEntity
  if (model.classes.some((c) => c.id === newClass.id)) {
    return model
  }
  return { ...model, classes: [...model.classes, newClass] }
}

function updateClassById(
  model: CanonicalModel,
  classId: string | null,
  updater: (classEntity: ClassEntity) => ClassEntity,
): CanonicalModel {
  if (!classId) return model
  return {
    ...model,
    classes: model.classes.map((c) => (c.id === classId ? updater(c) : c)),
  }
}

function updateAttributeById(
  model: CanonicalModel,
  attributeId: string | null,
  updater: (attribute: Attribute) => Attribute,
): CanonicalModel {
  if (!attributeId) return model
  return {
    ...model,
    classes: model.classes.map((c) => ({
      ...c,
      attributes: c.attributes.map((a) => (a.id === attributeId ? updater(a) : a)),
    })),
  }
}

function updateMethodById(
  model: CanonicalModel,
  methodId: string | null,
  updater: (method: Method) => Method,
): CanonicalModel {
  if (!methodId) return model
  return {
    ...model,
    classes: model.classes.map((c) => ({
      ...c,
      methods: c.methods.map((m) => (m.id === methodId ? updater(m) : m)),
    })),
  }
}

function stringField(payload: Record<string, unknown>, key: string): string | null {
  const v = payload[key]
  return typeof v === 'string' ? v : null
}

function numberField(payload: Record<string, unknown>, key: string, fallback: number): number {
  const v = payload[key]
  return typeof v === 'number' ? v : fallback
}
