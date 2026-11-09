package com.mdvcraft.mdvnpc.api;

/** Resultado de {@link Companion#moveTo} y {@link Companion#lead}. */
public enum MoveResult {
    /** Llegó al destino. */
    ARRIVED,
    /** Otra orden (follow, moveTo, lead, stop, teleport) reemplazó a esta, o el jugador guiado se desconectó. */
    CANCELLED,
    /** No encontró ruta o se quedó atascado demasiado tiempo. */
    FAILED,
    /** El acompañante fue eliminado. */
    REMOVED
}
