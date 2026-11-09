package com.mdvcraft.mdvnpc.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Handle de un acompañante temporal. Todos los métodos deben llamarse desde el hilo
 * principal. Después de {@link #remove()} los métodos no hacen nada y los futuros
 * pendientes terminan con {@link MoveResult#REMOVED}.
 *
 * <p>Las órdenes de movimiento se reemplazan entre sí: llamar a {@code follow},
 * {@code moveTo}, {@code lead}, {@code stop} o {@code teleport} termina la anterior
 * con {@link MoveResult#CANCELLED}.</p>
 */
public interface Companion {
    UUID id();

    Plugin owner();

    /** NPC de {@code npcs.yml} usado como plantilla de nombre/skin/prefijo, o null. */
    @Nullable String templateId();

    /** Nombre mostrado (con códigos de color sin traducir). */
    String name();

    boolean isValid();

    CompanionState state();

    /** Posición actual (copia). */
    Location location();

    /** UUID de la entidad base actual. Puede cambiar si el acompañante se recrea en otro mundo. */
    UUID entityId();

    // ---------------------------------------------------------------- visibilidad

    /** Jugadores que pueden ver al acompañante. */
    Set<UUID> viewers();

    boolean isViewer(Player player);

    void show(Player player);

    void hide(Player player);

    // ---------------------------------------------------------------- movimiento

    /** Sigue a {@code target} a la distancia configurada ({@code companions.follow-distance}). */
    void follow(Player target);

    /** Sigue a {@code target} manteniéndose a unos {@code distance} bloques (1.5–8). */
    void follow(Player target, double distance);

    /** Camina hasta {@code destination} a la velocidad por defecto. */
    CompletableFuture<MoveResult> moveTo(Location destination);

    /** Camina hasta {@code destination} a {@code blocksPerSecond} (0.5–8). */
    CompletableFuture<MoveResult> moveTo(Location destination, double blocksPerSecond);

    /**
     * Guía a {@code follower} hasta {@code destination}: camina delante y se para a
     * esperarlo si se aleja más de {@code companions.lead-max-gap} bloques.
     */
    CompletableFuture<MoveResult> lead(Location destination, Player follower);

    /** Se queda quieto donde está. */
    void stop();

    /** Teletransporta al acompañante (lo recrea si es otro mundo). Queda quieto. */
    void teleport(Location location);

    // ---------------------------------------------------------------- comportamiento

    /**
     * Jugador al que mira mientras no camina. null = el jugador que sigue o guía,
     * o el visor más cercano.
     */
    void lookAt(@Nullable Player player);

    void gesture(CompanionGesture gesture);

    /** Envía un mensaje de chat del NPC (con su prefijo) a todos los visores conectados. */
    void say(String message);

    /** Envía un mensaje de chat del NPC solo a {@code viewer}. */
    void say(Player viewer, String message);

    /** Elimina al acompañante. */
    void remove();
}
