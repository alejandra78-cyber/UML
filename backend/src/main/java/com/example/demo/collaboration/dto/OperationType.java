package com.example.demo.collaboration.dto;

/**
 * Catalogo formal y cerrado de operaciones atomicas STOMP (seccion 6 del documento
 * de arquitectura). Cada valor lleva asociado su mecanismo de exclusion mutua, de
 * modo que {@code CollaborationStompController} pueda decidir, sin tablas externas,
 * si una mutacion requiere lock, se resuelve por LWW o no requiere nada.
 */
public enum OperationType {
    ACQUIRE_LOCK(MutualExclusionMode.LOCK_ENGINE),
    RELEASE_LOCK(MutualExclusionMode.LOCK_ENGINE),

    ADD_CLASS(MutualExclusionMode.NONE),
    MOVE_CLASS(MutualExclusionMode.LWW_FREE),
    RENAME_CLASS(MutualExclusionMode.LOCK_REQUIRED),
    DELETE_CLASS(MutualExclusionMode.LOCK_REQUIRED),

    ADD_ATTRIBUTE(MutualExclusionMode.LOCK_REQUIRED),
    UPDATE_ATTRIBUTE(MutualExclusionMode.LOCK_REQUIRED),
    DELETE_ATTRIBUTE(MutualExclusionMode.LOCK_REQUIRED),

    ADD_RELATIONSHIP(MutualExclusionMode.NONE),
    UPDATE_WAYPOINTS(MutualExclusionMode.LWW_FREE),
    DELETE_RELATIONSHIP(MutualExclusionMode.LOCK_REQUIRED),
    UPDATE_RELATIONSHIP(MutualExclusionMode.LOCK_REQUIRED),

    RESIZE_CLASS(MutualExclusionMode.LWW_FREE),

    ADD_METHOD(MutualExclusionMode.LOCK_REQUIRED),
    UPDATE_METHOD(MutualExclusionMode.LOCK_REQUIRED),
    DELETE_METHOD(MutualExclusionMode.LOCK_REQUIRED),

    ADD_PACKAGE(MutualExclusionMode.NONE),
    UPDATE_PACKAGE(MutualExclusionMode.LOCK_REQUIRED),
    DELETE_PACKAGE(MutualExclusionMode.LOCK_REQUIRED),

    BULK_MERGE(MutualExclusionMode.DIAGRAM_LOCK),

    USER_CURSOR(MutualExclusionMode.NONE);

    private final MutualExclusionMode mutualExclusionMode;

    OperationType(MutualExclusionMode mutualExclusionMode) {
        this.mutualExclusionMode = mutualExclusionMode;
    }

    public MutualExclusionMode mutualExclusionMode() {
        return mutualExclusionMode;
    }

    public boolean requiresLock() {
        return mutualExclusionMode == MutualExclusionMode.LOCK_REQUIRED
                || mutualExclusionMode == MutualExclusionMode.DIAGRAM_LOCK;
    }

    public boolean isLwwFree() {
        return mutualExclusionMode == MutualExclusionMode.LWW_FREE;
    }
}
