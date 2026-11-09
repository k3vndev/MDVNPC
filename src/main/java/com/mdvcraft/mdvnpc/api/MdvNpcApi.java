package com.mdvcraft.mdvnpc.api;

import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * API pública de MDVNPC, registrada en el ServicesManager de Bukkit.
 *
 * <pre>{@code
 * MdvNpcApi npcs = Bukkit.getServicesManager().load(MdvNpcApi.class);
 * }</pre>
 *
 * <p>Los acompañantes son NPC <b>temporales</b>: no se guardan en {@code npcs.yml},
 * no tienen rutinas, sobreviven a {@code /mdvnpc reload} y desaparecen al reiniciar el
 * servidor o al deshabilitarse el plugin que los creó. Solo los ven los jugadores
 * indicados en {@link CompanionSpec}.</p>
 *
 * <p>Todos los métodos deben llamarse desde el hilo principal del servidor.</p>
 */
public interface MdvNpcApi {
    /** false si {@code companions.enabled} está desactivado en la config de MDVNPC. */
    boolean companionsEnabled();

    /**
     * Crea un acompañante temporal.
     *
     * @throws CompanionSpawnException si no se pudo crear (límite, mundo, plantilla, WorldGuard…).
     */
    Companion spawnCompanion(CompanionSpec spec);

    Optional<Companion> companion(UUID companionId);

    /** El acompañante cuya entidad base es {@code entity}, si existe. */
    Optional<Companion> companion(Entity entity);

    boolean isCompanion(Entity entity);

    /** Acompañantes vivos creados por {@code owner}. */
    List<Companion> companions(Plugin owner);

    int activeCompanions();

    int maxCompanions();
}
