package com.mdvcraft.mdvnpc.routine;

import com.destroystokyo.paper.entity.Pathfinder;
import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Node;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.BiPredicate;

/**
 * Paper calculates the route; short controlled steps replay its waypoints while the villager
 * brain remains disabled. Searches widen only when a partial route stops making useful progress.
 */
public final class RoutineNavigator {
    public enum Result { MOVING, ARRIVED, WAITING }
    public enum HopFailure { NONE, INVALID_START, BLOCKED, DISPLACED, NO_MOTION, TELEPORT_REJECTED }
    private enum SearchFailure { NONE, NO_ROUTE, CHUNK, DENIED_DOOR }
    private static final int[] PAPER_SEARCH_RANGES = {16, 24, 32, 48, 64};
    private static final int MAX_PATH_POINTS = 2048;
    private static final int LOCAL_RETRY_TICKS = 4, PARTIAL_RETRY_TICKS = 4,
            CHUNK_RETRY_TICKS = 20, IMPOSSIBLE_RETRY_TICKS = 100;
    private static final int STUCK_TICKS = 36;
    private static final int PARTIAL_ENDPOINT_HISTORY = 12;
    private static final double PROGRESS_DISTANCE_SQUARED = .15 * .15;
    private static final double GOAL_PROGRESS_DISTANCE = .15;
    private static final double MOVEMENT_EPSILON_SQUARED = 1e-12;
    private static final double GROUND_EPSILON = .015;
    private static final double FLOOR_SETTLE_EPSILON = 1e-7;
    /** Waypoints are converted from Paper's integer cells to the real collision height.
     * Horizontal tolerance prevents tiny orbiting near the centre, while the vertical
     * tolerance stays tight so mud/soul-sand/path/carpet levels are actually visited. */
    private static final double WAYPOINT_HORIZONTAL_EPSILON = .15;
    private static final double WAYPOINT_VERTICAL_EPSILON = 1e-4;
    /** MDVNPC deliberately allows slightly more capable pedestrian traversal than vanilla:
     * decorative market paths frequently combine a full block with carpet/slab layers. */
    private double MAX_CLIMB_HEIGHT = 1.30;
    private double MAX_DROP_HEIGHT = 2.30;
    private double MAX_STEP_HEIGHT = MAX_CLIMB_HEIGHT;
    /** Starting the lift before the hitbox reaches the riser is essential: teleport replay
     * cannot pass through a slab/full-block side while incrementally gaining Y. */
    private static final double RISER_SUPPORT_RADIUS = .065;
    /** When Paper compresses consecutive nodes to the same Y, follow the real support on
     * the route centreline. The almost-point-sized probe prevents a merely lateral block from
     * becoming a stair; the vertical envelope itself is still capped at MAX_CLIMB_HEIGHT. */
    private double MAX_UNANNOUNCED_RISER = MAX_CLIMB_HEIGHT;
    /** Small decorative dips may be absent from Paper's node list. Larger falls require a
     * lower native waypoint so a compressed flat route cannot make an NPC dive into a pit. */
    private double MAX_UNANNOUNCED_DROP = 1.01;
    private static final double UNANNOUNCED_SUPPORT_RADIUS = .001;
    /** Approximate villager/player half-width. When a horizontal substep first collides,
     * sample just beyond the body's leading edge to discover the low surface causing it. */
    private static final double RISER_BODY_LEAD = .305;
    /** If a partial route finishes almost below/above the destination, following it only
     * makes the NPC hug the wall/floor. Widen first so Paper can discover stairs/ramps/doors. */
    private static final double VERTICAL_SHADOW_HORIZONTAL_SQUARED = 4.5 * 4.5;
    private static final double VERTICAL_SHADOW_MIN_Y = .75;
    private static final int MINIMUM_HOP_TICKS = 12;
    private static final double HOP_SWEEP_INTERVAL = .08;
    private static final double HOP_POSITION_TOLERANCE_SQUARED = .05*.05;
    private final DoorController doors;
    private final BiPredicate<ActiveNpc, Location> teleport;
    private final Map<String, Travel> travels = new LinkedHashMap<>();
    private final Map<String, RecoveryHop> hops = new LinkedHashMap<>();
    private final Map<String, HopFailure> hopFailures = new LinkedHashMap<>();
    private long budgetTick = Long.MIN_VALUE, spentNanos;
    private int maximumStarts = 2, startsLeft = 2;
    private long maximumNanos = 2_000_000;
    private final NetheriteNodeIndex nodeIndex = new NetheriteNodeIndex();
    private boolean nodesEnabled;
    private int nodeLinkRange=12, nodeEndpointRange=16, nodeScanMargin=24, nodeMaxDistance=256;
    private int nodeBlockBudget=8192, nodeSearchBudget=256, nodeBlocksLeft, nodeSearchLeft;

    private record PathPlan(List<Location> points, boolean reachesFinal, Location endpoint) {}
    private record SearchOutcome(PathPlan plan, SearchFailure failure) {}

    private static final class Travel {
        final ActiveNpc npc;
        final RoutineTerrain terrain;
        final Location destination;
        List<Location> path;
        int index;
        long retry;
        boolean pending = true;
        boolean pathReachesFinal;
        int searchLevel;
        int minimumSearchLevel;
        final int maxSearchLevel = PAPER_SEARCH_RANGES.length - 1;
        int localFailures;
        boolean exhausted;
        boolean avoidClosedDoors;
        boolean doorDeniedDuringStep;
        NetheriteNodeIndex.Scan nodeScan;
        NetheriteNodeRouter nodeRouter;
        boolean nodesTried, nodePath;
        List<Node> routeMarkers=List.of();
        final Map<Node,Node> markerSources=new HashMap<>();
        int markerIndex;
        final Deque<Location> partialEndpoints = new ArrayDeque<>();
        double bestGoalDistance;
        double segmentStartBest;
        int segmentsWithoutGoalProgress;
        Location riserWaypoint;
        double riserHeight = Double.NaN;
        double riserBaseY = Double.NaN;
        /** Safe floor selected while crossing a ledge. Kept across ticks so RoutineGravity
         * does not steal/cancel the route in the middle of a controlled <=2.3 block descent. */
        double dropHeight = Double.NaN;
        Location lastCommandPosition;
        Location lastProgressPosition;
        long lastProgressTick;
        Travel(ActiveNpc npc, Location destination, DoorController doors, Location start, long tick) {
            this.npc = npc; this.destination = destination.clone();
            terrain = new RoutineTerrain(destination.getWorld(), doors);
            // The only entrance or staircase can require a detour on any floor. Every trip
            // starts cheaply at 16, widening only after an unsuccessful or stagnant segment.
            bestGoalDistance = start.distance(destination);
            lastCommandPosition = start.clone();
            lastProgressPosition = start.clone(); lastProgressTick = tick;
        }
    }

    private static final class RecoveryHop {
        final ActiveNpc npc;
        final RoutineTerrain terrain;
        final Location start, landing;
        final double height, quadratic, linear, sweepRate;
        final int duration;
        final boolean forced;
        Location lastCommand;
        long lastTick;
        int elapsed;
        RecoveryHop(ActiveNpc npc,RoutineTerrain terrain,Location start,Location landing,double height,long tick) {
            this(npc,terrain,start,landing,height,tick,false);
        }
        RecoveryHop(ActiveNpc npc,RoutineTerrain terrain,Location start,Location landing,double height,long tick,boolean forced) {
            this.npc=npc;this.terrain=terrain;this.start=start.clone();this.landing=landing.clone();this.height=height;
            this.forced=forced;
            double delta=landing.getY()-start.getY();
            // Solve the parabola through both floors with its apex at startY + height.
            quadratic=2*height-delta+2*Math.sqrt(Math.max(0,height*(height-delta)));
            linear=quadratic+delta;
            sweepRate=Math.hypot(landing.getX()-start.getX(),landing.getZ()-start.getZ())
                    +Math.max(Math.abs(linear),Math.abs(linear-2*quadratic));
            duration=Math.max(MINIMUM_HOP_TICKS,(int)Math.ceil(Math.max(height*20,
                    Math.hypot(landing.getX()-start.getX(),landing.getZ()-start.getZ())*8)));
            lastCommand=start.clone();lastTick=tick;
        }
        Location frame(double phase) {
            if(phase>=1)return landing.clone();
            return start.clone().add((landing.getX()-start.getX())*phase,
                    linear*phase-quadratic*phase*phase,(landing.getZ()-start.getZ())*phase);
        }
    }

    public RoutineNavigator(DoorController doors, BiPredicate<ActiveNpc, Location> teleport) {
        this.doors = doors; this.teleport = teleport;
    }
    /** Vertical limits in blocks; invalid values fall back to the defaults. */
    public void configureHeights(double climb, double drop, double unannouncedDrop) {
        MAX_CLIMB_HEIGHT = climb > 0 && climb <= 4 ? climb : 1.30;
        MAX_STEP_HEIGHT = MAX_CLIMB_HEIGHT;
        MAX_UNANNOUNCED_RISER = MAX_CLIMB_HEIGHT;
        MAX_DROP_HEIGHT = drop > 0 && drop <= 8 ? drop : 2.30;
        MAX_UNANNOUNCED_DROP = unannouncedDrop > 0 && unannouncedDrop <= MAX_DROP_HEIGHT ? unannouncedDrop : Math.min(1.01, MAX_DROP_HEIGHT);
    }
    /** Hidden netherite markers guide long trips; terrain replay still owns every step. */
    public void configureNodes(boolean enabled,int linkRange,int endpointRange,int scanMargin,
                               int maxDistance,int blockBudget,int searchBudget) {
        nodesEnabled=enabled;
        nodeLinkRange=Math.max(2,Math.min(32,linkRange));
        nodeEndpointRange=Math.max(2,Math.min(48,endpointRange));
        nodeScanMargin=Math.max(0,Math.min(64,scanMargin));
        nodeMaxDistance=Math.max(16,Math.min(512,maxDistance));
        nodeBlockBudget=Math.max(256,Math.min(65536,blockBudget));
        nodeSearchBudget=Math.max(16,Math.min(2048,searchBudget));
        clear();
    }
    public void invalidateNodes(org.bukkit.World world,int blockX,int blockZ) {
        if(nodesEnabled)nodeIndex.invalidate(world,blockX,blockZ);
    }
    /** Compatibility with older integrations; the former A* limits and route cache are unused. */
    public RoutineNavigator(DoorController doors, BiPredicate<ActiveNpc, Location> teleport, int maxNodes, int cacheSize) {
        this(doors, teleport);
    }
    /** A single shared allowance spreads simultaneous departures over successive updates. */
    public void beginTick(long tick, int maximumStarts, long maximumNanos) {
        if (budgetTick == tick) return;
        budgetTick = tick;
        this.maximumStarts = Math.max(1, Math.min(8, maximumStarts));
        this.maximumNanos = Math.max(1, maximumNanos);
        startsLeft = this.maximumStarts; spentNanos = 0;
        nodeBlocksLeft=nodeBlockBudget; nodeSearchLeft=nodeSearchBudget;
    }
    /** No background A* search remains. Kept for source compatibility only. */
    public void searchBudget(int nodes, long nanos) {}

    /** Preflight only: the routine decides when a grounded, idle walker needs recovery. */
    public boolean startHop(ActiveNpc npc,double height,double distance,long tick) {
        return queueHop(npc,height,distance,tick);
    }

    /** Always queue the configured forward impulse, including airborne or embedded starts.
     * Actual collisions clip individual motion axes while the animation keeps running. */
    public boolean startRecoveryHop(ActiveNpc npc,double height,double distance,long tick) {
        String id=npc.definition().id();
        if(hops.containsKey(id))return false;
        Location start=npc.entity().getLocation();
        if(!validHopStart(npc,start,height,distance)) {
            recordHopFailure(id,HopFailure.INVALID_START);return false;
        }
        double yaw=Math.toRadians(start.getYaw());
        Location target=start.clone().add(-Math.sin(yaw)*distance,0,Math.cos(yaw)*distance);
        hops.put(id,new RecoveryHop(npc,new RoutineTerrain(start.getWorld(),doors),start,target,height,tick,true));
        hopFailures.remove(id);return true;
    }

    private boolean validHopStart(ActiveNpc npc,Location start,double height,double distance) {
        return Double.isFinite(height) && Double.isFinite(distance) && height>=.05 && height<=4
                && distance>=.05 && distance<=4 && start.getWorld()!=null
                && Double.isFinite(start.getX()) && Double.isFinite(start.getY()) && Double.isFinite(start.getZ())
                && Float.isFinite(start.getYaw()) && npc.entity().isValid() && !npc.entity().isInsideVehicle();
    }

    private boolean queueHop(ActiveNpc npc,double height,double distance,long tick) {
        String id=npc.definition().id();
        if(hops.containsKey(id))return false;
        Location start=npc.entity().getLocation();
        if(!validHopStart(npc,start,height,distance)) {
            recordHopFailure(id,HopFailure.INVALID_START);return false;
        }
        RoutineTerrain terrain=new RoutineTerrain(start.getWorld(),doors);
        try(RoutineTerrain.Update ignored=terrain.beginUpdate()) {
            double support=terrain.supportHeight(start.getX(),start.getY(),start.getZ(),.02);
            if(!Double.isFinite(support) || Math.abs(support-start.getY())>.02
                    || !terrain.recoveryHopFits(start.getX(),start.getY(),start.getZ(),start.getY(),false)
                    || !terrain.fenceClearAtBase(start.getX(),start.getY(),start.getZ())) {
                recordHopFailure(id,HopFailure.INVALID_START);return false;
            }
            double yaw=Math.toRadians(start.getYaw());
            Location landing=start.clone().add(-Math.sin(yaw)*distance,0,Math.cos(yaw)*distance);
            double floor=terrain.recoveryHopLanding(landing.getX(),start.getY(),landing.getZ(),
                    Math.min(height,MAX_DROP_HEIGHT),height);
            if(!Double.isFinite(floor) || floor>start.getY()+height+FLOOR_SETTLE_EPSILON) {
                recordHopFailure(id,HopFailure.BLOCKED);return false;
            }
            landing.setY(floor);
            RecoveryHop hop=new RecoveryHop(npc,terrain,start,landing,height,tick);
            if(!hopArcClear(hop,0,1,true)) {
                recordHopFailure(id,HopFailure.BLOCKED);return false;
            }
            hops.put(id,hop);hopFailures.remove(id);return true;
        }
    }

    /** Advance one visible frame; missed updates cannot collapse the arc into one teleport. */
    public Result advanceHop(ActiveNpc npc,long tick) {
        String id=npc.definition().id();RecoveryHop hop=hops.get(id);
        if(hop==null)return Result.WAITING;
        Location current=npc.entity().getLocation();
        if(hop.npc!=npc || current.getWorld()!=hop.start.getWorld()
                || !hop.forced && current.distanceSquared(hop.lastCommand)>HOP_POSITION_TOLERANCE_SQUARED) {
            travels.remove(id);return abortHop(id,HopFailure.DISPLACED);
        }
        if(!npc.entity().isValid() || npc.entity().isInsideVehicle())return abortHop(id,HopFailure.INVALID_START);
        if(tick<=hop.lastTick)return Result.MOVING;
        int nextElapsed=Math.min(hop.duration,hop.elapsed+(int)Math.min(2,tick-hop.lastTick));
        double before=(double)hop.elapsed/hop.duration,phase=(double)nextElapsed/hop.duration;
        if(hop.forced)return advanceForcedHop(hop,current,before,phase,nextElapsed,tick);
        Location next=hop.frame(phase);
        try(RoutineTerrain.Update ignored=hop.terrain.beginUpdate()) {
            double landing=hop.terrain.recoveryHopLanding(hop.landing.getX(),hop.start.getY(),hop.landing.getZ(),
                    Math.min(hop.height,MAX_DROP_HEIGHT),hop.height);
            if(!Double.isFinite(landing) || Math.abs(landing-hop.landing.getY())>WAYPOINT_VERTICAL_EPSILON
                    || !hopChordClear(hop,current,next,tick) || !hopArcClear(hop,before,phase,false))
                return abortHop(id,HopFailure.BLOCKED);
            boolean accepted=teleport.test(npc,next);
            Location actual=npc.entity().getLocation();
            boolean moved=actual.getWorld()!=current.getWorld()
                    || actual.distanceSquared(current)>MOVEMENT_EPSILON_SQUARED;
            if(moved)travels.remove(id);
            if(!accepted)return abortHop(id,HopFailure.TELEPORT_REJECTED);
            if(!moved)return abortHop(id,HopFailure.NO_MOTION);
            if(actual.getWorld()!=next.getWorld() || actual.distanceSquared(next)>HOP_POSITION_TOLERANCE_SQUARED
                    || !hop.terrain.recoveryHopFits(actual.getX(),actual.getY(),actual.getZ(),hop.start.getY(),false))
                return abortHop(id,HopFailure.DISPLACED);
            hop.lastCommand=actual.clone();hop.elapsed=nextElapsed;hop.lastTick=tick;
            if(nextElapsed>=hop.duration) {
                double actualFloor=hop.terrain.recoveryHopLanding(actual.getX(),hop.start.getY(),actual.getZ(),
                        Math.min(hop.height,MAX_DROP_HEIGHT),hop.height);
                if(!Double.isFinite(actualFloor) || Math.abs(actual.getY()-actualFloor)>WAYPOINT_VERTICAL_EPSILON)
                    return abortHop(id,HopFailure.DISPLACED);
                hops.remove(id);hopFailures.remove(id);return Result.ARRIVED;
            }
            return Result.MOVING;
        }
    }

    /** A blocked impulse still consumes its visible frames; another attempt is scheduled
     * by the routine's quiet clock. No landing or complete-arc preflight vetoes this mode. */
    private Result advanceForcedHop(RecoveryHop hop,Location current,double before,double phase,int nextElapsed,long tick) {
        String id=hop.npc.definition().id();
        Vector requested=hop.frame(phase).toVector().subtract(hop.frame(before).toVector());
        int samples=Math.max(1,(int)Math.ceil(requested.length()/HOP_SWEEP_INTERVAL));
        Vector piece=requested.multiply(1.0/samples);
        Location next=current.clone();
        try(RoutineTerrain.Update ignored=hop.terrain.beginUpdate()) {
            for(int i=0;i<samples;i++) {
                Vector impulse=piece.clone();
                if(!doors.openNear(hop.npc.entity(),next.clone().add(impulse),tick)) {
                    impulse.setX(0);impulse.setZ(0);
                }
                next.add(hop.terrain.clipRecoveryHop(next,impulse,hop.start.getY()));
            }
            if(next.distanceSquared(current)>MOVEMENT_EPSILON_SQUARED) {
                boolean accepted=teleport.test(hop.npc,next);
                Location actual=hop.npc.entity().getLocation();
                if(actual.getWorld()!=current.getWorld() || actual.distanceSquared(current)>MOVEMENT_EPSILON_SQUARED)
                    travels.remove(id);
                if(!accepted)return abortHop(id,HopFailure.TELEPORT_REJECTED);
                if(actual.getWorld()!=next.getWorld())return abortHop(id,HopFailure.DISPLACED);
                hop.lastCommand=actual.clone();
            } else hop.lastCommand=current.clone();
            hop.elapsed=nextElapsed;hop.lastTick=tick;
            if(nextElapsed>=hop.duration) {
                hops.remove(id);hopFailures.remove(id);return Result.ARRIVED;
            }
            return Result.MOVING;
        }
    }

    private boolean hopArcClear(RecoveryHop hop,double from,double to,boolean planned) {
        int samples=Math.max(1,(int)Math.ceil(hop.sweepRate*(to-from)/HOP_SWEEP_INTERVAL));
        for(int i=0;i<=samples;i++) {
            Location point=hop.frame(from+(to-from)*i/samples);
            if(!hop.terrain.recoveryHopFits(point.getX(),point.getY(),point.getZ(),hop.start.getY(),planned))return false;
        }
        return true;
    }
    private boolean hopChordClear(RecoveryHop hop,Location from,Location to,long tick) {
        int samples=Math.max(1,(int)Math.ceil(from.distance(to)/HOP_SWEEP_INTERVAL));
        for(int i=1;i<=samples;i++) {
            double phase=(double)i/samples;
            Location point=from.clone().add((to.getX()-from.getX())*phase,(to.getY()-from.getY())*phase,
                    (to.getZ()-from.getZ())*phase);
            if(!doors.openNear(hop.npc.entity(),point,tick)
                    || !hop.terrain.recoveryHopFits(point.getX(),point.getY(),point.getZ(),hop.start.getY(),false))return false;
        }
        return true;
    }
    private Result abortHop(String id,HopFailure reason) {
        hops.remove(id);recordHopFailure(id,reason);return Result.WAITING;
    }
    private void recordHopFailure(String id,HopFailure reason) {
        if(!hopFailures.containsKey(id) && hopFailures.size()>=256)hopFailures.remove(hopFailures.keySet().iterator().next());
        hopFailures.put(id,reason);
    }
    public boolean activeHop(String id) {return hops.containsKey(id);}
    public HopFailure hopFailure(String id) {return hopFailures.getOrDefault(id,HopFailure.NONE);}
    public void cancelHop(String id) {hops.remove(id);hopFailures.remove(id);}

    public Result move(ActiveNpc npc, Location destination, double speed, long tick, int cadence) {
        String id = npc.definition().id();
        Location current = npc.entity().getLocation();
        if (destination.getWorld() == null || current.getWorld() != destination.getWorld()) {
            cancel(id); return Result.WAITING;
        }
        if(activeHop(id)) {
            Result recovery=advanceHop(npc,tick);
            // Completing recovery is not arrival at the caller's routine destination.
            if(recovery==Result.ARRIVED)
                return arrivedAt(npc.entity().getLocation(),destination)?Result.ARRIVED:Result.MOVING;
            return recovery;
        }
        if (arrivedAt(current,destination)) { cancel(id); return Result.ARRIVED; }
        if (budgetTick != tick) beginTick(tick, maximumStarts, maximumNanos);
        Travel travel = travels.get(id);
        if (travel == null || travel.npc != npc || travel.destination.getWorld() != destination.getWorld()
                || travel.destination.distanceSquared(destination) > .01) {
            cancel(id); travel = new Travel(npc, destination, doors, current, tick); travels.put(id, travel);
        }
        try (RoutineTerrain.Update ignored = travel.terrain.beginUpdate()) {
            return advance(travel, current, speed, tick, cadence);
        }
    }

    private Result advance(Travel travel, Location current, double speed, long tick, int cadence) {
        travel.doorDeniedDuringStep=false;
        ActiveNpc npc = travel.npc;
        Location destination = travel.destination;
        // An external teleport/push invalidates the route's starting assumptions. Re-query
        // from the new feet position instead of walking back through old waypoints.
        if (current.distanceSquared(travel.lastCommandPosition) > .25) {
            travel.path = null; travel.pending = true; travel.retry = tick;
            travel.partialEndpoints.clear(); travel.segmentsWithoutGoalProgress = 0;
            travel.searchLevel = 0; travel.minimumSearchLevel = 0;
            travel.avoidClosedDoors = false;
            travel.riserWaypoint = null; travel.riserHeight = Double.NaN; travel.riserBaseY = Double.NaN; travel.dropHeight = Double.NaN;
            travel.bestGoalDistance = current.distance(destination);
            travel.lastCommandPosition = current.clone();
            travel.lastProgressPosition = current.clone(); travel.lastProgressTick = tick;
            travel.localFailures = 0; travel.exhausted = false;
            resetNodePlanning(travel);
        }
        if(travel.nodePath && !validNextMarker(travel,current)) {
            localFailure(travel,tick);return Result.WAITING;
        }
        observeProgress(travel,current,tick);
        if (travel.path != null && tick-travel.lastProgressTick >= STUCK_TICKS) {
            localFailure(travel,tick); return Result.WAITING;
        }
        if (travel.path == null) {
            if (tick < travel.retry) return Result.WAITING;
            if (!travel.terrain.loaded(current.getBlockX(), current.getBlockZ())
                    || !travel.terrain.loaded(destination.getBlockX(), destination.getBlockZ())) {
                chunkFailure(travel, tick); return Result.WAITING;
            }
            if (startsLeft <= 0 || spentNanos >= maximumNanos) return Result.WAITING;
            startsLeft--;
            long started = System.nanoTime();
            try {
                Location start = prepareStart(travel, current, tick, speed, cadence);
                if (start == null) { localFailure(travel, tick); return Result.WAITING; }
                // Settling onto a nearby verified floor follows the same speed limit as walking.
                if (current.distanceSquared(start) > MOVEMENT_EPSILON_SQUARED) return Result.MOVING;
                current = start;
                PathPlan guided=null;
                if(nodesEnabled && !travel.nodesTried) {
                    guided=findNodePath(travel,start);
                    if(!travel.nodesTried)return Result.WAITING;
                }
                SearchOutcome outcome = guided==null ? findPaperPath(travel, start)
                        : new SearchOutcome(guided,SearchFailure.NONE);
                if (outcome.plan() == null) {
                    if (outcome.failure() == SearchFailure.CHUNK) chunkFailure(travel,tick);
                    else if (outcome.failure() == SearchFailure.DENIED_DOOR && !travel.avoidClosedDoors)
                        avoidClosedDoors(travel,tick,cadence);
                    else noRouteFailure(travel,tick);
                    return Result.WAITING;
                }
                PathPlan plan=outcome.plan();
                double endpointMovement=start.distanceSquared(plan.endpoint());
                if (!plan.reachesFinal() && endpointMovement < .04) {
                    noRouteFailure(travel,tick); return Result.WAITING;
                }
                if (!plan.reachesFinal() && repeatsStagnantEndpoint(travel, plan.endpoint())) {
                    noRouteFailure(travel, tick);
                    travel.minimumSearchLevel = Math.max(travel.minimumSearchLevel, travel.searchLevel);
                    return Result.WAITING;
                }
                // Do not walk into the vertical "shadow" of a bed/workstation on another floor.
                // A short-range partial path commonly ends against the wall directly below/above
                // the goal. Re-run Paper with a wider FOLLOW_RANGE until it can see the actual
                // stairs/ramp/door detour. At maximum range, reject the dead-end instead of
                // oscillating a couple of blocks forever.
                if (!plan.reachesFinal() && verticalShadow(plan.endpoint(), travel.destination)
                        && !usefulFloorProgress(start,plan.endpoint(),travel.destination)) {
                    if (travel.searchLevel < travel.maxSearchLevel) {
                        travel.searchLevel++;
                        travel.minimumSearchLevel = Math.max(travel.minimumSearchLevel, travel.searchLevel);
                        travel.path = null; travel.pending = true; travel.exhausted = false;
                        travel.retry = tick + LOCAL_RETRY_TICKS;
                    } else {
                        noRouteFailure(travel, tick);
                    }
                    return Result.WAITING;
                }
                travel.path = plan.points(); travel.pathReachesFinal=plan.reachesFinal();
                travel.index = 0; travel.pending = false; travel.exhausted=false;
                // Paper's first node represents the occupied starting cell. Replaying its
                // centre after every local replan makes an off-centre NPC walk backward,
                // then forward into the same obstacle indefinitely. Consume that node only
                // on the actual starting floor; higher/lower starts still need a transition.
                Location first=travel.path.getFirst();
                if(travel.path.size()>1 && first.getBlockX()==current.getBlockX()
                        && first.getBlockZ()==current.getBlockZ()
                        && Math.abs(first.getY()-current.getY())<=WAYPOINT_VERTICAL_EPSILON)
                    travel.index=1;
                travel.segmentStartBest = travel.bestGoalDistance;
                travel.lastProgressPosition = current.clone(); travel.lastProgressTick = tick;
                travel.riserWaypoint = null; travel.riserHeight = Double.NaN; travel.riserBaseY = Double.NaN; travel.dropHeight = Double.NaN;
                tuneNextSearch(travel,start,plan);
            } finally {
                spentNanos += System.nanoTime() - started;
            }
        }
        while (travel.index < travel.path.size()
                && reachedWaypoint(travel,current,travel.path.get(travel.index))) travel.index++;
        if (travel.index >= travel.path.size()) {
            // A partial path is a valid segment of a long trip. Re-query from its endpoint;
            // the search level may already have widened if that segment represented a detour.
            if (!travel.pathReachesFinal) rememberPartialEndpoint(travel);
            travel.riserWaypoint = null; travel.riserHeight = Double.NaN; travel.riserBaseY = Double.NaN; travel.dropHeight = Double.NaN;
            travel.path = null; travel.pending = true; travel.retry = tick + PARTIAL_RETRY_TICKS;
            return Result.WAITING;
        }
        Location next = travel.path.get(travel.index);
        Node nextFloor = new Node(next.getBlockX(), (int)Math.ceil(next.getY() - .02), next.getBlockZ());
        if (!travel.terrain.stand(nextFloor) || Math.abs(travel.terrain.height(nextFloor) - next.getY()) > .08) {
            if (!travel.avoidClosedDoors && deniedClosedDoor(travel,next)) avoidClosedDoors(travel,tick,cadence);
            else localFailure(travel, tick);
            return Result.WAITING;
        }
        double distance = Math.max(.01, speed) * Math.max(1, cadence) / 20.0;
        Location step = walkingStep(travel,current,next,distance,tick);
        if(step==null) {localFailure(travel,tick);return Result.WAITING;}
        Vector delta = step.toVector().subtract(current.toVector());
        if (Math.abs(delta.getX()) + Math.abs(delta.getZ()) > .001)
            step.setDirection(new Vector(delta.getX(), 0, delta.getZ()));
        if (!applyStep(travel, current, step)) { localFailure(travel, tick); return Result.WAITING; }
        if(travel.doorDeniedDuringStep) { localFailure(travel,tick);return Result.WAITING; }
        return Result.MOVING;
    }

    private void observeProgress(Travel travel,Location current,long tick) {
        double goalDistance = current.distance(travel.destination);
        if (goalDistance < travel.bestGoalDistance - GOAL_PROGRESS_DISTANCE) {
            travel.bestGoalDistance = goalDistance;
            travel.partialEndpoints.clear(); travel.segmentsWithoutGoalProgress = 0;
            travel.minimumSearchLevel = 0;
        }
        if(travel.lastProgressPosition==null) {
            travel.lastProgressPosition=current.clone();travel.lastProgressTick=tick;return;
        }
        if(current.distanceSquared(travel.lastProgressPosition)>=PROGRESS_DISTANCE_SQUARED) {
            travel.lastProgressPosition=current.clone();travel.lastProgressTick=tick;
            travel.localFailures=0;travel.exhausted=false;
        }
    }

    private boolean repeatsStagnantEndpoint(Travel travel, Location endpoint) {
        if (endpoint.distance(travel.destination) < travel.bestGoalDistance - GOAL_PROGRESS_DISTANCE) return false;
        for (Location prior : travel.partialEndpoints) if (prior.distanceSquared(endpoint) < .25) return true;
        return false;
    }

    private void rememberPartialEndpoint(Travel travel) {
        Location endpoint = travel.path.getLast();
        if (travel.bestGoalDistance < travel.segmentStartBest - GOAL_PROGRESS_DISTANCE)
            travel.segmentsWithoutGoalProgress = 0;
        else travel.segmentsWithoutGoalProgress++;
        if (travel.partialEndpoints.size() >= PARTIAL_ENDPOINT_HISTORY) travel.partialEndpoints.removeFirst();
        travel.partialEndpoints.addLast(endpoint.clone());
    }

    /** A successful teleport event can still leave the entity in place. Count actual motion. */
    private boolean applyStep(Travel travel, Location before, Location step) {
        if (before.distanceSquared(step) <= MOVEMENT_EPSILON_SQUARED || !teleport.test(travel.npc, step)) return false;
        Location actual = travel.npc.entity().getLocation();
        if (actual.getWorld() != before.getWorld()
                || actual.distanceSquared(before) <= MOVEMENT_EPSILON_SQUARED) return false;
        travel.lastCommandPosition = actual.clone();
        return true;
    }

    private static boolean arrivedAt(Location current,Location destination) {
        if(current.getWorld()!=destination.getWorld())return false;
        double dx=current.getX()-destination.getX(),dz=current.getZ()-destination.getZ();
        return dx*dx+dz*dz<=WAYPOINT_HORIZONTAL_EPSILON*WAYPOINT_HORIZONTAL_EPSILON
                && Math.abs(current.getY()-destination.getY())<=WAYPOINT_VERTICAL_EPSILON;
    }

    private static boolean reachedWaypoint(Travel travel,Location current,Location waypoint) {
        if(current.getWorld()!=waypoint.getWorld())return false;
        double dx=current.getX()-waypoint.getX(),dz=current.getZ()-waypoint.getZ();
        return dx*dx+dz*dz<=WAYPOINT_HORIZONTAL_EPSILON*WAYPOINT_HORIZONTAL_EPSILON
                && Math.abs(current.getY()-waypoint.getY())<=WAYPOINT_VERTICAL_EPSILON;
    }

    /** Approach a riser from the last clear position. Descents happen only after the body
     * clears the upper ledge. A diagonal corner that is too tight gets a short X/Z slide
     * before the whole Paper route is discarded. */
    private Location walkingStep(Travel travel,Location current,Location next,double allowance,long tick) {
        if (travel.riserWaypoint != next) {
            // The waypoint tolerance can advance while the feet still cross the old riser.
            // Keep its grounded fence projection until actual narrow support releases it.
            travel.riserWaypoint = next;
        }
        Location at=current.clone();
        int limit=(int)Math.ceil(allowance/.08)+20;
        for(int i=0;i<limit && allowance>1e-7;i++) {
            double dx=next.getX()-at.getX(),dz=next.getZ()-at.getZ(),horizontal=Math.hypot(dx,dz);
            Location candidate=null;
            if(horizontal<.0001) {
                // Never chase Paper's integer Y through empty air. At the waypoint centre,
                // follow the real collision support instead. This is what prevents 1/16-1/8
                // height blocks from producing a vertical oscillation/spin.
                double support=replaySupport(travel,at,next,0,0);
                if(!Double.isFinite(support))return lastClearStep(current,at);
                double dy=support-at.getY();
                if(Math.abs(dy)<=WAYPOINT_VERTICAL_EPSILON)break;
                if(dy>MAX_STEP_HEIGHT || dy < -MAX_DROP_HEIGHT)return lastClearStep(current,at);
                Location vertical=at.clone().add(0,Math.copySign(Math.min(Math.abs(dy),Math.min(.08,allowance)),dy),0);
                if(!walkable(travel,vertical,tick))return lastClearStep(current,at);
                candidate=vertical;
            } else {
                double ux=dx/horizontal,uz=dz/horizontal;
                double support=replaySupport(travel,at,next,ux,uz);
                if(!Double.isFinite(support))return lastClearStep(current,at);
                if(Double.isFinite(travel.riserHeight) && at.getY()>=travel.riserHeight-GROUND_EPSILON) {
                    // Full-body support may find the new ledge under the front shoulder
                    // before the feet transfer. Only the narrow feet probe releases the lift.
                    double footSupport=travel.terrain.supportHeight(at.getX(),at.getY(),at.getZ(),.02);
                    if(Double.isFinite(footSupport) && footSupport>=travel.riserHeight-GROUND_EPSILON) {
                        travel.riserHeight=Double.NaN;travel.riserBaseY=Double.NaN;
                    }
                }
                if (Double.isFinite(travel.dropHeight) && at.getY() <= travel.dropHeight + GROUND_EPSILON)
                    travel.dropHeight = Double.NaN;
                boolean crossingRiser = Double.isFinite(travel.riserHeight)
                        && support < travel.riserHeight - GROUND_EPSILON;
                boolean paperDescent = next.getY() < at.getY() - GROUND_EPSILON;
                double allowedDrop = paperDescent ? MAX_DROP_HEIGHT : MAX_UNANNOUNCED_DROP;

                // Follow the real floor for descents even when Paper compresses/omits the
                // intermediate Y node. Never settle while a staged ascent still owns the Y
                // axis: the old lower floor remains under the rear half of the body until the
                // NPC has actually transferred onto the higher surface.
                if(!crossingRiser && support<at.getY()-FLOOR_SETTLE_EPSILON
                        && at.getY()-support<=allowedDrop+GROUND_EPSILON) {
                    travel.dropHeight=support;
                    double fall=Math.min(at.getY()-support,Math.min(.08,allowance));
                    Location down=at.clone().add(0,-fall,0);
                    if(walkable(travel,down,tick))candidate=down;
                }

                if(candidate==null) {
                    // Detect the physical riser in front of the feet even when Paper keeps the
                    // same integer Y for consecutive waypoints. Partial-height surfaces are
                    // allowed without a higher native node; a complete block still requires
                    // Paper's vertical intent so obstacles beside/inside a flat route are not
                    // treated as stairs.
                    boolean paperRiser=next.getY()>at.getY()+GROUND_EPSILON;
                    double riseLimit=paperRiser?MAX_STEP_HEIGHT:MAX_UNANNOUNCED_RISER;
                    double radius=paperRiser?RISER_SUPPORT_RADIUS:UNANNOUNCED_SUPPORT_RADIUS;
                    // Lift only when the next short horizontal step brings the leading edge
                    // to a riser. A long look-ahead raises an NPC at the previous cell centre
                    // and can skip the lower tread of a mixed stair/slab entrance.
                    double probe=Math.min(horizontal,Math.min(.08,allowance)+RISER_BODY_LEAD);
                    double contact=travel.terrain.climbSupportHeight(at.getX()+ux*probe,at.getY(),at.getZ()+uz*probe,
                            riseLimit,radius);
                    if(Double.isFinite(contact) && contact>at.getY()+GROUND_EPSILON
                            && contact-at.getY()<=riseLimit+GROUND_EPSILON
                            && travel.terrain.recoveryHopFits(at.getX()+ux*probe,contact,at.getZ()+uz*probe,
                            Double.isFinite(travel.riserBaseY)?travel.riserBaseY:at.getY(),true)) {
                        Location up=at.clone().add(0,Math.min(contact-at.getY(),Math.min(.08,allowance)),0);
                        if(walkable(travel,up,tick)) {
                            candidate=up;
                            if(!Double.isFinite(travel.riserBaseY))travel.riserBaseY=at.getY();
                            travel.riserHeight = contact;
                        }
                    }
                }

                // A dirt path/mud/carpet can leave the feet a few centimetres above the real
                // support after Paper advances to the next waypoint. Settle locally, but if the
                // old ledge still intersects the body, keep walking and retry after it is clear.
                // Once lifted for a verified riser, transfer horizontally onto it before
                // settling. The old lower floor otherwise undoes every ascent in this loop.
                if(candidate==null && !crossingRiser && support<at.getY()-FLOOR_SETTLE_EPSILON
                        && at.getY()-support<=allowedDrop) {
                    Location down=at.clone().add(0,-Math.min(at.getY()-support,Math.min(.08,allowance)),0);
                    if(walkable(travel,down,tick))candidate=down;
                }

                if(candidate==null) {
                    double stride=Math.min(horizontal,Math.min(.08,allowance));
                    candidate=horizontalStep(travel,at,next,ux,uz,stride,tick);
                    if(candidate==null) {
                        // A compressed Paper route can omit the intermediate node for a
                        // slab/carpet/other partial block. The first failed horizontal substep
                        // tells us exactly where the body meets that riser. Probe just beyond
                        // the leading edge, then raise while still beside the obstacle; once
                        // the feet reach its top, ordinary horizontal replay continues.
                        double lead=Math.min(horizontal,stride+RISER_BODY_LEAD);
                        double contact=travel.terrain.climbSupportHeight(at.getX()+ux*lead,at.getY(),
                                at.getZ()+uz*lead,MAX_UNANNOUNCED_RISER,UNANNOUNCED_SUPPORT_RADIUS);
                        if(Double.isFinite(contact) && contact>at.getY()+GROUND_EPSILON
                                && contact-at.getY()<=MAX_UNANNOUNCED_RISER+GROUND_EPSILON
                                && travel.terrain.recoveryHopFits(at.getX()+ux*lead,contact,at.getZ()+uz*lead,
                                Double.isFinite(travel.riserBaseY)?travel.riserBaseY:at.getY(),true)) {
                            Location up=at.clone().add(0,Math.min(contact-at.getY(),Math.min(.08,allowance)),0);
                            if(walkable(travel,up,tick)) {
                                candidate=up;
                                if(!Double.isFinite(travel.riserBaseY))travel.riserBaseY=at.getY();
                                travel.riserHeight=contact;
                            }
                        }
                    }
                    if(candidate==null)return lastClearStep(current,at);
                }
            }
            double moved=at.distance(candidate);
            if(moved<1e-8)break;
            allowance-=moved;at=candidate;
        }
        return at;
    }

    private static Location lastClearStep(Location current,Location at) {
        // A later blocked substep must not erase the valid motion already accumulated in
        // this update or replace it with a speculative lift from the original position.
        return current.distanceSquared(at)>MOVEMENT_EPSILON_SQUARED?at:null;
    }

    private double replaySupport(Travel travel,Location at,Location next,double ux,double uz) {
        double support=travel.terrain.supportHeight(at.getX(),at.getY(),at.getZ(),.02);
        if(Double.isFinite(support))return support;
        boolean descent=next.getY()<at.getY()-GROUND_EPSILON;
        boolean transferring=Double.isFinite(travel.riserHeight);
        // Rear-body contact must not let a flat compressed route enter a deep pit. Only a
        // native lower waypoint or a verified staged lift owns this larger vertical gap.
        if(!descent && !transferring)return Double.NaN;
        support=travel.terrain.safeSupportBelow(at.getX(),at.getY(),at.getZ(),MAX_DROP_HEIGHT);
        if(Double.isFinite(support) || !descent || Math.abs(ux)+Math.abs(uz)<1e-7)return support;
        // At the exact ledge boundary the old block can still clip a lower landing by a
        // floating-point fraction. Validate the landing one substep ahead and keep crossing
        // horizontally; the downward body sweep will wait until the rear is actually clear.
        double ahead=Math.min(.08,Math.hypot(next.getX()-at.getX(),next.getZ()-at.getZ()));
        return travel.terrain.safeSupportBelow(at.getX()+ux*ahead,at.getY(),at.getZ()+uz*ahead,MAX_DROP_HEIGHT);
    }

    private Location horizontalStep(Travel travel,Location at,Location next,double ux,double uz,double stride,long tick) {
        double allowedDrop=next.getY()<at.getY()-GROUND_EPSILON?MAX_DROP_HEIGHT:MAX_UNANNOUNCED_DROP;
        boolean transferringRiser=Double.isFinite(travel.riserHeight)
                && at.getY()>=travel.riserHeight-GROUND_EPSILON;
        Location direct=at.clone().add(ux*stride,0,uz*stride);
        // For rises above one block the old floor is temporarily >1.01 below the raised feet.
        // Once the verified target height has been reached, allow the short horizontal transfer
        // onto that surface instead of mistaking the intentional step-up for unsupported air.
        double groundedBase=Double.isFinite(travel.riserBaseY)?travel.riserBaseY:at.getY();
        if(walkable(travel,direct,tick)
                && (!Double.isFinite(travel.riserBaseY)
                || travel.terrain.recoveryHopFits(direct.getX(),direct.getY(),direct.getZ(),groundedBase,false))
                && (supported(travel,direct,next,allowedDrop) || transferringRiser))return direct;

        if(Math.abs(ux)<1e-6 || Math.abs(uz)<1e-6)return null;

        // Paper can legally return a diagonal around a block corner that our exact 0.60-wide
        // replay touches by a few centimetres. Slide along one axis and retry the diagonal next tick.
        ArrayList<Location> axes=new ArrayList<>(2);
        Location x=at.clone().add(Math.copySign(Math.min(Math.abs(next.getX()-at.getX()),stride),ux),0,0);
        Location z=at.clone().add(0,0,Math.copySign(Math.min(Math.abs(next.getZ()-at.getZ()),stride),uz));
        if(walkable(travel,x,tick)
                && (!Double.isFinite(travel.riserBaseY)
                || travel.terrain.recoveryHopFits(x.getX(),x.getY(),x.getZ(),groundedBase,false))
                && (supported(travel,x,next,allowedDrop) || transferringRiser))axes.add(x);
        if(walkable(travel,z,tick)
                && (!Double.isFinite(travel.riserBaseY)
                || travel.terrain.recoveryHopFits(z.getX(),z.getY(),z.getZ(),groundedBase,false))
                && (supported(travel,z,next,allowedDrop) || transferringRiser))axes.add(z);
        if(axes.isEmpty())return null;
        axes.sort(Comparator.comparingDouble(next::distanceSquared));
        return axes.getFirst();
    }


    private boolean supported(Travel travel,Location at,Location next,double maximumDrop) {
        double dx=next.getX()-at.getX(),dz=next.getZ()-at.getZ(),horizontal=Math.hypot(dx,dz);
        double support=replaySupport(travel,at,next,horizontal>1e-7?dx/horizontal:0,horizontal>1e-7?dz/horizontal:0);
        if(!Double.isFinite(support) || at.getY()-support>maximumDrop+GROUND_EPSILON)return false;
        // Remember ownership even for small drops (slab -> block, carpet -> block, mud ledges).
        // RoutineGravity runs before the next navigation update and would otherwise interpret
        // that legal transition as accidental floor loss and cancel the route.
        if(!Double.isFinite(travel.riserHeight) && support<at.getY()-GROUND_EPSILON)
            travel.dropHeight=support;
        return true;
    }
    private boolean walkable(Travel travel,Location candidate,long tick) {
        // A cancelled opening must not fire again for another candidate in this update.
        // Keep earlier safe substeps, but return WAITING so the denial gets the local retry.
        if(travel.doorDeniedDuringStep)return false;
        if(!doors.openNear(travel.npc.entity(),candidate,tick)) {
            travel.doorDeniedDuringStep=true;return false;
        }
        return travel.terrain.fits(candidate.getX(),candidate.getY(),candidate.getZ(),false);
    }

    private Location prepareStart(Travel travel, Location current, long tick, double speed, int cadence) {
        Node start = travel.terrain.near(current);
        if (start == null) return null;
        double contact=travel.terrain.supportHeight(current.getX(),current.getY(),current.getZ(),.02);
        Location floor = current.clone();
        if(Double.isFinite(contact))floor.setY(contact);
        boolean validContact=Double.isFinite(contact)
                && Math.abs(contact-current.getY())<=1.01
                && doors.openNear(travel.npc.entity(),floor,tick)
                && travel.terrain.fits(floor.getX(),floor.getY(),floor.getZ(),false);
        if(!validContact) {
            // If a carpet/bottom slab/thin layer was placed under an existing NPC, the old
            // feet Y can sit a few pixels inside its new collision. A normal vertical sweep
            // cannot leave an already-overlapping shape. Recover only to a verified partial
            // support and only by at most half a block; full cubes remain deliberately blocked.
            double shallow=travel.terrain.supportHeightAtNode(start,current.getX(),current.getZ()), lift=shallow-current.getY();
            if(lift>GROUND_EPSILON && lift<=.51
                    && travel.terrain.partialSupport(start,current.getX(),current.getZ())) {
                Location recovered=current.clone();recovered.setY(shallow);
                if(doors.openNear(travel.npc.entity(),recovered,tick)
                        && travel.terrain.fits(recovered.getX(),recovered.getY(),recovered.getZ(),false)
                        && travel.terrain.fitsPartialEscape(current.getX(),current.getY(),current.getZ(),start)) {
                    double allowance=Math.max(.01,speed)*Math.max(1,cadence)/20.0;
                    recovered.setY(current.getY()+Math.min(lift,allowance));
                    if(travel.terrain.fitsPartialEscape(recovered.getX(),recovered.getY(),recovered.getZ(),start))
                        return applyStep(travel,current,recovered)?travel.npc.entity().getLocation():null;
                }
            }
            return null;
        }
        double dy=floor.getY()-current.getY();
        if(Math.abs(dy)<=.01)return current;
        double distance=Math.max(.01,speed)*Math.max(1,cadence)/20.0;
        Location step=current.clone().add(0,Math.copySign(Math.min(Math.abs(dy),distance),dy),0);
        int samples=Math.max(1,(int)Math.ceil(Math.abs(step.getY()-current.getY())/.16));
        for(int i=1;i<=samples;i++) {
            double y=current.getY()+(step.getY()-current.getY())*i/samples;
            if(!travel.terrain.fits(current.getX(),y,current.getZ(),false))return null;
        }
        return applyStep(travel,current,step)?travel.npc.entity().getLocation():null;
    }

    private SearchOutcome findPaperPath(Travel travel, Location current) {
        Pathfinder pathfinder = travel.npc.entity().getPathfinder();
        pathfinder.setCanOpenDoors(!travel.avoidClosedDoors);
        pathfinder.setCanPassDoors(true);
        pathfinder.setCanFloat(false);
        AttributeInstance range = travel.npc.entity().getAttribute(Attribute.FOLLOW_RANGE);
        AttributeInstance stepHeight = travel.npc.entity().getAttribute(Attribute.STEP_HEIGHT);
        AttributeInstance safeFall = travel.npc.entity().getAttribute(Attribute.SAFE_FALL_DISTANCE);
        int requestedRange=PAPER_SEARCH_RANGES[Math.max(0,Math.min(PAPER_SEARCH_RANGES.length-1,travel.searchLevel))];
        double priorRange = range == null ? requestedRange : range.getBaseValue();
        double priorStep = stepHeight == null ? MAX_CLIMB_HEIGHT : stepHeight.getBaseValue();
        double priorFall = safeFall == null ? MAX_DROP_HEIGHT : safeFall.getBaseValue();
        Pathfinder.PathResult result;
        try {
            if (range != null) range.setBaseValue(requestedRange);
            // Let the native Paper search plan the same terrain transitions that our controlled
            // replay supports. Values are restored immediately after findPath, so gameplay stats
            // are not permanently modified.
            if (stepHeight != null) stepHeight.setBaseValue(MAX_CLIMB_HEIGHT);
            if (safeFall != null) safeFall.setBaseValue(MAX_DROP_HEIGHT);
            // Paper's synchronous navigator works from currently available world data. Do not
            // reject the whole query merely because an unrelated neighboring chunk is unloaded.
            // The replay destination uses the exact physical feet height (for example
            // 63.875 on soul sand), but Paper pathfinding targets integer path cells. Feeding
            // the physical Y directly makes floor(Y) point at the slab/carpet/mud block itself.
            // Resolve the destination back to its walkable node before asking Paper for a path.
            Node destinationNode=travel.terrain.near(travel.destination);
            Location nativeDestination=travel.destination.clone();
            if(destinationNode!=null) {
                nativeDestination=new Location(travel.destination.getWorld(),destinationNode.x()+.5,
                        destinationNode.y(),destinationNode.z()+.5,
                        travel.destination.getYaw(),travel.destination.getPitch());
            }
            try (GroundFlag ignored = NativeGroundFlag.open(travel.npc.entity())) {
                result = pathfinder.findPath(nativeDestination);
            }
        } finally {
            if (range != null) range.setBaseValue(priorRange);
            if (stepHeight != null) stepHeight.setBaseValue(priorStep);
            if (safeFall != null) safeFall.setBaseValue(priorFall);
        }
        if (result == null) return new SearchOutcome(null,SearchFailure.NO_ROUTE);
        List<Location> points = result.getPoints();
        if (points == null || points.isEmpty() || points.size() > MAX_PATH_POINTS)
            return new SearchOutcome(null,SearchFailure.NO_ROUTE);
        ArrayList<Location> path = new ArrayList<>(points.size() + 1);
        for (Location raw : points) {
            if (raw == null || raw.getWorld() != current.getWorld()) return new SearchOutcome(null,SearchFailure.NO_ROUTE);
            if (!travel.terrain.loaded(raw.getBlockX(), raw.getBlockZ())) return new SearchOutcome(null,SearchFailure.CHUNK);
            Location centered = new Location(current.getWorld(), raw.getBlockX() + .5, raw.getY(), raw.getBlockZ() + .5);
            Node point = travel.terrain.near(centered);
            if (point == null) return new SearchOutcome(null,deniedClosedDoor(travel,centered)
                    ? SearchFailure.DENIED_DOOR : SearchFailure.NO_ROUTE);
            Location waypoint = travel.terrain.location(point);
            if (path.isEmpty() || path.getLast().distanceSquared(waypoint) > .0001) path.add(waypoint);
        }
        if(path.isEmpty())return new SearchOutcome(null,SearchFailure.NO_ROUTE);
        Location last = path.getLast();
        boolean exactBlock=last.getBlockX() == travel.destination.getBlockX() && last.getBlockZ() == travel.destination.getBlockZ()
                && Math.abs(last.getY() - travel.destination.getY()) < .12;
        boolean reachesFinal=result.canReachFinalPoint() || exactBlock;
        if (exactBlock && last.distanceSquared(travel.destination) > .0001) {
            path.add(travel.destination.clone()); last=path.getLast();
        }
        return new SearchOutcome(new PathPlan(List.copyOf(path),reachesFinal,last.clone()),SearchFailure.NONE);
    }

    /** Discovery and every local A* connector consume shared per-update allowances. */
    private PathPlan findNodePath(Travel travel,Location current) {
        if(travel.nodeScan==null)
            travel.nodeScan=nodeIndex.discover(current.getWorld(),current,travel.destination,nodeScanMargin,nodeMaxDistance);
        if(!travel.nodeScan.done()) {
            if(nodeBlocksLeft<=0)return null;
            nodeBlocksLeft-=travel.nodeScan.advance(nodeBlocksLeft);
            if(!travel.nodeScan.done())return null;
        }
        if(travel.nodeRouter==null) {
            // An incomplete scan must not create misleading links through a partial network.
            if(travel.nodeScan.truncated()) {travel.nodesTried=true;return null;}
            List<Node> markers=new ArrayList<>();
            for(Node raw:travel.nodeScan.result()) {
                if(!isMarker(travel,raw))continue;
                Node surface=travel.terrain.near(new Location(current.getWorld(),raw.x()+.5,raw.y(),raw.z()+.5));
                if(surface!=null && surface.y()>=raw.y() && surface.y()<=raw.y()+1) {
                    markers.add(surface);travel.markerSources.put(surface,raw);
                }
            }
            Node start=travel.terrain.near(current), goal=travel.terrain.near(travel.destination);
            if(markers.isEmpty() || start==null || goal==null) {travel.nodesTried=true;return null;}
            BoundedPathfinder.Grid grid=new BoundedPathfinder.Grid() {
                public boolean stand(Node n) {return travel.terrain.stand(n);}
                public boolean edge(Node a,Node b) {
                    double from=travel.terrain.height(a),to=travel.terrain.height(b),delta=to-from;
                    return delta<=MAX_CLIMB_HEIGHT+1e-7 && delta>=-MAX_DROP_HEIGHT-1e-7
                            && travel.terrain.edge(a,b)
                            && (delta<=0 || travel.terrain.fenceClearAtBase(b.x()+.5,from,b.z()+.5));
                }
            };
            travel.nodeRouter=new NetheriteNodeRouter(markers,grid,start,goal,nodeLinkRange,nodeEndpointRange,2048);
        }
        if(!travel.nodeRouter.done()) {
            if(nodeSearchLeft<=0)return null;
            long started=System.nanoTime();
            long allowance=Math.max(1,maximumNanos-spentNanos);
            do {
                nodeSearchLeft-=travel.nodeRouter.advance(Math.min(16,nodeSearchLeft));
            } while(!travel.nodeRouter.done() && nodeSearchLeft>0 && System.nanoTime()-started<allowance);
            if(!travel.nodeRouter.done())return null;
        }
        travel.nodesTried=true;
        List<Node> route=travel.nodeRouter.result();
        if(route==null || route.isEmpty() || route.size()>MAX_PATH_POINTS)return null;
        for(Node marker:travel.nodeRouter.markers()) {
            Node raw=travel.markerSources.get(marker);
            if(raw==null || !isMarker(travel,raw))return null;
        }
        List<Location> points=new ArrayList<>();
        for(Node n:route) {
            if(!travel.terrain.stand(n))return null;
            points.add(travel.terrain.location(n));
        }
        Location last=points.getLast();
        if(Math.abs(last.getY()-travel.destination.getY())>.12)return null;
        if(last.distanceSquared(travel.destination)>.0001)points.add(travel.destination.clone());
        travel.routeMarkers=travel.nodeRouter.markers();travel.markerIndex=0;travel.nodePath=true;
        return new PathPlan(List.copyOf(points),true,points.getLast().clone());
    }
    private boolean isMarker(Travel travel,Node raw) {
        Block block=travel.terrain.block(raw.x(),raw.y()-2,raw.z());
        Block cover=travel.terrain.block(raw.x(),raw.y()-1,raw.z());
        return block!=null && block.getType()==org.bukkit.Material.NETHERITE_BLOCK
                && RoutineTerrain.walkingSurface(cover);
    }
    private boolean validNextMarker(Travel travel,Location current) {
        while(travel.markerIndex<travel.routeMarkers.size()) {
            Node marker=travel.routeMarkers.get(travel.markerIndex);
            Node raw=travel.markerSources.get(marker);
            if(raw==null || !isMarker(travel,raw)) {
                if(raw!=null)nodeIndex.invalidate(current.getWorld(),raw.x(),raw.z());
                return false;
            }
            if(current.distanceSquared(travel.terrain.location(marker))>.25)return true;
            travel.markerIndex++;
        }
        return true;
    }
    private void resetNodePlanning(Travel travel) {
        travel.nodeScan=null;travel.nodeRouter=null;travel.nodesTried=false;travel.nodePath=false;
        travel.routeMarkers=List.of();travel.markerSources.clear();travel.markerIndex=0;
    }

    /** Only a denied closed panel changes Paper's door policy; other bad terrain does not. */
    private boolean deniedClosedDoor(Travel travel, Location feet) {
        boolean denied = false;
        for (int x=(int)Math.floor(feet.getX()-.30);x<=(int)Math.floor(feet.getX()+.30);x++)
            for (int z=(int)Math.floor(feet.getZ()-.30);z<=(int)Math.floor(feet.getZ()+.30);z++)
                for (int y=(int)Math.floor(feet.getY()+.015);y<=(int)Math.floor(feet.getY()+1.95);y++) {
                    Block block=travel.terrain.block(x,y,z);
                    if(block==null)return false;
                    if(block.getBlockData() instanceof Door door && !door.isOpen() && !doors.canOpen(block)) denied=true;
                }
        return denied;
    }

    private void avoidClosedDoors(Travel travel,long tick,int cadence) {
        travel.path=null;travel.pending=true;travel.pathReachesFinal=false;
        travel.riserWaypoint=null;travel.riserHeight=Double.NaN;travel.riserBaseY=Double.NaN;travel.dropHeight=Double.NaN;
        travel.avoidClosedDoors=true;travel.exhausted=false;
        // The alternate policy gets its own shared-budget query on a later update.
        travel.retry=tick+Math.max(1,cadence);
    }

    private void tuneNextSearch(Travel travel,Location start,PathPlan plan) {
        if(plan.reachesFinal()) {
            travel.partialEndpoints.clear(); travel.segmentsWithoutGoalProgress = 0;
            return;
        }
        double before=start.distanceSquared(travel.destination);
        double after=plan.endpoint().distanceSquared(travel.destination);
        boolean detour=after>=before-.25;
        boolean floorMismatch=verticalShadow(plan.endpoint(),travel.destination)
                && !usefulFloorProgress(start,plan.endpoint(),travel.destination);
        if((detour||floorMismatch||travel.segmentsWithoutGoalProgress>=2) && travel.searchLevel<travel.maxSearchLevel) {
            travel.searchLevel++;
            if(floorMismatch)travel.minimumSearchLevel=Math.max(travel.minimumSearchLevel,travel.searchLevel);
        } else if(!detour && !floorMismatch && before-after>16 && travel.searchLevel>travel.minimumSearchLevel) {
            travel.searchLevel--;
        }
    }

    private static boolean verticalShadow(Location endpoint,Location destination) {
        if(endpoint==null || destination==null || endpoint.getWorld()!=destination.getWorld())return false;
        double dx=endpoint.getX()-destination.getX(),dz=endpoint.getZ()-destination.getZ();
        return dx*dx+dz*dz<=VERTICAL_SHADOW_HORIZONTAL_SQUARED
                && Math.abs(endpoint.getY()-destination.getY())>=VERTICAL_SHADOW_MIN_Y;
    }

    /** A partial stair segment can lie directly below the goal while genuinely climbing
     * toward it. Keep that inexpensive segment; only a stationary floor shadow widens. */
    private static boolean usefulFloorProgress(Location start,Location endpoint,Location destination) {
        return Math.abs(start.getY()-destination.getY())-Math.abs(endpoint.getY()-destination.getY())>GROUND_EPSILON
                && start.distance(destination)-endpoint.distance(destination)>=GOAL_PROGRESS_DISTANCE;
    }

    private void noRouteFailure(Travel travel,long tick) {
        travel.path=null;travel.pending=true;travel.pathReachesFinal=false;
        travel.riserWaypoint=null;travel.riserHeight=Double.NaN;travel.riserBaseY=Double.NaN;travel.dropHeight=Double.NaN;
        if(travel.searchLevel<travel.maxSearchLevel) {
            travel.searchLevel++;travel.retry=tick+LOCAL_RETRY_TICKS;travel.exhausted=false;
        } else {
            travel.exhausted=true;travel.retry=tick+IMPOSSIBLE_RETRY_TICKS;
            resetNodePlanning(travel);
            travel.avoidClosedDoors=false;
            travel.riserWaypoint=null;travel.riserHeight=Double.NaN;travel.riserBaseY=Double.NaN;travel.dropHeight=Double.NaN;
        }
    }
    private void localFailure(Travel travel,long tick) {
        if(travel.nodePath)resetNodePlanning(travel);
        travel.path=null;travel.pending=true;travel.pathReachesFinal=false;travel.localFailures++;
        // Once a route is discarded it no longer owns an in-air Y transition. Releasing the
        // marker lets RoutineGravity settle the NPC instead of leaving it hovering/spinning.
        travel.riserWaypoint=null;travel.riserHeight=Double.NaN;travel.riserBaseY=Double.NaN;travel.dropHeight=Double.NaN;
        if(travel.localFailures>=2 && travel.searchLevel<travel.maxSearchLevel) {
            travel.searchLevel++;travel.localFailures=0;
        } else if(travel.localFailures>=3 && travel.searchLevel>=travel.maxSearchLevel) {
            travel.exhausted=true;
            travel.avoidClosedDoors=false;
            travel.riserWaypoint=null;travel.riserHeight=Double.NaN;travel.riserBaseY=Double.NaN;travel.dropHeight=Double.NaN;
        }
        travel.retry=tick+(travel.exhausted?IMPOSSIBLE_RETRY_TICKS:LOCAL_RETRY_TICKS);
    }
    private void chunkFailure(Travel travel,long tick) {
        travel.path=null;travel.pending=true;travel.pathReachesFinal=false;
        travel.riserWaypoint=null;travel.riserHeight=Double.NaN;travel.riserBaseY=Double.NaN;travel.dropHeight=Double.NaN;
        travel.retry=tick+CHUNK_RETRY_TICKS;
    }

    public boolean exhausted(String npc) {
        Travel travel=travels.get(npc);return travel!=null && travel.exhausted;
    }
    /** Computing an incremental graph is deliberate waiting, not a physically stuck walk. */
    public boolean planningNodes(String npc) {
        Travel travel=travels.get(npc);return nodesEnabled && travel!=null && !travel.nodesTried;
    }
    /** A staged lift remains owned by route replay during its short local retry. */
    public boolean controlsVerticalStep(String npc) {
        Travel travel=travels.get(npc);
        return activeHop(npc) || travel!=null && (Double.isFinite(travel.riserHeight) || Double.isFinite(travel.dropHeight));
    }
    public void cancel(String npc) { travels.remove(npc);cancelHop(npc); }
    public void clear() {
        travels.clear();hops.clear();hopFailures.clear(); budgetTick = Long.MIN_VALUE;
        nodeIndex.clear();
    }

    /**
     * Paper 1.21.6 uses Mojang names at runtime: CraftEntity.getHandle and Entity.setOnGround.
     * Reflection is resolved once per implementation class; it never enables AI or vanilla goals.
     */
    private static final class NativeGroundFlag {
        private static final ClassValue<Optional<Method>> HANDLES = methods("getHandle");
        private static final ClassValue<Optional<Method>> SETTERS = methods("setOnGround", boolean.class);
        private static ClassValue<Optional<Method>> methods(String name, Class<?>... arguments) {
            return new ClassValue<>() {
                @Override protected Optional<Method> computeValue(Class<?> type) {
                    try { return Optional.of(type.getMethod(name, arguments)); }
                    catch (NoSuchMethodException ex) { return Optional.empty(); }
                }
            };
        }
        static GroundFlag open(Entity entity) {
            if (entity.isOnGround()) return GroundFlag.UNCHANGED;
            Method getHandle = HANDLES.get(entity.getClass()).orElseThrow(() -> unsupported(entity.getClass()));
            Object handle = call(getHandle, entity);
            if (handle == null) throw unsupported(entity.getClass());
            Method setter = SETTERS.get(handle.getClass()).orElseThrow(() -> unsupported(handle.getClass()));
            call(setter, handle, true);
            return new GroundFlag(setter, handle);
        }
        private static IllegalStateException unsupported(Class<?> type) {
            return new IllegalStateException("No se pudo preparar la navegación Paper 1.21.6: " + type.getName()
                    + " no expone getHandle/setOnGround; la rutina se pausa para conservar la IA desactivada.");
        }
        static Object call(Method method, Object target, Object... arguments) {
            try { return method.invoke(target, arguments); }
            catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("No se pudo ajustar/restaurar onGround durante la búsqueda Paper", ex);
            }
        }
    }
    private record GroundFlag(Method setter, Object handle) implements AutoCloseable {
        private static final GroundFlag UNCHANGED = new GroundFlag(null, null);
        @Override public void close() { if (setter != null) NativeGroundFlag.call(setter, handle, false); }
    }
    public int activeRoutes() { return (int)travels.values().stream().filter(t -> t.path != null).count(); }
    public int searches() { return (int)travels.values().stream().filter(t -> t.pending).count(); }
    public int cachedRoutes() { return 0; }
}
