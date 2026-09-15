// Espejo TypeScript del catálogo STOMP de la sección 8.1 del PLAN_ARQUITECTONICO.md
// (com.example.demo.collaboration.dto.OperationType / StompMutationMessage / StompBroadcastMessage).

export type OperationType =
  | 'ACQUIRE_LOCK'
  | 'RELEASE_LOCK'
  | 'ADD_CLASS'
  | 'MOVE_CLASS'
  | 'RENAME_CLASS'
  | 'DELETE_CLASS'
  | 'ADD_ATTRIBUTE'
  | 'UPDATE_ATTRIBUTE'
  | 'DELETE_ATTRIBUTE'
  | 'ADD_RELATIONSHIP'
  | 'UPDATE_WAYPOINTS'
  | 'DELETE_RELATIONSHIP'
  | 'UPDATE_RELATIONSHIP'
  | 'RESIZE_CLASS'
  | 'ADD_METHOD'
  | 'UPDATE_METHOD'
  | 'DELETE_METHOD'
  | 'ADD_PACKAGE'
  | 'UPDATE_PACKAGE'
  | 'DELETE_PACKAGE'
  | 'BULK_MERGE'
  | 'USER_CURSOR'

export interface StompMutationMessage {
  operationType: OperationType
  targetId: string | null
  userId: string | null
  clientTimestamp: number
  payload: Record<string, unknown>
}

export interface StompBroadcastMessage {
  sequenceNum: number
  operationType: OperationType
  targetId: string | null
  userId: string
  serverTimestamp: number
  payload: Record<string, unknown>
}
