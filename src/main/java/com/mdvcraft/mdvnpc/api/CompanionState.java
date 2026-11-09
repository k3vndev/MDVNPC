package com.mdvcraft.mdvnpc.api;

/** Qué está haciendo un acompañante. */
public enum CompanionState {
    /** Quieto, mirando a su objetivo de mirada. */
    IDLE,
    /** Siguiendo a un jugador de forma continua. */
    FOLLOWING,
    /** Caminando hacia un destino. */
    MOVING,
    /** Guiando a un jugador hacia un destino, esperándolo si se queda atrás. */
    LEADING,
    /** Eliminado; el handle ya no hace nada. */
    REMOVED
}
