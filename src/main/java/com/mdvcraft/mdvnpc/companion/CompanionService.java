package com.mdvcraft.mdvnpc.companion;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.api.Companion;
import com.mdvcraft.mdvnpc.api.CompanionSpawnException;
import com.mdvcraft.mdvnpc.api.CompanionSpawnException.Reason;
import com.mdvcraft.mdvnpc.api.CompanionSpec;
import com.mdvcraft.mdvnpc.api.MdvNpcApi;
import com.mdvcraft.mdvnpc.api.event.CompanionRemoveEvent;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.routine.DoorController;
import com.mdvcraft.mdvnpc.routine.RoutineGravity;
import com.mdvcraft.mdvnpc.routine.RoutineNavigator;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.skin.DisguiseService;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Acompañantes temporales creados por otros plugins. Vive aparte de {@code NpcManager}:
 * no lee ni escribe npcs.yml, rutinas ni skins guardadas, usa su propio marcador PDC
 * ({@code companion-id}) y su propio navegador, así que /mdvnpc reload no lo toca.
 */
public final class CompanionService implements MdvNpcApi {
    public static final String TAG = "MDVNPC_COMPANION";

    private final MdvNpcPlugin plugin;
    private final NamespacedKey marker;
    private final DisguiseService disguises = new DisguiseService();
    private final Map<UUID, CompanionRuntime> byId = new LinkedHashMap<>();
    private final Map<UUID, CompanionRuntime> byEntity = new HashMap<>();
    private final Set<UUID> spawning = new HashSet<>();
    private CompanionSettings settings = CompanionSettings.defaults();
    private DoorController doors;
    private RoutineNavigator navigator;
    private RoutineGravity gravity;
    private BukkitTask task;
    private UUID internalEntity;
    private long ticks;
    private int sequence;
    private boolean started;

    public CompanionService(MdvNpcPlugin plugin) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "companion-id");
    }

    // ------------------------------------------------------------------ ciclo de vida

    public void start() {
        if (started)
            return;
        doors = new DoorController(plugin);
        navigator = new RoutineNavigator(doors, this::teleportInternal);
        gravity = new RoutineGravity(doors, this::teleportInternal);
        reconfigure();
        // Restos de un cierre abrupto: los acompañantes nunca se guardan.
        for (World world : Bukkit.getWorlds())
            for (Entity entity : world.getEntities())
                removeStale(entity);
        plugin.getServer().getServicesManager().register(MdvNpcApi.class, this, plugin, ServicePriority.Normal);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        started = true;
    }

    /** Relee {@code companions.*} y la configuración de navegación. Los acompañantes activos siguen igual. */
    public void reconfigure() {
        ConfigurationSection cfg = plugin.settings() == null ? null : plugin.settings().messages();
        settings = CompanionSettings.from(cfg);
        if (navigator == null || cfg == null)
            return;
        navigator.configureHeights(cfg.getDouble("routines.max-climb-height", 1.30),
                cfg.getDouble("routines.max-drop-height", 2.30), cfg.getDouble("routines.max-unannounced-drop", 1.01));
        navigator.configureNodes(cfg.getBoolean("routines.nodes.enabled", true),
                cfg.getInt("routines.nodes.link-range", 12), cfg.getInt("routines.nodes.endpoint-range", 16),
                cfg.getInt("routines.nodes.scan-margin", 24), cfg.getInt("routines.nodes.max-distance", 256),
                cfg.getInt("routines.nodes.scan-blocks-per-update", 8192),
                cfg.getInt("routines.nodes.search-nodes-per-update", 256));
    }

    public void close() {
        if (!started)
            return;
        started = false;
        if (task != null)
            task.cancel();
        task = null;
        for (CompanionRuntime companion : new ArrayList<>(byId.values()))
            remove(companion, CompanionRemoveEvent.Reason.SHUTDOWN);
        plugin.getServer().getServicesManager().unregister(MdvNpcApi.class, this);
        if (navigator != null)
            navigator.clear();
        if (doors != null)
            doors.close();
    }

    public CompanionSettings settings() {
        return settings;
    }

    public void invalidateNodes(World world, int blockX, int blockZ) {
        if (navigator != null)
            navigator.invalidateNodes(world, blockX, blockZ);
    }

    // ------------------------------------------------------------------ API pública

    @Override
    public boolean companionsEnabled() {
        return started && settings.enabled();
    }

    @Override
    public Companion spawnCompanion(CompanionSpec spec) {
        requireMainThread();
        if (!companionsEnabled())
            throw new CompanionSpawnException(Reason.DISABLED, "Los acompañantes están desactivados (companions.enabled)");
        if (!spec.owner().isEnabled())
            throw new CompanionSpawnException(Reason.DISABLED, "El plugin " + spec.owner().getName() + " no está habilitado");
        if (byId.size() >= settings.maxActive())
            throw new CompanionSpawnException(Reason.LIMIT_REACHED,
                    "Límite de acompañantes alcanzado (" + settings.maxActive() + ")");
        NpcDefinition template = null;
        if (spec.templateId() != null) {
            template = plugin.definitions().get(spec.templateId());
            if (template == null)
                throw new CompanionSpawnException(Reason.UNKNOWN_TEMPLATE, "No existe el NPC plantilla " + spec.templateId());
        }
        Location at = spec.spawn();
        if (!loaded(at))
            throw new CompanionSpawnException(Reason.INVALID_LOCATION, "La ubicación no está en un chunk cargado");

        NpcDefinition definition = CompanionDefinitions.create("companion-" + (++sequence), template, spec, settings, at);
        NpcDefinition.Skin skin = CompanionDefinitions.skin(template, spec, settings, definition,
                template == null ? null : plugin.skins().resolve(template));
        CompanionRuntime runtime = new CompanionRuntime(this, UUID.randomUUID(), spec, definition, skin);
        runtime.attach(spawnEntity(runtime, at));
        byId.put(runtime.id(), runtime);
        byEntity.put(runtime.npc().entity().getUniqueId(), runtime);
        return runtime;
    }

    @Override
    public Optional<Companion> companion(UUID companionId) {
        return Optional.ofNullable(byId.get(companionId));
    }

    @Override
    public Optional<Companion> companion(Entity entity) {
        return Optional.ofNullable(entity == null ? null : byEntity.get(entity.getUniqueId()));
    }

    @Override
    public boolean isCompanion(Entity entity) {
        return entity != null && (byEntity.containsKey(entity.getUniqueId()) || marked(entity));
    }

    @Override
    public List<Companion> companions(Plugin owner) {
        List<Companion> list = new ArrayList<>();
        for (CompanionRuntime companion : byId.values())
            if (companion.owner().equals(owner))
                list.add(companion);
        return List.copyOf(list);
    }

    @Override
    public int activeCompanions() {
        return byId.size();
    }

    @Override
    public int maxCompanions() {
        return settings.maxActive();
    }

    // ------------------------------------------------------------------ uso interno

    MdvNpcPlugin plugin() {
        return plugin;
    }

    RoutineNavigator navigator() {
        return navigator;
    }

    RoutineGravity gravity() {
        return gravity;
    }

    long ticks() {
        return ticks;
    }

    public boolean marked(Entity entity) {
        return entity.getPersistentDataContainer().has(marker, PersistentDataType.STRING);
    }

    /** Para el hook de WorldGuard: solo entidades creadas ahora mismo por este servicio. */
    public boolean isSpawning(Entity entity) {
        return spawning.contains(entity.getUniqueId()) && marked(entity);
    }

    public boolean internal(Entity entity) {
        return entity.getUniqueId().equals(internalEntity);
    }

    public CompanionRuntime runtime(Entity entity) {
        return entity == null ? null : byEntity.get(entity.getUniqueId());
    }

    public void removeStale(Entity entity) {
        if (marked(entity) && !byEntity.containsKey(entity.getUniqueId()) && !spawning.contains(entity.getUniqueId()))
            entity.remove();
    }

    boolean teleportInternal(ActiveNpc npc, Location to) {
        World world = to.getWorld();
        if (world == null || world != npc.entity().getWorld() || !loaded(to))
            return false;
        UUID previous = internalEntity;
        internalEntity = npc.entity().getUniqueId();
        try {
            return npc.entity().teleport(to);
        } finally {
            internalEntity = previous;
        }
    }

    static boolean loaded(Location location) {
        World world = location.getWorld();
        return world != null && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    /** Crea la entidad base y el disfraz. La entidad nace oculta para todos menos los visores. */
    ActiveNpc spawnEntity(CompanionRuntime runtime, Location at) {
        World world = at.getWorld();
        if (world == null || !loaded(at))
            throw new CompanionSpawnException(Reason.INVALID_LOCATION, "La ubicación no está en un chunk cargado");
        NpcDefinition definition = runtime.definition();
        UUID[] spawningId = new UUID[1];
        Villager entity = null;
        try {
            try {
                entity = world.spawn(at, Villager.class, CreatureSpawnEvent.SpawnReason.CUSTOM, villager -> {
                    spawningId[0] = villager.getUniqueId();
                    spawning.add(spawningId[0]);
                    villager.setVisibleByDefault(false);
                    villager.getPersistentDataContainer().set(marker, PersistentDataType.STRING, runtime.id().toString());
                    villager.addScoreboardTag(TAG);
                    villager.setAI(false);
                    villager.setAware(false);
                    villager.setAdult();
                    villager.setAgeLock(true);
                    villager.setGravity(true);
                    villager.setInvulnerable(true);
                    villager.setSilent(true);
                    villager.setCollidable(false);
                    villager.setCanPickupItems(false);
                    villager.setRemoveWhenFarAway(false);
                    villager.setPersistent(false);
                });
            } catch (RuntimeException ex) {
                throw new CompanionSpawnException(Reason.SPAWN_BLOCKED, "No se pudo generar la entidad base", ex);
            }
            if (entity == null || !entity.isValid())
                throw new CompanionSpawnException(Reason.SPAWN_BLOCKED,
                        "Otro plugin canceló la aparición (revisa mob-spawning/deny-spawn de WorldGuard)");
            PlayerDisguise disguise;
            try {
                disguise = disguises.apply(entity, definition, runtime.skin());
            } catch (RuntimeException | LinkageError ex) {
                throw new CompanionSpawnException(Reason.DISGUISE_FAILED, "LibsDisguises rechazó el disfraz", ex);
            }
            ActiveNpc npc = new ActiveNpc(definition, at.clone(), entity, disguise);
            for (UUID viewer : runtime.viewers()) {
                Player player = Bukkit.getPlayer(viewer);
                if (player != null)
                    player.showEntity(plugin, entity);
            }
            return npc;
        } catch (CompanionSpawnException ex) {
            if (entity != null)
                entity.remove();
            throw ex;
        } finally {
            if (spawningId[0] != null)
                spawning.remove(spawningId[0]);
        }
    }

    /** Cambia la entidad de un acompañante (otro mundo o la anterior desapareció). */
    boolean respawn(CompanionRuntime runtime, Location at) {
        ActiveNpc old = runtime.npc();
        ActiveNpc fresh;
        try {
            fresh = spawnEntity(runtime, at);
        } catch (CompanionSpawnException ex) {
            plugin.getLogger().fine("No se pudo recrear el acompañante " + runtime.id() + ": " + ex.getMessage());
            return false;
        }
        if (old != null) {
            navigator.cancel(old.definition().id());
            byEntity.remove(old.entity().getUniqueId());
            discard(old);
        }
        runtime.attach(fresh);
        byEntity.put(fresh.entity().getUniqueId(), runtime);
        return true;
    }

    void remove(CompanionRuntime runtime, CompanionRemoveEvent.Reason reason) {
        if (byId.remove(runtime.id()) == null)
            return;
        ActiveNpc npc = runtime.npc();
        if (npc != null) {
            byEntity.remove(npc.entity().getUniqueId());
            if (navigator != null)
                navigator.cancel(npc.definition().id());
            discard(npc);
        }
        runtime.disposed();
        try {
            plugin.getServer().getPluginManager().callEvent(new CompanionRemoveEvent(runtime, reason));
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Error en un listener de CompanionRemoveEvent", ex);
        }
    }

    private static void discard(ActiveNpc npc) {
        try {
            npc.disguise().removeDisguise();
        } catch (RuntimeException | LinkageError ignored) {
            // La entidad se elimina de todos modos.
        }
        npc.entity().remove();
    }

    // ------------------------------------------------------------------ eventos (desde CompanionListener)

    void ownerDisabled(Plugin owner) {
        for (CompanionRuntime companion : new ArrayList<>(byId.values()))
            if (companion.owner().equals(owner))
                remove(companion, CompanionRemoveEvent.Reason.OWNER_DISABLED);
    }

    void playerQuit(Player player) {
        for (CompanionRuntime companion : new ArrayList<>(byId.values())) {
            companion.playerQuit(player);
            if (companion.removeWhenViewersLeave() && companion.isViewerId(player.getUniqueId())
                    && !companion.hasOnlineViewer(player.getUniqueId()))
                remove(companion, CompanionRemoveEvent.Reason.VIEWERS_LEFT);
        }
    }

    void playerJoin(Player player) {
        for (CompanionRuntime companion : byId.values())
            companion.refreshVisibility(player);
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        ticks++;
        if (byId.isEmpty())
            return;
        if (ticks % 20 == 0)
            doors.tick(ticks, false);
        boolean movement = ticks % settings.cadence() == 0;
        if (movement)
            navigator.beginTick(ticks, 4, 2_000_000);
        for (CompanionRuntime companion : new ArrayList<>(byId.values())) {
            if (!byId.containsKey(companion.id()))
                continue;
            try {
                companion.tick(ticks, movement, settings);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Error moviendo el acompañante " + companion.id()
                        + " de " + companion.owner().getName() + "; se elimina", ex);
                remove(companion, CompanionRemoveEvent.Reason.LOST);
            }
        }
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("La API de acompañantes solo se puede usar desde el hilo principal");
    }

    static void requireMain() {
        requireMainThread();
    }
}
