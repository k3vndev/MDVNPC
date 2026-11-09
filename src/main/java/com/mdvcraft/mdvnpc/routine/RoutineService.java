package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.work.ShopWorkDefinition;
import com.mdvcraft.mdvnpc.work.BlacksmithController;
import com.mdvcraft.mdvnpc.work.FishermanController;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.runtime.PlayerFilter;
import com.mdvcraft.mdvnpc.trait.Trait;
import com.mdvcraft.mdvnpc.util.Text;
import org.bukkit.*;
import org.bukkit.block.data.type.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class RoutineService {
    private static final double MIN_VISIBILITY_RANGE=96;
    private final MdvNpcPlugin plugin;
    private final RoutineRepository repository;
    private final WorldClockManager clocks;
    private final DoorController doors;
    private final RoutineVisuals visuals;
    private final RoutineLook looks;
    private final RoutineGravity gravity;
    private RoutineNavigator navigator;
    private DanceController dancers;
    private BlacksmithController blacksmiths;
    private long blacksmithRetryTicks;
    private FishermanController fishermen;
    private long fishermanRetryTicks;
    private final RoutineChoices choices=new RoutineChoices();
    private record CachedWindow(World world,long time,RoutineRepository.Plan plan,RoutineSchedule.Window value) {}
    private final Map<String,CachedWindow> windows=new HashMap<>();
    private BukkitTask task;
    private long ticks;
    private int cadence;
    private int passiveCadence,pathStarts;
    private double activationRange;
    private boolean danceEnabled;
    private long danceSeatedTicks;
    private boolean stuckHopEnabled;
    private long stuckHopDelayTicks;
    private double stuckHopHeight,stuckHopDistance;
    private int clockElapsed;
    private UUID internalEntity;
    private boolean mounting;
    private final Map<String,State> states=new HashMap<>();
    private final Map<String,StaticFloorState> staticFloors=new HashMap<>();
    private final Map<RoutineGoal.Point,String> occupied=new HashMap<>();
    private final Set<String> failed=new HashSet<>();
    private record DialogueKey(String npc,int goal,UUID player) {}
    private static final class DialogueState { long due,lastSeen; int nextLine; DialogueState(long due,long now){this.due=due;this.lastSeen=now;} }
    private record UnavailableKey(String npc,UUID player) {}
    private static final class UnavailableState { long due; int nextLine; }
    private final Map<DialogueKey,DialogueState> dialogueStates=new HashMap<>();
    private final Map<UnavailableKey,UnavailableState> unavailableStates=new HashMap<>();
    private static final class StaticFloorState {
        final ActiveNpc npc;
        final RoutineGravity.State gravity=new RoutineGravity.State();
        long nextPresence,nextUpdate;
        boolean observed;
        StaticFloorState(ActiveNpc npc){this.npc=npc;}
    }
    private static final class State {
        ActiveNpc npc; RoutineSchedule.Window window,recoveryWindow; int chain,point; RoutineGoal goal;
        RoutineGoal.Point destination; Location approach; RoutineVisuals.Pose pose;
        boolean working,paused,dancing,returningFromDance,recoveryDone;
        boolean recoveringFloor;
        boolean recoveringHopFloor;
        boolean blacksmithReturning,blacksmithTrade;
        long nextBlacksmithAttempt;
        boolean fishermanReturning,fishermanTrade,fishermanReactionPaused;
        long nextFishermanAttempt;
        Location travelProgress;
        Location stuckAnchor;
        Location recoveryHopStart;
        long nextHopAttempt;
        long nextPick, travelSince, nextPresence, nextDialogue, nextPoseCheck, nextDanceCheck, nextUpdate,nextReturnCheck,returnWaitingSince,nextRecoveryCheck;
        int returnDoorway,returnFailedDoorways;
        final Map<RoutineGoal.Point,Long> blockedDanceSeats=new HashMap<>();
        List<Location> approachOptions=List.of(); int approachIndex;
        int lastMinute=-1; String status="esperando";
        final RoutineLook.State look=new RoutineLook.State();
        final RoutineGravity.State gravity=new RoutineGravity.State();
    }
    public RoutineService(MdvNpcPlugin plugin) throws Exception {
        this(plugin,null);
    }
    RoutineService(MdvNpcPlugin plugin,RoutineVisuals suppliedVisuals) throws Exception {
        this.plugin=plugin; repository=new RoutineRepository(plugin.getDataFolder().toPath());
        clocks=new WorldClockManager(plugin); doors=new DoorController(plugin);
        visuals=suppliedVisuals==null?new RoutineVisuals(plugin,this::teleport):suppliedVisuals;looks=new RoutineLook(plugin);
        gravity=new RoutineGravity(doors,this::teleport);
    }
    public RoutineRepository repository() { return repository; }
    public BlacksmithController blacksmiths() { return blacksmiths; }
    public FishermanController fishermen() { return fishermen; }
    public void start() {
        cadence=Math.max(1,Math.min(4,plugin.settings().messages().getInt("routines.movement-interval-ticks",2)));
        passiveCadence=Math.max(2,Math.min(20,plugin.settings().messages().getInt("routines.passive-update-ticks",8)));
        pathStarts=Math.max(1,Math.min(8,plugin.settings().messages().getInt("routines.path-starts-per-update",2)));
        activationRange=Math.max(16,Math.min(128,plugin.settings().messages().getDouble("routines.activation-range",48)));
        if(!Double.isFinite(activationRange))activationRange=48;
        danceEnabled=plugin.settings().messages().getBoolean("routines.dancing",true);
        danceSeatedTicks=Math.max(1,Math.min(600,plugin.settings().messages().getInt("routines.dance-seated-seconds",30)))*20L;
        navigator=new RoutineNavigator(doors,this::teleport); failed.clear();
        var cfg=plugin.settings().messages();
        navigator.configureHeights(cfg.getDouble("routines.max-climb-height",1.30),cfg.getDouble("routines.max-drop-height",2.30),cfg.getDouble("routines.max-unannounced-drop",1.01));
        boolean nodesEnabled=cfg.getBoolean("routines.nodes.enabled",true);
        navigator.configureNodes(nodesEnabled,cfg.getInt("routines.nodes.link-range",12),
                cfg.getInt("routines.nodes.endpoint-range",16),cfg.getInt("routines.nodes.scan-margin",24),
                cfg.getInt("routines.nodes.max-distance",256),cfg.getInt("routines.nodes.scan-blocks-per-update",8192),
                cfg.getInt("routines.nodes.search-nodes-per-update",256));
        stuckHopEnabled=cfg.getBoolean("routines.stuck-hop.enabled",true);
        stuckHopDelayTicks=20L*Math.max(1,Math.min(60,cfg.getInt("routines.stuck-hop.delay-seconds",3)));
        double hopHeight=cfg.getDouble("routines.stuck-hop.height",.6),hopDistance=cfg.getDouble("routines.stuck-hop.distance",1);
        stuckHopHeight=Double.isFinite(hopHeight)?Math.max(.05,Math.min(4,hopHeight)):.6;
        stuckHopDistance=Double.isFinite(hopDistance)?Math.max(.05,Math.min(4,hopDistance)):1;
        dancers=new DanceController(plugin,navigator,this::teleport);
        blacksmiths=new BlacksmithController(plugin,navigator,this::teleport);
        blacksmithRetryTicks=20L*Math.max(1,Math.min(600,cfg.getInt("blacksmith.retry-seconds",30)));
        fishermen=new FishermanController(plugin,navigator,this::teleport,this::mount);
        fishermanRetryTicks=20L*Math.max(1,Math.min(600,cfg.getInt("fisherman.retry-seconds",30)));
        clocks.configure(repository.snapshot().clocks());
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,cadence,cadence);
    }
    public void stop() {
        if(task!=null) {task.cancel();task=null;}
        for(String id:List.copyOf(states.keySet())) remove(id);
        if(navigator!=null) navigator.clear(); doors.close();
        if(dancers!=null)dancers.clear();if(blacksmiths!=null)blacksmiths.clear();if(fishermen!=null)fishermen.clear();choices.clear();windows.clear();
        dialogueStates.clear(); unavailableStates.clear();staticFloors.clear();
    }
    public void close() { stop(); clocks.close(); }
    public void invalidateNodes(World world,int blockX,int blockZ) {
        if(navigator!=null)navigator.invalidateNodes(world,blockX,blockZ);
        if(plugin.companions()!=null)plugin.companions().invalidateNodes(world,blockX,blockZ);
    }
    public void worldLoaded() { clocks.sync(); }
    public boolean enabled(String id) { var plan=repository.snapshot().plans().get(id); return plan!=null && plan.enabled() && !plan.goals().isEmpty(); }
    public List<RoutineGoal.Point> points(String id) {
        var plan=repository.snapshot().plans().get(id);
        return plan==null || !plan.enabled()?List.of():plan.goals().stream()
                .flatMap(g->java.util.stream.IntStream.range(0,g.choiceCount()).mapToObj(g::choice))
                .flatMap(g->g.points().stream()).distinct().toList();
    }
    public boolean internal(Entity e) { return e.getUniqueId().equals(internalEntity); }
    public boolean mounting(Entity e) { return mounting && internal(e); }
    public boolean isBoat(Entity e) { return e!=null && fishermen!=null && fishermen.ownsBoat(e); }
    /** Only the controller's own passenger can follow a fishing boat's movement. */
    public boolean inFishingBoat(Entity e) {
        if(e==null || fishermen==null)return false;
        Entity boat=e.getVehicle();
        if(boat==null || !fishermen.ownsBoat(boat))return false;
        ActiveNpc npc=fishermen.mountedNpc(boat);
        return npc!=null && npc.entity()==e;
    }
    public boolean canMountFishingBoat(Entity boat,Entity passenger) {
        if(!isBoat(boat) || passenger==null || !mounting(passenger))return false;
        ActiveNpc owner=fishermen.mountedNpc(boat);
        return owner!=null && owner.entity()==passenger;
    }
    /** The existing seat guard also protects controlled boat boarding and disembarking. */
    public boolean mount(ActiveNpc npc,Entity vehicle) {
        UUID previous=internalEntity;boolean priorMounting=mounting;
        internalEntity=npc.entity().getUniqueId();mounting=true;
        try{return vehicle==null?npc.entity().leaveVehicle():vehicle.addPassenger(npc.entity());}
        finally{internalEntity=previous;mounting=priorMounting;}
    }
    public boolean isSeat(Entity e) { return visuals.isSeat(e); }
    public boolean liveSeat(Entity e) { return states.values().stream().anyMatch(s -> s.pose!=null && s.pose.seat==e); }
    public boolean claimed(RoutineGoal.Point point) { return occupied.containsKey(key(point)); }
    private static RoutineGoal.Point key(RoutineGoal.Point p) { return new RoutineGoal.Point(p.world(),p.x(),p.y(),p.z(),0); }
    public boolean teleport(ActiveNpc npc,Location to) {
        World world=to.getWorld();
        if(world==null || world!=npc.entity().getWorld() || !world.isChunkLoaded(to.getBlockX()>>4,to.getBlockZ()>>4)) return false;
        UUID previous=internalEntity; internalEntity=npc.entity().getUniqueId();
        try { return npc.entity().teleport(to); } finally { internalEntity=previous; }
    }
    private RoutineSchedule.Window window(String id,World world) {
        var plan=repository.snapshot().plans().get(id);
        if(plan==null || !plan.enabled() || plan.goals().isEmpty() || !plan.goals().getFirst().points().getFirst().world().equals(world.getUID())) return null;
        long time=world.getFullTime(),stamp=Math.floorDiv(time+6000,24000)*1440+RoutineSchedule.minute(time);
        CachedWindow cached=windows.get(id);
        if(cached!=null && cached.world()==world && cached.time()==stamp && cached.plan()==plan)return cached.value();
        var value=RoutineSchedule.window(plan.goals(),time);
        windows.put(id,new CachedWindow(world,stamp,plan,value));return value;
    }
    /** Recover by current world time when a destination chunk loads, with no forced chunk tickets. */
    public Location spawnLocation(NpcDefinition definition,World world) {
        var p=definition.position();
        if(!enabled(definition.id())) return new Location(world,p.x(),p.y(),p.z(),p.yaw(),p.pitch());
        var w=window(definition.id(),world);
        if(w==null) return new Location(world,p.x(),p.y(),p.z(),p.yaw(),p.pitch());
        var goal=choices.select(definition.id(),w,w.chain().getFirst(),ThreadLocalRandom.current());
        var terrain=new RoutineTerrain(world,doors);
        for(var point:goal.points()) {
            var node=terrain.approach(point,goal.type()==RoutineGoal.Type.SIT || goal.type()==RoutineGoal.Type.SLEEP);
            if(node!=null) { Location location=terrain.location(node); location.setYaw(point.yaw()); return location; }
        }
        return null;
    }
    public boolean canInteract(ActiveNpc npc) {
        if(!enabled(npc.definition().id())) return true;
        State s=states.get(npc.definition().id());
        if(s==null || !s.working || s.paused || s.npc!=npc || s.approach==null) return false;
        var w=window(npc.definition().id(),npc.entity().getWorld());
        return same(s.window,w) && (npc.entity().getLocation().distanceSquared(s.approach)<.36
                || isBlacksmithWork(s) && (s.blacksmithTrade || blacksmiths!=null && blacksmiths.active(npc.definition().id()))
                || isFishermanWork(s) && (s.fishermanTrade || fishermen!=null && fishermen.active(npc.definition().id())));
    }
    /** Commands and shops may be enabled for a goal without granting WORK, music or idle look ownership. */
    public boolean canUseJob(ActiveNpc npc) {
        if(npc==null || !npc.entity().isValid() || failed.contains(npc.definition().id()))return false;
        String id=npc.definition().id();State s=states.get(id);
        // Static NPCs retain their original behaviour when their routine is absent/disabled.
        if(!enabled(id))return s==null || s.npc==npc;
        if(s==null || s.npc!=npc || s.paused)return false;
        var current=window(id,npc.entity().getWorld());
        if(!same(s.window,current))return false;
        if(canInteract(npc))return true;
        if(s.goal==null || current==null || s.chain<0 || s.chain>=current.chain().size())return false;
        RoutineGoal scheduled=current.chain().get(s.chain);
        // The live parent flag also revokes cached alternatives immediately after an edit.
        return scheduled.order()==s.goal.order() && scheduled.workInteraction() && s.goal.workInteraction();
    }
    public boolean canLook(ActiveNpc npc) { return (blacksmiths==null || !blacksmiths.active(npc.definition().id())) && (fishermen==null || !fishermen.active(npc.definition().id())) && (plugin.reactions()==null || !plugin.reactions().busy(npc.definition().id())) && (plugin.traits()==null || !plugin.traits().busy(npc.definition().id())) && (!enabled(npc.definition().id()) || canInteract(npc)); }
    public boolean canReceiveBeer(ActiveNpc npc) {
        if(plugin.reactions()!=null && plugin.reactions().busy(npc.definition().id()))return false;
        if(!enabled(npc.definition().id()))return true;
        State s=states.get(npc.definition().id());
        if(s!=null && s.pose!=null && (s.pose.sleeping || !validFurniture(s.goal,s.destination,npc.entity().getWorld())
                || s.pose.seat==null || !s.pose.seat.isValid() || npc.entity().getVehicle()!=s.pose.seat))return false;
        return s!=null && s.npc==npc && !s.paused && s.goal!=null && s.goal.type()!=RoutineGoal.Type.SLEEP
                && same(s.window,window(npc.definition().id(),npc.entity().getWorld())) && !failed.contains(npc.definition().id());
    }
    public void prepareReaction(ActiveNpc npc) {
        State s=states.get(npc.definition().id());if(s==null)return;
        clearStuckHop(s);
        interruptBlacksmith(s);
        if(!suspendFishermanReaction(s))interruptFisherman(s);
        endDance(s);
        looks.clear(npc,s.look);navigator.cancel(npc.definition().id());
        if(s.pose!=null && s.pose.sleeping)release(s,true);
        else if(s.pose!=null)visuals.suspend(s.pose,ticks);
        s.nextPick=0;s.nextPresence=0;s.travelSince=ticks;
    }
    public RoutineVisuals.Pose beginDrink(ActiveNpc npc,org.bukkit.inventory.ItemStack beer,long tick) {
        State s=states.get(npc.definition().id());
        if(s!=null){clearStuckHop(s);interruptBlacksmith(s);interruptFisherman(s);endDance(s);looks.clear(npc,s.look);navigator.cancel(npc.definition().id());}
        return visuals.beginDrink(npc,s==null?null:s.pose,beer,tick);
    }
    public boolean drinkTick(RoutineVisuals.Pose pose,long tick){return visuals.drinkTick(pose,tick);}
    public void cancelDrink(RoutineVisuals.Pose pose,long tick){visuals.suspend(pose,tick);}
    private void say(ActiveNpc npc,Player player,String line) {
        if(plugin.sounds()!=null)plugin.sounds().say(npc,player,line);
        else player.sendMessage(com.mdvcraft.mdvnpc.util.DialogueText.render(line,player,npc.definition()));
    }
    public RoutineGoal activeGoal(ActiveNpc npc) {
        State s=states.get(npc.definition().id());
        return s!=null && s.npc==npc && !s.paused ? s.goal : null;
    }
    public void unavailable(ActiveNpc npc,Player player) {
        if(!enabled(npc.definition().id()) || canUseJob(npc)) return;
        var cfg=npc.definition().interaction().unavailable(); if(cfg.lines().isEmpty()) return;
        long now=System.nanoTime(); var key=new UnavailableKey(npc.definition().id(),player.getUniqueId());
        var state=unavailableStates.computeIfAbsent(key,k->new UnavailableState()); if(now<state.due) return;
        int index=cfg.random()?ThreadLocalRandom.current().nextInt(cfg.lines().size()):state.nextLine;
        state.nextLine=(index+1)%cfg.lines().size();
        state.due=now+(long)(cfg.cooldownSeconds()*1_000_000_000L);
        String line=cfg.lines().get(index);
        say(npc,player,line);
    }
    public void forget(UUID player) {
        dialogueStates.keySet().removeIf(k->k.player().equals(player));
        unavailableStates.keySet().removeIf(k->k.player().equals(player));
    }
    private void clearGoalDialogue(String npc) {
        dialogueStates.keySet().removeIf(k->k.npc().equals(npc));
    }
    private static boolean same(RoutineSchedule.Window a,RoutineSchedule.Window b) {
        return a==b || a!=null && b!=null && a.occurrence()==b.occurrence() && a.timedOrder()==b.timedOrder();
    }
    private void release(State s,boolean reposition) {
        stopBlacksmith(s);
        stopFisherman(s);
        endDance(s);
        if(plugin.traits()!=null)plugin.traits().cancel(s.npc.definition().id());
        looks.clear(s.npc,s.look);
        if(s.working) plugin.shops().invalidateNpc(s.npc.definition().id());
        s.working=false;
        if(plugin.music()!=null) plugin.music().remove(s.npc.definition().id());
        if(s.pose!=null) { visuals.leave(s.pose,reposition);s.pose=null; }
        if(s.destination!=null) occupied.remove(key(s.destination),s.npc.definition().id());
        s.destination=null;s.approach=null;s.approachOptions=List.of();s.approachIndex=0; navigator.cancel(s.npc.definition().id());
        s.returningFromDance=false;s.nextReturnCheck=0;s.returnWaitingSince=0;s.returnDoorway=0;s.returnFailedDoorways=0;
        s.recoveringFloor=false;s.recoveringHopFloor=false;s.travelProgress=null;gravity.reset(s.gravity);
    }
    public void remove(String id) {
        staticFloors.remove(id);
        if(blacksmiths!=null)blacksmiths.stop(id);
        if(fishermen!=null)fishermen.stop(id);
        State s=states.remove(id); if(s!=null) release(s,false);
        clearGoalDialogue(id);
        unavailableStates.keySet().removeIf(k->k.npc().equals(id));
    }
    private void tick() {
        ticks+=cadence; clockElapsed+=cadence;
        if(clockElapsed>=10) {clocks.tick(clockElapsed);clockElapsed=0;}
        var manager=plugin.manager(); if(manager==null) return;
        navigator.beginTick(ticks,pathStarts,2_000_000);
        for(ActiveNpc npc:manager.activeNpcs()) {
            String id=npc.definition().id();
            if(!npc.entity().isValid()){staticFloors.remove(id);if(blacksmiths!=null)blacksmiths.stop(id);if(fishermen!=null)fishermen.stop(id);continue;}
            if(failed.contains(id))continue;
            if(!enabled(id)) {
                State held=states.get(id);if(held!=null){clearStuckHop(held);stopBlacksmith(held);stopFisherman(held);}
                try{updateStaticFloor(npc);}
                catch(RuntimeException ex) {
                    failed.add(id);staticFloors.remove(id);
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,"Recuperación de suelo pausada por error: "+id+". Corrige y usa /mdvnpc reload",ex);
                }
                continue;
            }
            staticFloors.remove(id);
            State previous=states.get(id);
            int minute=RoutineSchedule.minute(npc.entity().getWorld().getFullTime());
            if(previous!=null && ticks<previous.nextUpdate && ticks<previous.nextPresence && previous.lastMinute==minute)continue;
            try {
                update(npc);
            }
            catch(RuntimeException ex) {
                failed.add(id); try { remove(id); } catch(RuntimeException cleanup) { ex.addSuppressed(cleanup); }
                plugin.getLogger().log(java.util.logging.Level.SEVERE,"Rutina pausada por error: "+id+". Corrige y usa /mdvnpc reload",ex);
            }
        }
        if(plugin.reactions()!=null)plugin.reactions().tick(ticks);
        if(plugin.sounds()!=null)plugin.sounds().tick(ticks);
        if(plugin.traits()!=null)plugin.traits().tick(ticks);
        if(ticks%20<cadence) {
            if(plugin.prefixEditor()!=null)plugin.prefixEditor().prune();
            doors.tick(ticks,false); if(plugin.routineCommands()!=null)plugin.routineCommands().prune();
            long now=System.nanoTime(); dialogueStates.values().removeIf(v->now>=v.due && now-v.lastSeen>60_000_000_000L);
        }
    }
    /** Static NPCs share the floor helper without creating a routine, pose, or job state. */
    private void updateStaticFloor(ActiveNpc npc) {
        String id=npc.definition().id();StaticFloorState s=staticFloors.get(id);
        if(s==null || s.npc!=npc){s=new StaticFloorState(npc);staticFloors.put(id,s);}
        if(ticks<s.nextUpdate && ticks<s.nextPresence)return;
        State held=states.get(id);
        if(npc.entity().isInsideVehicle() || held!=null && held.npc==npc && (held.pose!=null || held.dancing)
                || plugin.reactions()!=null && plugin.reactions().busy(id)
                || plugin.traits()!=null && plugin.traits().busy(id)) {
            gravity.reset(s.gravity);s.nextUpdate=ticks+20;s.nextPresence=Math.max(s.nextPresence,ticks+20);return;
        }
        if(ticks>=s.nextPresence) {
            s.nextPresence=ticks+20;
            s.observed=!npc.position().getWorld().getNearbyPlayers(npc.position(),activationRange,p->!p.isDead()).isEmpty();
        }
        if(!s.observed){gravity.reset(s.gravity);s.nextUpdate=ticks+20;return;}
        RoutineGravity.Result result=gravity.tick(npc,s.gravity,ticks,cadence);
        s.nextUpdate=result.moving()?ticks+cadence:Math.min(ticks+20,s.gravity.nextCheck);
    }
    private void update(ActiveNpc npc) {
        String id=npc.definition().id(); State s=states.computeIfAbsent(id,k -> {State n=new State(); n.npc=npc;return n;});
        s.lastMinute=RoutineSchedule.minute(npc.entity().getWorld().getFullTime());
        boolean workAnimation=blacksmiths!=null && blacksmiths.active(id) || fishermen!=null && fishermen.active(id);
        s.nextUpdate=ticks+(s.paused?20:!s.dancing && !workAnimation && (s.pose!=null || s.working)?passiveCadence:cadence);
        Location position=npc.entity().getLocation();
        double range=activationRange;
        // Nearby-player queries every second; no per-tick whole-world scans.
        if(ticks>=s.nextPresence) {
            s.nextPresence=ticks+20;
            // Spectators can follow an NPC too. Keep the configured activation range;
            // the wider visibility guard below is queried only for pending recovery.
            boolean absent=position.getWorld().getNearbyPlayers(position,range,p -> !p.isDead()).isEmpty();
            if(absent) {
                clearStuckHop(s);
                if(!s.paused) {
                    if(plugin.traits()!=null)plugin.traits().cancel(id);
                    // Keep furniture poses when the same schedule remains active. In particular,
                    // never wake and displace a sleeper just because its last observer left.
                    if(s.pose!=null) {looks.clear(npc,s.look);visuals.suspend(s.pose,ticks);navigator.cancel(id);}
                    else release(s,true);
                    clearGoalDialogue(id); s.nextDialogue=0;
                    s.paused=true;s.status="suspendido: sin jugadores cerca";
                }
                var dormantWindow=window(id,position.getWorld());
                if(s.pose!=null && (!same(s.window,dormantWindow) || !validFurniture(s.goal,s.destination,position.getWorld()))) {
                    release(s,true);s.window=null;s.goal=null;s.chain=0;s.point=0;s.nextPick=0;
                }
                if(s.pose!=null)return;
                if(!same(s.recoveryWindow,dormantWindow)) {
                    s.recoveryWindow=dormantWindow;s.recoveryDone=false;s.nextRecoveryCheck=0;
                }
                // Catch up to the current schedule only while BOTH ends are unseen.
                // Cache completed occurrences and spread inaccessible/observed retries
                // over five seconds instead of recalculating destination geometry every tick.
                if(dormantWindow==null || s.recoveryDone || ticks<s.nextRecoveryCheck)return;
                s.nextRecoveryCheck=ticks+100;
                // Respect the server's actual entity trackers as well as the local radius.
                // Servers may configure tracking distances larger than the visibility guard.
                if(!npc.entity().getTrackedBy().isEmpty())return;
                double visibleRange=Math.max(range,MIN_VISIBILITY_RANGE);
                if(!position.getWorld().getNearbyPlayers(position,visibleRange,p -> true).isEmpty())return;
                Location recovery=spawnLocation(npc.definition(),position.getWorld());
                if(recovery==null)return;
                if(recovery.distanceSquared(position)<=4){s.recoveryDone=true;return;}
                if(recovery.getWorld().getNearbyPlayers(recovery,visibleRange,p -> true).isEmpty() && teleport(npc,recovery)) {
                    s.window=null;s.goal=null;s.chain=0;s.point=0;s.nextPick=0;s.recoveryDone=true;
                }
                return;
            }
            s.recoveryWindow=null;s.recoveryDone=false;s.nextRecoveryCheck=0;
            if(s.paused) {
                s.nextPick=0;s.nextDialogue=0;s.nextPoseCheck=0;
                // Restore an ongoing pose when observers return, never yesterday's pose.
                if(s.pose!=null && same(s.window,window(id,position.getWorld()))) {
                    boolean restored=s.pose.sleeping?visuals.restoreSleep(s.pose,true):restoreSeat(s,true);
                    if(!restored)release(s,true);
                    else s.nextPoseCheck=ticks+40;
                }
            }
            s.paused=false;
        }
        if(s.paused) return;
        position=npc.position();
        var w=window(id,position.getWorld());
        if(!same(s.window,w)) { release(s,true); s.window=w; s.chain=0; s.point=0; s.goal=null; s.nextPick=0; }
        if(w==null || s.chain>=w.chain().size()) {
            clearStuckHop(s);
            if(recoverFloor(s))return;
            s.status="fuera de horario / secuencia terminada";return;
        }
        RoutineGoal goal=choices.select(id,w,w.chain().get(s.chain),ThreadLocalRandom.current());
        if(s.goal!=goal) {
            // Cada entrada a un goal comienza su propio intervalo/demora inicial. Sin esto,
            // un intervalo largo de ayer podría impedir que el NPC hable hoy.
            clearGoalDialogue(id);
            clearStuckHop(s);
            stopBlacksmith(s);
            stopFisherman(s);
            s.goal=goal; s.point=0; s.nextDialogue=0;s.blockedDanceSeats.clear();
        }
        if(plugin.reactions()!=null && plugin.reactions().busy(id)) {
            clearStuckHop(s);interruptBlacksmith(s);
            if(!suspendFishermanReaction(s))interruptFisherman(s);
            gravity.reset(s.gravity);s.status="enfadado: mirando al jugador";s.travelSince=ticks;return;
        }
        if(plugin.traits()!=null && plugin.traits().busy(id)) {clearStuckHop(s);interruptBlacksmith(s);interruptFisherman(s);gravity.reset(s.gravity);s.status="bebiendo cerveza ofrecida";s.travelSince=ticks;return;}
        if(s.fishermanReactionPaused) {
            s.fishermanReactionPaused=false;
            if(fishermen==null || !fishermen.active(id) || !fishermen.resume(id,ticks))returnFishermanToPost(s);
        }
        if(ticks>=s.nextDialogue) { s.nextDialogue=ticks+20; updateDialogue(npc,goal,System.nanoTime()); }
        if(s.dancing) {
            stopBlacksmith(s);
            stopFisherman(s);
            clearStuckHop(s);
            gravity.reset(s.gravity);
            var dance=dancers.tick(npc,ticks,cadence,goal.speed());
            if(dance!=DanceController.Result.FINISHED) {s.status=dance==DanceController.Result.DANCING?"bailando junto a músicos":"acercándose a los músicos";return;}
            endDance(s);s.nextPick=0;
            position=npc.position();
        }
        if(s.pose!=null) {
            stopBlacksmith(s);
            stopFisherman(s);
            clearStuckHop(s);
            gravity.reset(s.gravity);
            if(!validFurniture(goal,s.destination,position.getWorld())) {
                release(s,true);s.nextPick=ticks+100;return;
            }
            if(s.pose.sleeping && ticks>=s.nextPoseCheck) {
                s.nextPoseCheck=ticks+40;
                if(!visuals.restoreSleep(s.pose,false)) {release(s,true);s.nextPick=ticks+40;return;}
            }
            if(!s.pose.sleeping && (ticks>=s.nextPoseCheck || s.pose.seat==null
                    || !s.pose.seat.isValid() || npc.entity().getVehicle()!=s.pose.seat)) {
                s.nextPoseCheck=ticks+40;
                if(!restoreSeat(s,false)) {release(s,true);s.nextPick=ticks+40;return;}
            }
            if(!s.pose.sleeping && npc.definition().traits().type()==Trait.PARTYGOER
                    && ticks>=s.nextDanceCheck && danceEnabled) {
                s.nextDanceCheck=ticks+40;
                if(dancers.start(npc,s.approach,ticks)) {
                    looks.clear(npc,s.look);visuals.leave(s.pose,true);s.pose=null;s.dancing=true;
                    s.nextUpdate=ticks+cadence;s.status="acercándose a los músicos";return;
                }
            }
            visuals.tick(s.pose,ticks);
            s.status=s.pose.sleeping?"durmiendo":s.pose.reading?"sentado: leyendo":"sentado";
            if(!s.pose.sleeping)looks.tick(npc,s.look,ticks,s.pose.bodyYaw,true,s.pose.reading,s.pose.mealUntil>ticks);
            return;
        }
        if(updateBlacksmith(s,goal))return;
        if(updateFisherman(s,goal))return;
        position=npc.position();
        // Floor recovery may keep returning BLOCKED, or report lift/fall frames without
        // horizontal movement. Its early return must not hide a waiting walker's clock.
        // A started hop owns subsequent frames before gravity; idle workers remain excluded.
        if(!s.working && stuckHop(s))return;
        // Native route replay owns lifted stair frames. Check ordinary walkers only after
        // WAITING below; idle workers and an already falling NPC have no active stair step.
        if(s.working || s.recoveringFloor || s.gravity.falling()) {
            if(recoverFloor(s))return;
            position=npc.position();
        }
        if(s.working) {
            if(!npc.definition().mode().musician() || s.approach!=null && position.getWorld()==s.approach.getWorld()
                    && position.distanceSquared(s.approach)<.36) {clearStuckHop(s);s.status="trabajando";return;}
            // A displaced musician becomes ineligible for audio immediately. Keep the chosen
            // work goal and post, then use the same bounded route to rejoin on arrival.
            s.working=false;if(plugin.music()!=null)plugin.music().remove(id);
            looks.clear(npc,s.look);navigator.cancel(id);
            resetTravelProgress(s,position);s.nextUpdate=ticks+cadence;
        }
        if(s.destination!=null && !s.returningFromDance && ticks-s.travelSince>1200) {
            release(s,true);s.nextPick=ticks+100;s.status="ruta inaccesible; esperando reintento";return;
        }
        if(stuckHop(s))return;
        if(s.returningFromDance && ticks>=s.nextReturnCheck) {
            s.nextReturnCheck=ticks+100;
            if(!validFurniture(goal,s.destination,position.getWorld())) {
                // A removed/unloaded seat cannot hold a returning dancer indefinitely.
                // Release its reservation and look only for another loaded seat in this goal.
                release(s,false);s.nextPick=0;
            } else {
                Location returnApproach=returnApproach(s.destination,position,s.returnDoorway);
                if(returnApproach!=null) {
                    returnApproach.setYaw(s.destination.yaw());s.approach=returnApproach;
                }
            }
        }
        if(s.destination==null) {
            if(ticks<s.nextPick) return;
            s.nextPick=ticks+100;
            s.destination=choose(goal,s,position);
            if(s.destination==null) {s.status="esperando destino disponible";return;}
            var terrain=new RoutineTerrain(position.getWorld(),doors);
            boolean furniture=goal.type()==RoutineGoal.Type.SIT || goal.type()==RoutineGoal.Type.SLEEP;
            Location approachOrigin=position;
            s.approachOptions=terrain.approaches(s.destination,furniture).stream().map(terrain::location)
                    .sorted(java.util.Comparator.comparingDouble(approachOrigin::distanceSquared)).map(location->{
                        Location value=location.clone();value.setYaw(s.destination.yaw());return value;
                    }).toList();
            s.approachIndex=0;
            if(s.approachOptions.isEmpty()) {
                // The selected destination still represents walking intent even when its
                // floor/clearance cannot produce a route doorway from the current world.
                observeStuckMotion(s,position);
                s.destination=null;s.status="destino descargado o sin acceso";return;
            }
            s.approach=s.approachOptions.getFirst();
            resetTravelProgress(s,position);
            if(furniture) occupied.put(key(s.destination),id);
        }
        Location beforeMove=npc.position();
        observeStuckMotion(s,beforeMove);
        // Joining a seat from its checked doorway needs no exact native waypoint. This also
        // recovers a return that ends a few centimetres short without a visible long teleport.
        var result=s.returningFromDance && readyToSit(npc,s.approach)
                ?RoutineNavigator.Result.ARRIVED:navigator.move(npc,s.approach,goal.speed(),ticks,cadence);
        boolean progressed=travelProgress(s,beforeMove,npc.position(),result);
        observeStuckMotion(s,npc.position());
        if(result==RoutineNavigator.Result.WAITING && recoverFloor(s))return;
        if(!s.returningFromDance && result==RoutineNavigator.Result.WAITING && navigator.exhausted(id)
                && (goal.type()==RoutineGoal.Type.SIT || goal.type()==RoutineGoal.Type.SLEEP)) {
            if(++s.approachIndex<s.approachOptions.size()) {
                s.approach=s.approachOptions.get(s.approachIndex);navigator.cancel(id);resetTravelProgress(s,npc.position());
                s.status="acceso sin ruta; probando otro lado";return;
            }
            release(s,true);s.nextPick=ticks+100;s.status="destino sin ningún acceso alcanzable";return;
        }
        if(s.returningFromDance) {
            if(result!=RoutineNavigator.Result.WAITING || navigator.planningNodes(id)) {
                s.returnWaitingSince=0;
                if(progressed)s.returnFailedDoorways=0;
            }
            else if(s.returnWaitingSince==0)s.returnWaitingSince=ticks;
            else if(ticks-s.returnWaitingSince>=100) {
                // Native routes may reject one doorway while another side is reachable.
                // Spread these retries over time; never release the reserved chair mid-return.
                s.returnDoorway++;s.returnFailedDoorways++;s.nextReturnCheck=0;s.returnWaitingSince=0;navigator.cancel(id);
                if(s.returnFailedDoorways>=4) {
                    // Four failed local doorways with no walking progress are a genuine
                    // inaccessible-seat case, not an excuse to reserve it or idle forever.
                    s.blockedDanceSeats.put(key(s.destination),ticks+1200);
                    release(s,false);s.nextPick=0;s.nextUpdate=ticks+cadence;
                    s.status="asiento sin ruta; buscando otro asiento";return;
                }
            }
        }
        if(plugin.sounds()!=null)plugin.sounds().moved(npc,beforeMove,npc.position(),ticks);
        if(result==RoutineNavigator.Result.MOVING) {
            var moved=npc.position().toVector().subtract(beforeMove.toVector());moved.setY(0);
            float bodyYaw=moved.lengthSquared()>.00001?beforeMove.clone().setDirection(moved).getYaw():s.look.initialized?s.look.bodyYaw:beforeMove.getYaw();
            looks.tick(npc,s.look,ticks,bodyYaw,false,false);
        } else if(goal.type()==RoutineGoal.Type.WALK || s.returningFromDance) {
            // Keep the occasional-glance timer across short waypoints; otherwise a route
            // made of short segments would reset it forever and never produce a glance.
            looks.tick(npc,s.look,ticks,s.look.initialized?s.look.bodyYaw:beforeMove.getYaw(),false,false);
        } else looks.clear(npc,s.look);
        s.status=s.returningFromDance
                ?result==RoutineNavigator.Result.WAITING?"volviendo al asiento: esperando ruta":"volviendo al asiento"
                :result==RoutineNavigator.Result.WAITING?"esperando ruta (reintentos limitados)":"caminando a "+goal.type();
        if(result!=RoutineNavigator.Result.ARRIVED) return;
        clearStuckHop(s);
        switch(goal.type()) {
            case WORK -> { s.blacksmithReturning=false;s.fishermanReturning=false;s.working=true; if(plugin.music()!=null)plugin.music().working(npc); npc.anchor().setYaw(s.approach.getYaw()); npc.entity().setRotation(s.approach.getYaw(),0);s.status="trabajando"; }
            case WALK -> {
                s.point++;
                if(goal.target() && s.point>=goal.points().size()) {s.chain++;s.goal=null;s.point=0;}
                else if(s.point>=goal.points().size()) s.point=0;
                s.destination=null;s.approach=null;s.nextPick=ticks+(goal.mode()==RoutineGoal.WalkMode.RANDOM?40:0);
            }
            case SIT,SLEEP -> {
                UUID before=internalEntity; internalEntity=npc.entity().getUniqueId();mounting=true;
                try {s.pose=visuals.enter(npc,goal,s.destination,s.approach,ticks);}
                finally {internalEntity=before;mounting=false;}
                if(s.pose==null) {
                    boolean afterDance=s.returningFromDance;
                    if(afterDance)s.blockedDanceSeats.put(key(s.destination),ticks+1200);
                    release(s,true);s.nextPick=afterDance?0:ticks+100;
                    if(afterDance)s.status="asiento no disponible; buscando otro asiento";
                }
                else {
                    navigator.cancel(id);s.returningFromDance=false;s.nextReturnCheck=0;s.returnWaitingSince=0;s.returnDoorway=0;s.returnFailedDoorways=0;
                    // The seated part of the cycle starts after mounting, never while walking
                    // back from the band. Initial seating follows the same quiet interval.
                    s.nextDanceCheck=ticks+danceSeatedTicks;
                    s.nextPoseCheck=ticks+40;
                    s.status=goal.type()==RoutineGoal.Type.SLEEP?"durmiendo":"sentado";
                }
            }
        }
    }
    private boolean isBlacksmithWork(State s) {
        return s.goal!=null && s.goal.type()==RoutineGoal.Type.WORK
                && s.npc.definition().mode()==NpcDefinition.Mode.SHOP
                && s.npc.definition().shopWork().category()==ShopWorkDefinition.Category.BLACKSMITH;
    }
    private void stopBlacksmith(State s) {
        if(blacksmiths!=null)blacksmiths.stop(s.npc.definition().id());
        s.blacksmithReturning=false;s.blacksmithTrade=false;s.nextBlacksmithAttempt=0;
    }
    /** Reactions and drinks restore equipment before another controller borrows it. */
    private void interruptBlacksmith(State s) {
        if(blacksmiths==null || !blacksmiths.active(s.npc.definition().id()))return;
        blacksmiths.stop(s.npc.definition().id());
        s.blacksmithReturning=true;s.blacksmithTrade=false;
        s.nextBlacksmithAttempt=ticks+blacksmithRetryTicks;
    }
    private void returnBlacksmithToPost(State s) {
        if(blacksmiths!=null)blacksmiths.stop(s.npc.definition().id());
        clearStuckHop(s);looks.clear(s.npc,s.look);
        s.working=false;s.blacksmithReturning=true;s.blacksmithTrade=false;
        s.nextBlacksmithAttempt=ticks+blacksmithRetryTicks;
        navigator.cancel(s.npc.definition().id());
        resetTravelProgress(s,s.npc.position());s.nextUpdate=ticks+cadence;
        s.status="herrero: volviendo al puesto de trabajo";
    }
    /** The workshop borrows movement only after the normal WORK arrival. */
    private boolean updateBlacksmith(State s,RoutineGoal goal) {
        if(blacksmiths==null || !isBlacksmithWork(s))return false;
        ActiveNpc npc=s.npc;String id=npc.definition().id();
        boolean active=blacksmiths.active(id);
        if(!s.working || s.approach==null)return false;
        if(plugin.shops()!=null && plugin.shops().hasOpenSession(id)) {
            if(active){blacksmiths.stop(id);clearStuckHop(s);looks.clear(npc,s.look);}
            s.blacksmithTrade=true;s.nextUpdate=ticks+passiveCadence;
            s.status="herrero: atendiendo compra";return true;
        }
        if(s.blacksmithTrade) {
            returnBlacksmithToPost(s);return false;
        }
        if(!active) {
            Location at=npc.position();
            if(s.blacksmithReturning || at.getWorld()!=s.approach.getWorld() || at.distanceSquared(s.approach)>=.36) {
                returnBlacksmithToPost(s);return false;
            }
            if(ticks<s.nextBlacksmithAttempt)return false;
            looks.clear(npc,s.look);clearStuckHop(s);
        } else if(blacksmiths.traveling(id)) {
            // A traveling smith still owns WORK/shop access, but needs the same forced
            // recovery hops and gravity as any other walker.
            if(blacksmiths.travelExpired(id,ticks)) {returnBlacksmithToPost(s);return false;}
            if(stuckHop(s))return true;
            if(recoverFloor(s))return true;
        } else {
            clearStuckHop(s);gravity.reset(s.gravity);
        }
        Location before=npc.position();
        BlacksmithController.Result result=blacksmiths.tick(npc,s.approach,goal.speed(),ticks,cadence);
        if(plugin.sounds()!=null)plugin.sounds().moved(npc,before,npc.position(),ticks);
        if(result==BlacksmithController.Result.FALLBACK) {
            returnBlacksmithToPost(s);return false;
        }
        if(blacksmiths.traveling(id))observeStuckMotion(s,npc.position());
        s.nextUpdate=ticks+cadence;s.status=blacksmiths.status(id);return true;
    }
    private boolean isFishermanWork(State s) {
        return s.goal!=null && s.goal.type()==RoutineGoal.Type.WORK
                && s.npc.definition().mode()==NpcDefinition.Mode.SHOP
                && s.npc.definition().shopWork().category()==ShopWorkDefinition.Category.FISHERMAN;
    }
    private void stopFisherman(State s) {
        if(fishermen!=null)fishermen.stop(s.npc.definition().id());
        s.fishermanReturning=false;s.fishermanTrade=false;s.fishermanReactionPaused=false;s.nextFishermanAttempt=0;
    }
    /** Hits pause a mounted fisher without evicting it or taking rotation from the reaction. */
    private boolean suspendFishermanReaction(State s) {
        String id=s.npc.definition().id();
        if(fishermen==null || !fishermen.active(id) || !fishermen.boating(id) || !fishermen.suspend(id,ticks))return false;
        s.fishermanReactionPaused=true;return true;
    }
    private void interruptFisherman(State s) {
        s.fishermanReactionPaused=false;
        if(fishermen==null || !fishermen.active(s.npc.definition().id()))return;
        fishermen.stop(s.npc.definition().id());
        s.fishermanReturning=true;s.fishermanTrade=false;
        s.nextFishermanAttempt=ticks+fishermanRetryTicks;
    }
    private void returnFishermanToPost(State s) {
        if(fishermen!=null)fishermen.stop(s.npc.definition().id());
        clearStuckHop(s);looks.clear(s.npc,s.look);
        s.working=false;s.fishermanReturning=true;s.fishermanTrade=false;s.fishermanReactionPaused=false;
        s.nextFishermanAttempt=ticks+fishermanRetryTicks;
        navigator.cancel(s.npc.definition().id());
        resetTravelProgress(s,s.npc.position());s.nextUpdate=ticks+cadence;
        s.status="pescador: volviendo al puesto de trabajo";
    }
    /** Shore travel shares the walker recovery; controlled sailing owns the passenger height. */
    private boolean updateFisherman(State s,RoutineGoal goal) {
        if(fishermen==null || !isFishermanWork(s))return false;
        ActiveNpc npc=s.npc;String id=npc.definition().id();
        boolean active=fishermen.active(id);
        if(!s.working || s.approach==null)return false;
        if(plugin.shops()!=null && plugin.shops().hasOpenSession(id)) {
            // Keep the passenger where the buyer opened the merchant. Cleanup of a boat
            // returns its NPC to the dock, which would invalidate that buyer's distance.
            if(active && !fishermen.pause(id)) {
                returnFishermanToPost(s);plugin.shops().invalidateNpc(id);return false;
            }
            clearStuckHop(s);looks.clear(npc,s.look);
            s.fishermanTrade=true;s.nextUpdate=ticks+passiveCadence;
            s.status="pescador: atendiendo compra";return true;
        }
        if(s.fishermanTrade){returnFishermanToPost(s);return false;}
        if(!active) {
            Location at=npc.position();
            if(s.fishermanReturning || at.getWorld()!=s.approach.getWorld() || at.distanceSquared(s.approach)>=.36) {
                returnFishermanToPost(s);return false;
            }
            if(ticks<s.nextFishermanAttempt)return false;
            looks.clear(npc,s.look);clearStuckHop(s);
        } else {
            // A blocked hop/floor must not conceal an expired shore or boat route.
            if(fishermen.travelExpired(id,ticks)){returnFishermanToPost(s);return false;}
            if(fishermen.walking(id)) {
                if(stuckHop(s))return true;
                if(recoverFloor(s))return true;
            } else {
                clearStuckHop(s);gravity.reset(s.gravity);
            }
        }
        Location before=npc.position();
        FishermanController.Result result=fishermen.tick(npc,s.approach,goal.speed(),ticks,cadence);
        if(plugin.sounds()!=null && !fishermen.boating(id))plugin.sounds().moved(npc,before,npc.position(),ticks);
        if(result==FishermanController.Result.FALLBACK){returnFishermanToPost(s);return false;}
        if(fishermen.walking(id))observeStuckMotion(s,npc.position());
        s.nextUpdate=ticks+cadence;s.status=fishermen.status(id);return true;
    }
    private void resetTravelProgress(State s,Location at) {
        s.travelSince=ticks;s.travelProgress=at.clone();
    }
    private boolean travelProgress(State s,Location before,Location after,RoutineNavigator.Result result) {
        if(s.travelProgress==null || s.travelProgress.getWorld()!=after.getWorld())s.travelProgress=before.clone();
        // Aggregate small legal steps. No-op teleports and bounded sub-centimetre jitter
        // cannot refresh the timeout; the navigator also bounds repeating partial routes.
        if(result==RoutineNavigator.Result.WAITING || before.distanceSquared(after)<1e-12
                || s.travelProgress.distanceSquared(after)<.0025)return false;
        resetTravelProgress(s,after);return true;
    }
    private void clearStuckHop(State s) {
        s.stuckAnchor=null;s.recoveryHopStart=null;s.nextHopAttempt=0;
        if(navigator!=null)navigator.cancelHop(s.npc.definition().id());
    }
    /** Native retries and vertical floor corrections cannot disguise a stationary walker. */
    private void observeStuckMotion(State s,Location at) {
        if(!stuckHopEnabled || s.npc.entity().isInsideVehicle())return;
        if(s.stuckAnchor==null || s.stuckAnchor.getWorld()!=at.getWorld()) {
            s.stuckAnchor=at.clone();s.nextHopAttempt=ticks+stuckHopDelayTicks;return;
        }
        double dx=at.getX()-s.stuckAnchor.getX(),dz=at.getZ()-s.stuckAnchor.getZ();
        if(dx*dx+dz*dz>=.0025) {
            s.stuckAnchor=at.clone();s.nextHopAttempt=ticks+stuckHopDelayTicks;
        }
    }
    /** A hop is walking recovery, never arrival at the routine's destination. */
    private boolean stuckHop(State s) {
        ActiveNpc npc=s.npc;String id=npc.definition().id();
        if(!stuckHopEnabled || npc.entity().isInsideVehicle()) {clearStuckHop(s);return false;}
        boolean hopping=navigator.activeHop(id);
        if(!hopping && navigator.planningNodes(id)) {
            s.nextHopAttempt=ticks+stuckHopDelayTicks;return false;
        }
        if(s.stuckAnchor==null && s.destination==null && !hopping)return false;
        if(hopping) {
            Location before=npc.position();
            if(s.recoveryHopStart==null)s.recoveryHopStart=before.clone();
            navigator.advanceHop(npc,ticks);
            Location after=npc.position();
            if(s.stuckAnchor!=null)s.stuckAnchor.setY(after.getY());
            if(plugin.sounds()!=null)plugin.sounds().moved(npc,before,after,ticks);
            if(!navigator.activeHop(id)) {
                // Count the final displacement, not an outward frame followed by a return.
                // Vertical animation and blocked attempts cannot prolong a stalled route.
                Location start=s.recoveryHopStart;
                if(start.getWorld()==after.getWorld()) {
                    double dx=after.getX()-start.getX(),dz=after.getZ()-start.getZ();
                    if(dx*dx+dz*dz>=.0025) {
                        s.stuckAnchor=after.clone();resetTravelProgress(s,after);
                    }
                }
                s.recoveryHopStart=null;s.nextHopAttempt=ticks+stuckHopDelayTicks;
                // A forced hop can finish in mid-air: hand the following frame to gravity.
                s.recoveringFloor=true;s.recoveringHopFloor=true;
            }
            s.nextUpdate=ticks+cadence;s.status="salto corto para destrabarse";return true;
        }
        s.recoveryHopStart=null;
        observeStuckMotion(s,npc.position());
        if(ticks<s.nextHopAttempt)return false;
        s.nextHopAttempt=ticks+stuckHopDelayTicks;
        if(!navigator.startRecoveryHop(npc,stuckHopHeight,stuckHopDistance,ticks))return false;
        s.recoveryHopStart=npc.position();gravity.reset(s.gravity);
        s.nextUpdate=ticks+cadence;s.status="salto corto para destrabarse";return true;
    }
    /** Returns true while floor recovery owns this update; reservations remain unchanged. */
    private boolean recoverFloor(State s) {
        ActiveNpc npc=s.npc;String id=npc.definition().id();
        boolean stationTravel=blacksmiths!=null && blacksmiths.active(id) || fishermen!=null && fishermen.active(id);
        if(s.pose!=null || s.dancing || npc.entity().isInsideVehicle()
                || plugin.reactions()!=null && plugin.reactions().busy(id)
                || plugin.traits()!=null && plugin.traits().busy(id)
                || navigator.controlsVerticalStep(id)) {
            gravity.reset(s.gravity);return false;
        }
        RoutineGravity.Result result=gravity.tick(npc,s.gravity,ticks,cadence,s.goal==null?2.4:s.goal.speed());
        if(result!=RoutineGravity.Result.STABLE) {
            if(!s.recoveringFloor) {
                s.recoveringFloor=true;navigator.cancel(id);looks.clear(npc,s.look);
                if(s.working && !stationTravel) {
                    s.working=false;plugin.shops().invalidateNpc(id);
                    if(plugin.music()!=null)plugin.music().remove(id);
                }
                resetTravelProgress(s,npc.position());
            }
            s.nextUpdate=ticks+(result.moving()?cadence:20);
            if(stuckHopEnabled && s.stuckAnchor!=null && s.nextHopAttempt>ticks)
                s.nextUpdate=Math.min(s.nextUpdate,s.nextHopAttempt);
            s.status=result==RoutineGravity.Result.FALLING?"cayendo al perder el suelo"
                    :result==RoutineGravity.Result.LIFTING?"recuperando apoyo":"esperando suelo seguro";
            return true;
        }
        if(!s.recoveringFloor)return false;
        s.recoveringFloor=false;Location position=npc.position();
        if(!s.recoveringHopFloor)resetTravelProgress(s,position);
        s.recoveringHopFloor=false;
        navigator.cancel(id);s.nextUpdate=ticks+cadence;
        // Station movement has its own destination; preserve the established WORK post.
        if(stationTravel)return false;
        if(s.destination!=null && s.goal!=null) {
            RoutineTerrain terrain=new RoutineTerrain(position.getWorld(),doors);
            boolean furniture=s.goal.type()==RoutineGoal.Type.SIT || s.goal.type()==RoutineGoal.Type.SLEEP;
            s.approachOptions=terrain.approaches(s.destination,furniture).stream().map(terrain::location)
                    .sorted(java.util.Comparator.comparingDouble(position::distanceSquared)).map(location->{
                        Location value=location.clone();value.setYaw(s.destination.yaw());return value;
                    }).toList();
            s.approachIndex=0;
            if(s.approachOptions.isEmpty()) {
                release(s,false);s.nextPick=ticks+100;s.status="destino sin suelo seguro";return true;
            }
            s.approach=s.approachOptions.getFirst();
        }
        return false;
    }
    private boolean restoreSeat(State state,boolean force) {
        UUID previous=internalEntity;boolean priorMounting=mounting;
        internalEntity=state.npc.entity().getUniqueId();mounting=true;
        try{return visuals.restoreSeat(state.pose,force);}
        finally{internalEntity=previous;mounting=priorMounting;}
    }
    private void endDance(State s) {
        if(!s.dancing)return;
        clearStuckHop(s);
        if(dancers!=null)dancers.cancel(s.npc.definition().id());
        s.dancing=false;s.returningFromDance=s.goal!=null && s.goal.type()==RoutineGoal.Type.SIT
                && s.destination!=null && s.approach!=null;
        s.nextDanceCheck=Long.MAX_VALUE;s.nextReturnCheck=0;s.returnWaitingSince=0;s.returnDoorway=0;s.returnFailedDoorways=0;
        s.nextUpdate=0;resetTravelProgress(s,s.npc.position());
    }
    private Location returnApproach(RoutineGoal.Point point,Location from,int attempt) {
        var terrain=new RoutineTerrain(from.getWorld(),doors);
        List<Location> approaches=new ArrayList<>(4);
        // Four local doorways are bounded and checked only every five seconds of a return.
        for(int[] side:new int[][]{{0,1},{1,0},{0,-1},{-1,0}}) {
            var node=terrain.near(new Location(from.getWorld(),point.x()+side[0]+.5,point.y(),point.z()+side[1]+.5));
            if(node==null)continue;
            approaches.add(terrain.location(node));
        }
        approaches.sort(java.util.Comparator.comparingDouble(from::distanceSquared));
        return approaches.isEmpty()?null:approaches.get(Math.floorMod(attempt,approaches.size()));
    }
    private boolean readyToSit(ActiveNpc npc,Location approach) {
        if(approach==null || npc.position().getWorld()!=approach.getWorld()
                || npc.position().distanceSquared(approach)>.36)return false;
        var at=npc.position();var terrain=new RoutineTerrain(at.getWorld(),doors);var floor=terrain.near(at);
        return floor!=null && Math.abs(terrain.height(floor)-at.getY())<.12
                && terrain.fits(at.getX(),at.getY(),at.getZ(),false);
    }
    private void updateDialogue(ActiveNpc npc,RoutineGoal goal,long now) {
        RoutineGoal.Dialogue dialogue=goal.dialogue();
        if(!dialogue.configured() && goal.type()==RoutineGoal.Type.WORK) {
            var legacy=npc.definition().dialogue();
            dialogue=new RoutineGoal.Dialogue(legacy.enabled(),legacy.range(),legacy.intervalSeconds(),legacy.initialDelaySeconds(),
                    legacy.random(),legacy.lineOfSight(),legacy.lines(),false);
        }
        if(!dialogue.enabled() || dialogue.lines().isEmpty() || dialogue.range()<=0) return;
        Collection<Player> players=npc.position().getWorld().getNearbyPlayers(npc.position(),dialogue.range(),
                player->PlayerFilter.accepts(player,plugin.settings()));
        final RoutineGoal.Dialogue activeDialogue=dialogue;
        for(Player player:players) {
            if(activeDialogue.lineOfSight() && !npc.entity().hasLineOfSight(player)) continue;
            var key=new DialogueKey(npc.definition().id(),goal.order(),player.getUniqueId());
            var state=dialogueStates.computeIfAbsent(key,k->new DialogueState(now+(long)(activeDialogue.initialDelaySeconds()*1_000_000_000L),now));
            state.lastSeen=now; if(now<state.due) continue;
            int index=activeDialogue.random()?ThreadLocalRandom.current().nextInt(activeDialogue.lines().size()):state.nextLine;
            state.nextLine=(index+1)%activeDialogue.lines().size();
            say(npc,player,activeDialogue.lines().get(index));
            state.due=now+(long)(activeDialogue.intervalSeconds()*1_000_000_000L);
        }
    }
    private boolean validFurniture(RoutineGoal goal,RoutineGoal.Point point,World world) {
        if(point==null || !world.isChunkLoaded(point.x()>>4,point.z()>>4)) return false;
        var data=world.getBlockAt(point.x(),point.y(),point.z()).getBlockData();
        return goal.type()==RoutineGoal.Type.SLEEP ? data instanceof Bed : data instanceof Stairs;
    }
    private RoutineGoal.Point choose(RoutineGoal goal,State state,Location from) {
        if(goal.type()==RoutineGoal.Type.WALK && goal.mode()!=RoutineGoal.WalkMode.RANDOM) return goal.points().get(state.point);
        if(goal.type()==RoutineGoal.Type.WORK) return goal.points().getFirst();
        List<RoutineGoal.Point> candidates=new ArrayList<>(); RoutineGoal.Point nearest=null;double best=Double.MAX_VALUE;
        state.blockedDanceSeats.entrySet().removeIf(entry->ticks>=entry.getValue());
        for(var point:goal.points()) {
            if(!point.world().equals(from.getWorld().getUID())) continue;
            if(goal.type()==RoutineGoal.Type.SIT || goal.type()==RoutineGoal.Type.SLEEP) {
                if(occupied.containsKey(key(point)) || state.blockedDanceSeats.containsKey(key(point))
                        || !validFurniture(goal,point,from.getWorld())) continue;
                var data=from.getWorld().getBlockAt(point.x(),point.y(),point.z()).getBlockData();
                if(data instanceof Bed bed && bed.isOccupied()) continue;
                candidates.add(point);continue;
            }
            double distance=point.location(from.getWorld()).distanceSquared(from);
            if(distance<.25 && goal.points().size()>1) continue;
            if(distance<best) {best=distance;nearest=point;}
            if(distance<=goal.radius()*goal.radius()) candidates.add(point);
        }
        return candidates.isEmpty()?nearest:candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }
    public String status(String id) {
        if(!enabled(id)) return "sin rutina activa";
        if(failed.contains(id)) return "error: revisa consola";
        State s=states.get(id); return s==null?"pendiente de cargar zona":s.status+(s.goal==null?"":"; goal "+s.goal.order());
    }
    public String metrics() { return "rutas de navegación activas="+navigator.activeRoutes()+", búsquedas pendientes="+navigator.searches()+", NPC con estado="+states.size(); }
}
