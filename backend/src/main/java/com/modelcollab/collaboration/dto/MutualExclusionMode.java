package com.modelcollab.collaboration.dto;

/**
 * Mecanismo de exclusion mutua exigido por una {@link OperationType}.
 */
public enum MutualExclusionMode {
    /** No requiere ningun mecanismo (p.ej. ADD_CLASS, ADD_RELATIONSHIP). */
    NONE,
    /** Gestionado directamente por el motor de locks (ACQUIRE_LOCK/RELEASE_LOCK). */
    LOCK_ENGINE,
    /** Last-Write-Wins libre por timestamp de servidor, sin lock (60 FPS friendly). */
    LWW_FREE,
    /** Requiere que el emisor posea un soft-lock activo sobre el targetId. */
    LOCK_REQUIRED,
    /** Requiere un lock a nivel de diagrama completo (operaciones atomicas masivas). */
    DIAGRAM_LOCK
}
