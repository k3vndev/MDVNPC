package com.mdvcraft.mdvnpc.integration;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Excepción LIMITADA a la aparición de los aldeanos base de MDVNPC.
 * WorldGuard aplica MOB_SPAWNING / DENY_SPAWN en CreatureSpawnEvent a prioridad HIGH.
 * Nosotros actuamos después (HIGHEST) solo cuando el NPC estaba en construcción,
 * el evento NO estaba cancelado en LOWEST y el flag de la región realmente lo bloquea.
 * No se cambian flags, regiones, permisos ni otros tipos de entidad.
 *
 * Importante: la API Bukkit no dice QUÉ plugin canceló un evento. Un tercer plugin
 * que también lo cancele entre LOWEST y HIGHEST no puede distinguirse con certeza;
 * para ese caso el diagnóstico MONITOR y la comprobación de isValid siguen vigentes.
 */
public final class WorldGuardSpawnHook implements Listener {
    private final MdvNpcPlugin plugin;
    private final Map<UUID, Boolean> originallyCancelled = new HashMap<>();

    public WorldGuardSpawnHook(MdvNpcPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.LOWEST)
    public void recordBeforeProtection(CreatureSpawnEvent event) {
        if (belongsToInFlightNpc(event)) {
            originallyCancelled.put(event.getEntity().getUniqueId(), event.isCancelled());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void allowOurNpcThroughRegionFlags(CreatureSpawnEvent event) {
        Boolean cancelledAtStart = originallyCancelled.remove(event.getEntity().getUniqueId());
        if (!plugin.settings().messages().getBoolean("worldguard-spawn-bypass", true)) return;
        if (cancelledAtStart == null || !belongsToInFlightNpc(event)
                || !SpawnBypassPolicy.shouldOverride(cancelledAtStart, event.isCancelled(), true)) return;
        try {
            // Consultar el estado EFECTIVO: respeta regiones superpuestas, prioridades,
            // herencia y la región __global__ sin tocar sus configuraciones.
            ApplicableRegionSet regions = WorldGuard.getInstance().getPlatform()
                    .getRegionContainer().createQuery()
                    .getApplicableRegions(BukkitAdapter.adapt(event.getLocation()));
            // Si WorldGuard no pudo cargar la información, NO quitar la protección.
            if (regions.isVirtual()) return;
            boolean mobSpawningDenied = !regions.testState(null, Flags.MOB_SPAWNING);
            Set<com.sk89q.worldedit.world.entity.EntityType> deniedTypes =
                    regions.queryValue(null, Flags.DENY_SPAWN);
            boolean villagerDenied = deniedTypes != null
                    && deniedTypes.contains(BukkitAdapter.adapt(EntityType.VILLAGER));
            if (!SpawnBypassPolicy.shouldOverride(cancelledAtStart, event.isCancelled(),
                    mobSpawningDenied || villagerDenied)) return;

            event.setCancelled(false);
            plugin.getLogger().fine("Excepción WorldGuard aplicada exclusivamente al NPC "
                    + (plugin.manager().isSpawningNpc(event.getEntity())
                        ? plugin.manager().pendingNpcId(event.getEntity()) : "(acompañante temporal)"));
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().log(Level.WARNING,
                    "No se pudo consultar WorldGuard al generar un NPC propio; no se modificó la cancelación.", exception);
        }
    }

    private boolean belongsToInFlightNpc(CreatureSpawnEvent event) {
        return event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM
                && event.getEntityType() == EntityType.VILLAGER
                && (plugin.manager().isSpawningNpc(event.getEntity())
                    || plugin.companions() != null && plugin.companions().isSpawning(event.getEntity()));
    }
}
