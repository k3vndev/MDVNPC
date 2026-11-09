package com.mdvcraft.mdvnpc.companion;

import com.mdvcraft.mdvnpc.api.event.CompanionInteractEvent;
import io.papermc.paper.event.entity.EntityMoveEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Protecciones y eventos de los acompañantes. Solo actúa sobre entidades con el marcador companion-id. */
public final class CompanionListener implements Listener {
    private final CompanionService service;

    public CompanionListener(CompanionService service) {
        this.service = service;
    }

    private boolean companion(Entity entity) {
        return entity != null && service.marked(entity);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void interactAt(PlayerInteractAtEntityEvent event) {
        if (companion(event.getRightClicked()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void interact(PlayerInteractEntityEvent event) {
        if (!companion(event.getRightClicked()))
            return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND)
            return;
        fire(event.getRightClicked(), event.getPlayer(), CompanionInteractEvent.Click.RIGHT);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void attack(PrePlayerAttackEntityEvent event) {
        if (!companion(event.getAttacked()))
            return;
        event.setCancelled(true);
        fire(event.getAttacked(), event.getPlayer(), CompanionInteractEvent.Click.LEFT);
    }

    private void fire(Entity entity, Player player, CompanionInteractEvent.Click click) {
        CompanionRuntime runtime = service.runtime(entity);
        if (runtime == null || !runtime.isValid() || !runtime.isViewer(player))
            return;
        service.plugin().getServer().getPluginManager().callEvent(new CompanionInteractEvent(runtime, player, click));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void damage(EntityDamageEvent event) {
        if (companion(event.getEntity()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void target(EntityTargetLivingEntityEvent event) {
        if (companion(event.getTarget()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void teleport(EntityTeleportEvent event) {
        if (companion(event.getEntity()) && !service.internal(event.getEntity()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void move(EntityMoveEvent event) {
        if (event.hasChangedPosition() && companion(event.getEntity()) && !service.internal(event.getEntity()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void portal(EntityPortalEvent event) {
        if (companion(event.getEntity()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void portalEnter(EntityPortalEnterEvent event) {
        if (companion(event.getEntity()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void burn(EntityCombustEvent event) {
        if (companion(event.getEntity()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void transform(EntityTransformEvent event) {
        if (companion(event.getEntity()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void mount(EntityMountEvent event) {
        if (companion(event.getEntity()) || companion(event.getMount()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void vehicle(VehicleEnterEvent event) {
        if (companion(event.getEntered()))
            event.setCancelled(true);
    }

    @EventHandler
    public void entitiesLoad(EntitiesLoadEvent event) {
        event.getEntities().forEach(service::removeStale);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        service.playerJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        service.playerQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void pluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() != service.plugin())
            service.ownerDisabled(event.getPlugin());
    }
}
