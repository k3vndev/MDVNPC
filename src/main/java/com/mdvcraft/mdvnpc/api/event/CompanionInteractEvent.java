package com.mdvcraft.mdvnpc.api.event;

import com.mdvcraft.mdvnpc.api.Companion;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Un visor hizo click (derecho o izquierdo) sobre un acompañante. */
public final class CompanionInteractEvent extends Event {
    public enum Click { RIGHT, LEFT }

    private static final HandlerList HANDLERS = new HandlerList();
    private final Companion companion;
    private final Player player;
    private final Click click;

    public CompanionInteractEvent(@NotNull Companion companion, @NotNull Player player, @NotNull Click click) {
        this.companion = companion;
        this.player = player;
        this.click = click;
    }

    public @NotNull Companion companion() { return companion; }
    public @NotNull Player player() { return player; }
    public @NotNull Click click() { return click; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
