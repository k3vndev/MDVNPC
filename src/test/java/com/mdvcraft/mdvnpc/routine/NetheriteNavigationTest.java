package com.mdvcraft.mdvnpc.routine;

import com.destroystokyo.paper.entity.Pathfinder;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real marker discovery, graph planning and collision replay; native Paper search is mocked. */
class NetheriteNavigationTest {
    private record Cell(int x, int y, int z) {}
    private final Map<Cell, Material> overrides = new HashMap<>();
    private final Map<Material, Block> blocks = new EnumMap<>(Material.class);
    private final List<Location> visited = new ArrayList<>();
    private World world;
    private Chunk chunk;
    private ChunkSnapshot snapshot;
    private Pathfinder finder;
    private RoutineNavigator navigator;
    private ActiveNpc npc;
    private Location position;
    private long tick;
    private boolean elevated;

    @BeforeEach void setup() {
        MockBukkit.mock();
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getMinHeight()).thenReturn(60);
        when(world.getMaxHeight()).thenReturn(72);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenAnswer(call ->
                (int) call.getArgument(0) == 0 && (int) call.getArgument(1) == 0);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            int x = call.getArgument(0), y = call.getArgument(1), z = call.getArgument(2);
            assertTrue(x >= 0 && x < 16 && z >= 0 && z < 16, "never read an unloaded chunk");
            return block(type(x, y, z));
        });
        snapshot = mock(ChunkSnapshot.class);
        // Keep the bounded scanner exercised even in the no-marker fixture.
        when(snapshot.contains(any(org.bukkit.block.data.BlockData.class))).thenReturn(true);
        when(snapshot.getBlockType(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                type(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(0);
        when(chunk.getZ()).thenReturn(0);
        when(chunk.getChunkSnapshot()).thenReturn(snapshot);
        when(chunk.getChunkSnapshot(anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(snapshot);
        when(world.getChunkAt(anyInt(), anyInt())).thenAnswer(call -> {
            assertEquals(0, (int) call.getArgument(0), "must not load another chunk");
            assertEquals(0, (int) call.getArgument(1), "must not load another chunk");
            return chunk;
        });
        when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunk});
        DoorController doors = mock(DoorController.class);
        when(doors.openNear(any(), any(), anyLong())).thenReturn(true);
        navigator = new RoutineNavigator(doors, (active, to) -> {
            position = to.clone();
            visited.add(to.clone());
            return true;
        });
        finder = mock(Pathfinder.class);
        when(finder.findPath(any(Location.class))).thenAnswer(call -> nativeReply(call.getArgument(0)));
        Villager entity = mock(Villager.class);
        when(entity.getWorld()).thenReturn(world);
        when(entity.isValid()).thenReturn(true);
        when(entity.isOnGround()).thenReturn(true);
        when(entity.getPathfinder()).thenReturn(finder);
        when(entity.getLocation()).thenAnswer(call -> position.clone());
        position = at(1, 64, 2);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("npcs.markers.location.world", "world");
        yaml.set("npcs.markers.location.y", 64);
        npc = new ActiveNpc(NpcParser.parse(yaml).get("markers"), position.clone(), entity, null);
    }

    @AfterEach void cleanup() {
        navigator.clear();
        MockBukkit.unmock();
    }

    private Material type(int x, int y, int z) {
        return overrides.getOrDefault(new Cell(x, y, z),
                y == 63 || elevated && x >= 5 && y == 64 || elevated && x >= 8 && y == 65
                        ? Material.STONE : Material.AIR);
    }

    private Block block(Material material) {
        return blocks.computeIfAbsent(material, key -> {
            Block block = mock(Block.class);
            when(block.getType()).thenReturn(key);
            when(block.isPassable()).thenReturn(key.isAir());
            VoxelShape shape = mock(VoxelShape.class);
            double height = key == Material.STONE_SLAB ? .5 : key == Material.WHITE_CARPET ? .0625 : 1;
            when(shape.getBoundingBoxes()).thenReturn(key.isAir() ? List.of()
                    : List.of(new BoundingBox(0, 0, 0, 1, height, 1)));
            when(block.getCollisionShape()).thenReturn(shape);
            return block;
        });
    }

    private Location at(int x, int y, int z) { return new Location(world, x + .5, y, z + .5); }
    private void marker(int x, int y, int z) { overrides.put(new Cell(x, y, z), Material.NETHERITE_BLOCK); }
    private void configure(int link, int endpoint, int blocksPerTick) {
        navigator.configureNodes(true, link, endpoint, 6, 32, blocksPerTick, 512);
    }

    /** Fallback path fixture follows grounded cells; it does not simulate Paper's evaluator. */
    private Pathfinder.PathResult nativeReply(Location target) {
        List<Location> points = new ArrayList<>();
        int x = position.getBlockX(), z = position.getBlockZ();
        points.add(at(x, floor(x), z));
        while (x != target.getBlockX()) {
            x += Integer.signum(target.getBlockX() - x);
            points.add(at(x, floor(x), z));
        }
        while (z != target.getBlockZ()) {
            z += Integer.signum(target.getBlockZ() - z);
            points.add(at(x, floor(x), z));
        }
        Pathfinder.PathResult result = mock(Pathfinder.PathResult.class);
        when(result.getPoints()).thenReturn(points);
        when(result.canReachFinalPoint()).thenReturn(true);
        return result;
    }

    private int floor(int x) { return elevated ? x >= 8 ? 66 : x >= 5 ? 65 : 64 : 64; }

    private RoutineNavigator.Result step(Location goal) {
        Location before = position.clone();
        navigator.beginTick(tick, 2, Long.MAX_VALUE);
        RoutineNavigator.Result result = navigator.move(npc, goal, 4, tick, 2);
        tick += 2;
        assertTrue(before.distance(position) <= .400001, "marker routes obey the same walking speed");
        if (result == RoutineNavigator.Result.ARRIVED) {
            assertTrue(position.distance(goal) <= .150001, "intermediate markers are not final arrival");
            assertEquals(goal.getY(), position.getY(), .0001, "arrival preserves the destination floor");
        }
        return result;
    }

    private void walk(Location goal) {
        for (int i = 0; i < 800; i++) if (step(goal) == RoutineNavigator.Result.ARRIVED) return;
        fail("route did not finish; final position=" + position);
    }

    private boolean visited(Location point) {
        return visited.stream().anyMatch(p -> p.distance(point) <= .16);
    }

    @Test void hiddenMarkersGuideTheTripBeforeNativeFallbackAndOnlyFinalGoalCountsAsArrival() {
        configure(5, 4, 8192);
        marker(3, 62, 5);
        marker(7, 62, 5);
        marker(10, 62, 5);
        Location goal = at(10, 64, 2);
        walk(goal);
        assertTrue(visited(at(3, 64, 5)), "visit the node above its cover floor");
        assertTrue(visited(at(7, 64, 5)), "follow the connected marker chain");
        assertTrue(visited(at(10, 64, 5)), "leave the graph through a node near the goal");
        verify(finder, never()).findPath(any(Location.class));
        assertEquals(0, navigator.activeRoutes());
        assertEquals(0, navigator.searches());
    }

    @Test void inaccessibleNearestMarkerDoesNotHideAReachableEntryAndDetour() {
        position = at(1, 64, 1);
        configure(6, 5, 8192);
        marker(3, 62, 1);
        for (int[] wall : new int[][]{{2, 1}, {4, 1}, {3, 0}, {3, 2}})
            for (int y = 64; y <= 66; y++)
                overrides.put(new Cell(wall[0], y, wall[1]), Material.STONE);
        marker(1, 62, 5);
        marker(7, 62, 5);
        marker(11, 62, 5);
        Location goal = at(11, 64, 1);
        walk(goal);
        assertFalse(visited(at(3, 64, 1)), "an enclosed nearest marker is not a valid entry");
        assertTrue(visited(at(1, 64, 5)), "choose a farther reachable entry even when it starts a detour");
        assertTrue(visited(at(7, 64, 5)));
        verify(finder, never()).findPath(any(Location.class));
    }

    @Test void nodesOnHigherFloorsFollowWalkableClimbsAndKeepTheActualGoalHeight() {
        elevated = true;
        position = at(1, 64, 1);
        configure(4, 4, 8192);
        marker(3, 62, 3);
        marker(6, 63, 3);
        marker(9, 64, 3);
        Location goal = at(12, 66, 1);
        walk(goal);
        assertTrue(visited(at(6, 65, 3)), "the second marker is one floor block higher");
        assertTrue(visited(at(9, 66, 3)), "a second legal climb reaches the top marker");
        assertEquals(66, position.getY(), .0001);
        verify(finder, never()).findPath(any(Location.class));
    }

    @Test void absentMarkersFinishABoundedScanThenUseTheExistingNativeNavigator() {
        configure(5, 4, 128);
        Location goal = at(10, 64, 2);
        assertEquals(RoutineNavigator.Result.WAITING, step(goal));
        verify(finder, never()).findPath(any(Location.class));
        walk(goal);
        verify(finder, times(1)).findPath(any(Location.class));
    }

    @Test void disabledMarkerRoutingDoesNotReadChunkSnapshots() {
        navigator.configureNodes(false, 5, 4, 6, 32, 128, 512);
        marker(3, 62, 5);
        walk(at(10, 64, 2));
        verify(world, never()).getChunkAt(anyInt(), anyInt());
        verify(chunk, never()).getChunkSnapshot();
        verify(chunk, never()).getChunkSnapshot(anyBoolean(), anyBoolean(), anyBoolean());
        verify(finder, times(1)).findPath(any(Location.class));
    }

    @Test void cancellationDropsAnUnfinishedMarkerScanAndAllTravelState() {
        configure(5, 4, 64);
        marker(3, 62, 5);
        assertEquals(RoutineNavigator.Result.WAITING, step(at(10, 64, 2)));
        navigator.cancel(npc.definition().id());
        assertEquals(0, navigator.searches());
        assertEquals(0, navigator.activeRoutes());
        assertEquals(RoutineNavigator.Result.ARRIVED, step(position.clone()));
        verify(finder, never()).findPath(any(Location.class));
    }

    @Test void deletingTheNextMarkerWhileWalkingDiscardsTheOldNetworkAndStillReachesTheGoal() {
        configure(5, 4, 8192);
        marker(3, 62, 5);
        marker(7, 62, 5);
        marker(10, 62, 5);
        Location goal = at(10, 64, 2);
        for (int i = 0; i < 200 && !visited(at(3, 64, 5)); i++) step(goal);
        assertTrue(visited(at(3, 64, 5)), "the network route must already be in progress");
        verify(finder, never()).findPath(any(Location.class));

        overrides.put(new Cell(7, 62, 5), Material.AIR);
        navigator.invalidateNodes(world, 7, 5);
        walk(goal);

        verify(finder, atLeastOnce()).findPath(any(Location.class));
        assertEquals(0, navigator.activeRoutes());
        assertEquals(0, navigator.searches());
    }

    @Test void slabCoverAndCarpetOverlayResolveToTheirPhysicalWalkingHeights() {
        configure(5, 4, 8192);
        marker(3, 62, 5);
        overrides.put(new Cell(3, 63, 5), Material.STONE_SLAB);
        marker(7, 62, 5);
        overrides.put(new Cell(7, 64, 5), Material.WHITE_CARPET);
        marker(10, 62, 5);
        walk(at(10, 64, 2));

        assertTrue(visited(new Location(world, 3.5, 63.5, 5.5)), "slab cover has a half-block surface");
        assertTrue(visited(new Location(world, 7.5, 64.0625, 5.5)), "carpet overlay raises the actual feet");
        verify(finder, never()).findPath(any(Location.class));
    }

    @Test void twoDeparturesShareTheSnapshotReadBudgetWithinTheSameUpdate() {
        configure(5, 4, 256);
        marker(3, 62, 5);
        ActiveNpc second = stationaryNpc("second", at(1, 64, 3), mock(Pathfinder.class));
        navigator.beginTick(0, 2, Long.MAX_VALUE);
        assertEquals(RoutineNavigator.Result.WAITING, navigator.move(npc, at(10, 64, 2), 4, 0, 2));
        int afterFirst = (int) mockingDetails(snapshot).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("getBlockType")).count();
        assertTrue(afterFirst > 0 && afterFirst <= 256);
        assertEquals(RoutineNavigator.Result.WAITING, navigator.move(second, at(10, 64, 3), 4, 0, 2));
        // A repeated beginTick for the same timestamp must not refill either allowance.
        navigator.beginTick(0, 2, Long.MAX_VALUE);
        navigator.move(second, at(10, 64, 3), 4, 0, 2);
        verify(snapshot, times(afterFirst)).getBlockType(anyInt(), anyInt(), anyInt());
        verify(finder, never()).findPath(any(Location.class));
        assertEquals(2, navigator.searches());
    }

    @Test void twoDeparturesAlsoShareTheNativeSearchStartAllowanceAfterDiscovery() {
        configure(5, 4, 256);
        Pathfinder secondFinder = mock(Pathfinder.class);
        ActiveNpc second = stationaryNpc("second", at(1, 64, 3), secondFinder);
        for (int i = 0; i < 80; i++) {
            navigator.beginTick(tick, 1, Long.MAX_VALUE);
            navigator.move(npc, at(10, 64, 2), 4, tick, 2);
            navigator.move(second, at(10, 64, 3), 4, tick, 2);
            tick += 2;
            boolean searched = mockingDetails(finder).getInvocations().stream()
                    .anyMatch(call -> call.getMethod().getName().equals("findPath"));
            if (searched) {
                verify(finder, times(1)).findPath(any(Location.class));
                verify(secondFinder, never()).findPath(any(Location.class));
                return;
            }
        }
        fail("the first bounded discovery must finish and perform its native fallback");
    }

    private ActiveNpc stationaryNpc(String id, Location start, Pathfinder pathfinder) {
        Villager entity = mock(Villager.class);
        when(entity.getWorld()).thenReturn(world);
        when(entity.isValid()).thenReturn(true);
        when(entity.isOnGround()).thenReturn(true);
        when(entity.getPathfinder()).thenReturn(pathfinder);
        when(entity.getLocation()).thenAnswer(call -> start.clone());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("npcs." + id + ".location.world", "world");
        yaml.set("npcs." + id + ".location.y", start.getY());
        return new ActiveNpc(NpcParser.parse(yaml).get(id), start.clone(), entity, null);
    }
}
