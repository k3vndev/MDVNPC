package com.mdvcraft.mdvnpc.api;

/** No se pudo crear un acompañante. {@link #reason()} indica el motivo. */
public final class CompanionSpawnException extends RuntimeException {
    public enum Reason { DISABLED, LIMIT_REACHED, UNKNOWN_TEMPLATE, INVALID_LOCATION, SPAWN_BLOCKED, DISGUISE_FAILED }

    private final Reason reason;

    public CompanionSpawnException(Reason reason, String message) {
        this(reason, message, null);
    }

    public CompanionSpawnException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
