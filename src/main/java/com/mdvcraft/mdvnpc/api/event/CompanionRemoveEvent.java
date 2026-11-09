package com.mdvcraft.mdvnpc.api.event;

import com.mdvcraft.mdvnpc.api.Companion;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Un acompañante acaba de eliminarse. El handle ya no es válido. */
public final class CompanionRemoveEvent extends Event {
    public enum Reason {
        /** Se llamó a {@link Companion#remove()}. */
        REMOVED,
        /** Ningún visor sigue conectado (removeWhenViewersLeave). */
        VIEWERS_LEFT,
        /** El plugin dueño se deshabilitó. */
        OWNER_DISABLED,
        /** MDVNPC se deshabilitó. */
        SHUTDOWN,
        /** La entidad desapareció y no se pudo recrear. */
        LOST
    }

    private static final HandlerList HANDLERS = new HandlerList();
    private final Companion companion;
    private final Reason reason;

    public CompanionRemoveEvent(@NotNull Companion companion, @NotNull Reason reason) {
        this.companion = companion;
        this.reason = reason;
    }

    public @NotNull Companion companion() { return companion; }
    public @NotNull Reason reason() { return reason; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
