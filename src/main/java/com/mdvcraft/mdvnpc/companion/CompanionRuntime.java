package com.mdvcraft.mdvnpc.companion;

import com.mdvcraft.mdvnpc.api.Companion;
import com.mdvcraft.mdvnpc.api.CompanionGesture;
import com.mdvcraft.mdvnpc.api.CompanionSpec;
import com.mdvcraft.mdvnpc.api.CompanionState;
import com.mdvcraft.mdvnpc.api.MoveResult;
import com.mdvcraft.mdvnpc.api.event.CompanionRemoveEvent;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.routine.RoutineGravity;
import com.mdvcraft.mdvnpc.routine.RoutineNavigator;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.util.DialogueText;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Un acompañante vivo. Solo se usa desde el hilo principal. */
final class CompanionRuntime implements Companion {
    private static final double LOOK_RANGE = 16, NEAREST_VIEWER_RANGE = 8, LOOK_THRESHOLD = 3;
    private static final int SAFE_SCAN = 3;

    private final CompanionService service;
    private final UUID id;
    private final Plugin owner;
    private final String templateId;
    private final NpcDefinition definition;
    private final NpcDefinition.Skin skin;
    private final Set<UUID> viewers = new LinkedHashSet<>();
    private final boolean removeWhenViewersLeave;
    private final RoutineGravity.State gravityState = new RoutineGravity.State();

    private ActiveNpc npc;
    private Location lastLocation;
    private CompanionState state = CompanionState.IDLE;
    private UUID lookTarget;

    // Seguir
    private UUID followTarget;
    private double followDistance;
    private double[] followGoal;
    private Location followDestination;

    // moveTo / lead
    private Location destination;
    private double speed;
    private UUID leadFollower;
    private boolean leadPaused;
    private CompletableFuture<MoveResult> future;

    // Progreso (para detectar atascos)
    private Location progressAt;
    private long progressTick;
    private long nextRespawn;

    // Gestos
    private List<GestureTimeline.Step> gesture = List.of();
    private int gestureIndex;
    private long gestureStart;

    CompanionRuntime(CompanionService service, UUID id, CompanionSpec spec, NpcDefinition definition, NpcDefinition.Skin skin) {
        this.service = service;
        this.id = id;
        this.owner = spec.owner();
        this.templateId = spec.templateId();
        this.definition = definition;
        this.skin = skin;
        this.viewers.addAll(spec.viewers());
        this.removeWhenViewersLeave = spec.removeWhenViewersLeave();
    }

    // ------------------------------------------------------------------ interno

    NpcDefinition definition() { return definition; }
    NpcDefinition.Skin skin() { return skin; }
    ActiveNpc npc() { return npc; }
    boolean removeWhenViewersLeave() { return removeWhenViewersLeave; }
    boolean isViewerId(UUID player) { return viewers.contains(player); }

    void attach(ActiveNpc fresh) {
        npc = fresh;
        lastLocation = fresh.position().clone();
        service.gravity().reset(gravityState);
        followGoal = null;
        resetProgress();
    }

    boolean hasOnlineViewer(UUID except) {
        for (UUID viewer : viewers)
            if (!viewer.equals(except) && Bukkit.getPlayer(viewer) != null)
                return true;
        return false;
    }

    void refreshVisibility(Player player) {
        if (alive() && viewers.contains(player.getUniqueId()))
            player.showEntity(service.plugin(), npc.entity());
    }

    void playerQuit(Player player) {
        UUID quitting = player.getUniqueId();
        if (quitting.equals(followTarget))
            halt(MoveResult.CANCELLED);
        if (quitting.equals(leadFollower))
            halt(MoveResult.CANCELLED);
        if (quitting.equals(lookTarget))
            lookTarget = null;
    }

    void disposed() {
        CompletableFuture<MoveResult> pending = future;
        future = null;
        state = CompanionState.REMOVED;
        followTarget = null;
        leadFollower = null;
        destination = null;
        gesture = List.of();
        if (pending != null)
            pending.complete(MoveResult.REMOVED);
    }

    private boolean alive() {
        return state != CompanionState.REMOVED && npc != null && npc.entity().isValid();
    }

    private String navId() {
        return definition.id();
    }

    // ------------------------------------------------------------------ Companion: identidad

    @Override public UUID id() { return id; }
    @Override public Plugin owner() { return owner; }
    @Override public @Nullable String templateId() { return templateId; }
    @Override public String name() { return definition.name(); }
    @Override public boolean isValid() { return state != CompanionState.REMOVED; }
    @Override public CompanionState state() { return state; }

    @Override
    public Location location() {
        if (alive())
            return npc.position().clone();
        return lastLocation == null ? null : lastLocation.clone();
    }

    @Override
    public UUID entityId() {
        return npc == null ? null : npc.entity().getUniqueId();
    }

    // ------------------------------------------------------------------ visibilidad

    @Override
    public Set<UUID> viewers() {
        return Set.copyOf(viewers);
    }

    @Override
    public boolean isViewer(Player player) {
        return player != null && viewers.contains(player.getUniqueId());
    }

    @Override
    public void show(Player player) {
        CompanionService.requireMain();
        Objects.requireNonNull(player, "player");
        if (!isValid())
            return;
        viewers.add(player.getUniqueId());
        if (alive())
            player.showEntity(service.plugin(), npc.entity());
    }

    @Override
    public void hide(Player player) {
        CompanionService.requireMain();
        Objects.requireNonNull(player, "player");
        viewers.remove(player.getUniqueId());
        if (alive())
            player.hideEntity(service.plugin(), npc.entity());
    }

    // ------------------------------------------------------------------ movimiento

    @Override
    public void follow(Player target) {
        follow(target, service.settings().followDistance());
    }

    @Override
    public void follow(Player target, double distance) {
        CompanionService.requireMain();
        Objects.requireNonNull(target, "target");
        if (!isValid())
            return;
        halt(MoveResult.CANCELLED);
        state = CompanionState.FOLLOWING;
        followTarget = target.getUniqueId();
        followDistance = CompanionSettings.clamp(distance, 1.5, 8);
        followGoal = null;
        followDestination = null;
        resetProgress();
    }

    @Override
    public CompletableFuture<MoveResult> moveTo(Location destination) {
        return moveTo(destination, service.settings().moveSpeed());
    }

    @Override
    public CompletableFuture<MoveResult> moveTo(Location destination, double blocksPerSecond) {
        return startMove(destination, blocksPerSecond, null);
    }

    @Override
    public CompletableFuture<MoveResult> lead(Location destination, Player follower) {
        Objects.requireNonNull(follower, "follower");
        return startMove(destination, service.settings().leadSpeed(), follower.getUniqueId());
    }

    private CompletableFuture<MoveResult> startMove(Location target, double blocksPerSecond, UUID follower) {
        CompanionService.requireMain();
        Objects.requireNonNull(target, "destination");
        if (target.getWorld() == null)
            throw new IllegalArgumentException("El destino no tiene mundo");
        if (!isValid())
            return CompletableFuture.completedFuture(MoveResult.REMOVED);
        halt(MoveResult.CANCELLED);
        CompletableFuture<MoveResult> created = new CompletableFuture<>();
        future = created;
        destination = target.clone();
        speed = CompanionSettings.clamp(blocksPerSecond, .5, 8);
        leadFollower = follower;
        leadPaused = false;
        state = follower == null ? CompanionState.MOVING : CompanionState.LEADING;
        resetProgress();
        return created;
    }

    @Override
    public void stop() {
        CompanionService.requireMain();
        if (isValid())
            halt(MoveResult.CANCELLED);
    }

    @Override
    public void teleport(Location location) {
        CompanionService.requireMain();
        Objects.requireNonNull(location, "location");
        if (!isValid())
            return;
        if (!CompanionService.loaded(location))
            throw new IllegalArgumentException("La ubicación no está en un chunk cargado");
        halt(MoveResult.CANCELLED);
        relocate(location);
    }

    /** Cancela cualquier orden de movimiento y deja al acompañante quieto. */
    private void halt(MoveResult result) {
        if (npc != null)
            service.navigator().cancel(navId());
        CompletableFuture<MoveResult> pending = future;
        future = null;
        destination = null;
        leadFollower = null;
        leadPaused = false;
        followTarget = null;
        followGoal = null;
        followDestination = null;
        if (state != CompanionState.REMOVED)
            state = CompanionState.IDLE;
        if (pending != null)
            pending.complete(result);
    }

    /** Termina un moveTo/lead (el callback puede dar otra orden: no se toca nada después). */
    private void finish(MoveResult result) {
        halt(result);
    }

    private boolean relocate(Location to) {
        if (npc == null || npc.entity().getWorld() != to.getWorld() || !npc.entity().isValid()) {
            if (service.ticks() < nextRespawn)
                return false;
            if (service.respawn(this, to))
                return true;
            nextRespawn = service.ticks() + 40;
            return false;
        }
        service.navigator().cancel(navId());
        boolean moved = service.teleportInternal(npc, to);
        if (moved) {
            lastLocation = npc.position().clone();
            service.gravity().reset(gravityState);
            followGoal = null;
            resetProgress();
        }
        return moved;
    }

    // ------------------------------------------------------------------ comportamiento

    @Override
    public void lookAt(@Nullable Player player) {
        CompanionService.requireMain();
        lookTarget = player == null ? null : player.getUniqueId();
    }

    @Override
    public void gesture(CompanionGesture value) {
        CompanionService.requireMain();
        Objects.requireNonNull(value, "gesture");
        if (!isValid())
            return;
        setSneaking(false);
        gesture = GestureTimeline.steps(value);
        gestureIndex = 0;
        gestureStart = service.ticks();
        runGesture(service.ticks());
    }

    @Override
    public void say(String message) {
        CompanionService.requireMain();
        if (!isValid() || message == null || message.isBlank())
            return;
        for (UUID viewer : viewers) {
            Player player = Bukkit.getPlayer(viewer);
            if (player != null)
                player.sendMessage(render(message, player));
        }
    }

    @Override
    public void say(Player viewer, String message) {
        CompanionService.requireMain();
        if (!isValid() || viewer == null || message == null || message.isBlank())
            return;
        viewer.sendMessage(render(message, viewer));
    }

    private String render(String message, Player player) {
        return DialogueText.render("{prefix} " + message, player, definition);
    }

    @Override
    public void remove() {
        CompanionService.requireMain();
        service.remove(this, CompanionRemoveEvent.Reason.REMOVED);
    }

    // ------------------------------------------------------------------ tick

    void tick(long ticks, boolean movement, CompanionSettings settings) {
        if (state == CompanionState.REMOVED)
            return;
        if (!gesture.isEmpty())
            runGesture(ticks);
        if (!movement)
            return;
        if (npc == null || !npc.entity().isValid()) {
            recover(ticks, settings);
            return;
        }
        lastLocation = npc.position().clone();
        switch (state) {
            case FOLLOWING -> tickFollow(ticks, settings);
            case MOVING, LEADING -> tickMove(ticks, settings);
            default -> idle(ticks, settings);
        }
    }

    private void idle(long ticks, CompanionSettings settings) {
        service.gravity().tick(npc, gravityState, ticks, settings.cadence());
        look();
    }

    private void tickFollow(long ticks, CompanionSettings settings) {
        Player target = Bukkit.getPlayer(followTarget);
        if (target == null || target.getGameMode() == GameMode.SPECTATOR) {
            idle(ticks, settings);
            return;
        }
        Location at = npc.position();
        Location player = target.getLocation();
        if (player.getWorld() != at.getWorld()) {
            relocateBehind(target, settings);
            return;
        }
        double distance = at.distance(player);
        if (distance > settings.teleportDistance()) {
            relocateBehind(target, settings);
            return;
        }
        if (FollowMath.shouldHold(distance, followDistance)) {
            service.navigator().cancel(navId());
            followGoal = null;
            followDestination = null;
            resetProgress();
            idle(ticks, settings);
            return;
        }
        double[] goal = FollowMath.followPoint(player.getX(), player.getZ(), at.getX(), at.getZ(),
                player.getYaw(), followDistance);
        if (followDestination == null || FollowMath.needsRetarget(followGoal, goal)) {
            followGoal = goal;
            followDestination = standAt(player.getWorld(), goal[0], player.getBlockY(), goal[1], target);
        }
        RoutineNavigator.Result result = walk(followDestination, FollowMath.speed(distance, settings), ticks, settings);
        trackProgress(ticks);
        boolean stuck = ticks - progressTick >= settings.stuckTeleportTicks();
        if (result == RoutineNavigator.Result.WAITING && (stuck || service.navigator().exhausted(navId()))
                && distance > followDistance + 2) {
            relocateBehind(target, settings);
        }
    }

    private void tickMove(long ticks, CompanionSettings settings) {
        if (destination.getWorld() != npc.entity().getWorld()) {
            finish(MoveResult.FAILED);
            return;
        }
        if (state == CompanionState.LEADING) {
            Player follower = Bukkit.getPlayer(leadFollower);
            if (follower == null) {
                finish(MoveResult.CANCELLED);
                return;
            }
            Location at = npc.position();
            boolean far = follower.getWorld() != at.getWorld()
                    || follower.getLocation().distance(at) > settings.leadMaxGap();
            if (far) {
                // Esperar al jugador: se para, lo mira y la ruta se recalcula al reanudar.
                if (!leadPaused)
                    service.navigator().cancel(navId());
                leadPaused = true;
                resetProgress();
                service.gravity().tick(npc, gravityState, ticks, settings.cadence());
                lookAt(npc.entity().getEyeLocation(), follower.getEyeLocation());
                return;
            }
            leadPaused = false;
        }
        RoutineNavigator.Result result = walk(destination, speed, ticks, settings);
        if (result == RoutineNavigator.Result.ARRIVED) {
            Player follower = leadFollower == null ? null : Bukkit.getPlayer(leadFollower);
            finish(MoveResult.ARRIVED);
            if (follower != null && lookTarget == null && state == CompanionState.IDLE)
                lookTarget = follower.getUniqueId();
            return;
        }
        trackProgress(ticks);
        if (service.navigator().exhausted(navId()) || ticks - progressTick >= settings.stuckFailTicks())
            finish(MoveResult.FAILED);
    }

    private RoutineNavigator.Result walk(Location to, double blocksPerSecond, long ticks, CompanionSettings settings) {
        service.gravity().reset(gravityState);
        return service.navigator().move(npc, to, blocksPerSecond, ticks, settings.cadence());
    }

    /** La entidad desapareció (chunk descargado, otro plugin...). Se recrea cuando se pueda. */
    private void recover(long ticks, CompanionSettings settings) {
        if (state == CompanionState.FOLLOWING) {
            Player target = Bukkit.getPlayer(followTarget);
            if (target != null) {
                relocateBehind(target, settings);
                return;
            }
        }
        if (state == CompanionState.LEADING) {
            Player follower = Bukkit.getPlayer(leadFollower);
            if (follower == null) {
                finish(MoveResult.CANCELLED);
                return;
            }
        }
        if (lastLocation != null && CompanionService.loaded(lastLocation) && relocate(lastLocation))
            return;
        if ((state == CompanionState.MOVING || state == CompanionState.LEADING)
                && ticks - progressTick >= settings.stuckFailTicks())
            finish(MoveResult.FAILED);
    }

    private void relocateBehind(Player target, CompanionSettings settings) {
        Location player = target.getLocation();
        double[] spot = FollowMath.behind(player.getX(), player.getZ(), player.getYaw(),
                Math.min(followDistance > 0 ? followDistance : settings.followDistance(), 2.5));
        Location to = standAt(player.getWorld(), spot[0], player.getBlockY(), spot[1], target);
        to.setYaw(player.getYaw());
        to.setPitch(0);
        relocate(to);
    }

    /** Punto de pie cerca de (x, y, z); si no hay ninguno, la posición del jugador. */
    private static Location standAt(World world, double x, int y, double z, Player fallback) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (world != null && world.isChunkLoaded(bx >> 4, bz >> 4)) {
            OptionalInt found = SafeSpot.nearestY(bx, y, bz, SAFE_SCAN, (ix, iy, iz) -> standable(world, ix, iy, iz));
            if (found.isPresent())
                return new Location(world, bx + .5, found.getAsInt(), bz + .5);
        }
        Location here = fallback.getLocation();
        return new Location(here.getWorld(), here.getX(), here.getY(), here.getZ());
    }

    private static boolean standable(World world, int x, int y, int z) {
        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight())
            return false;
        Block feet = world.getBlockAt(x, y, z);
        Block below = world.getBlockAt(x, y - 1, z);
        return feet.isPassable() && !feet.isLiquid() && world.getBlockAt(x, y + 1, z).isPassable()
                && !below.isPassable() && !below.isLiquid();
    }

    private void trackProgress(long ticks) {
        Location at = npc.position();
        if (progressAt == null || progressAt.getWorld() != at.getWorld() || progressAt.distanceSquared(at) > .25) {
            progressAt = at.clone();
            progressTick = ticks;
        }
    }

    private void resetProgress() {
        progressAt = npc == null ? null : npc.position().clone();
        progressTick = service.ticks();
    }

    // ------------------------------------------------------------------ mirar

    private void look() {
        Player target = lookCandidate();
        if (target != null)
            lookAt(npc.entity().getEyeLocation(), target.getEyeLocation());
    }

    private Player lookCandidate() {
        Location at = npc.position();
        for (UUID candidate : new UUID[] { lookTarget, followTarget, leadFollower }) {
            if (candidate == null)
                continue;
            Player player = Bukkit.getPlayer(candidate);
            if (player != null && player.getWorld() == at.getWorld() && player.getLocation().distance(at) <= LOOK_RANGE)
                return player;
        }
        Player nearest = null;
        double best = NEAREST_VIEWER_RANGE * NEAREST_VIEWER_RANGE;
        for (UUID viewer : viewers) {
            Player player = Bukkit.getPlayer(viewer);
            if (player == null || player.getWorld() != at.getWorld())
                continue;
            double distance = player.getLocation().distanceSquared(at);
            if (distance <= best) {
                best = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    private void lookAt(Location eye, Location target) {
        float[] angles = FollowMath.lookAngles(eye.getX(), eye.getY(), eye.getZ(), target.getX(), target.getY(), target.getZ());
        Location current = npc.entity().getLocation();
        if (FollowMath.angularDifference(current.getYaw(), angles[0]) < LOOK_THRESHOLD
                && Math.abs(current.getPitch() - angles[1]) < LOOK_THRESHOLD)
            return;
        npc.entity().setRotation(angles[0], angles[1]);
    }

    // ------------------------------------------------------------------ gestos

    private void runGesture(long ticks) {
        long elapsed = ticks - gestureStart;
        while (gestureIndex < gesture.size() && gesture.get(gestureIndex).tick() <= elapsed) {
            GestureTimeline.Action action = gesture.get(gestureIndex++).action();
            if (!alive())
                continue;
            switch (action) {
                case SNEAK_ON -> setSneaking(true);
                case SNEAK_OFF -> setSneaking(false);
                case SWING -> npc.entity().swingMainHand();
            }
        }
        if (gestureIndex >= gesture.size()) {
            gesture = List.of();
            setSneaking(false);
        }
    }

    private void setSneaking(boolean value) {
        if (alive() && npc.disguise() != null)
            npc.disguise().getWatcher().setSneaking(value);
    }
}
