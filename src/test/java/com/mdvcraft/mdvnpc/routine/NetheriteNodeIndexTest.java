package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Node;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NetheriteNodeIndexTest {
    private World world;
    private ChunkSnapshot snapshot;
    private final Set<Node> buried = new HashSet<>();
    private NetheriteNodeIndex index;
    private Location start, goal;

    @BeforeEach void setup() {
        MockBukkit.mock();
        world = mock(World.class); Chunk chunk = mock(Chunk.class); snapshot = mock(ChunkSnapshot.class);
        when(world.getMinHeight()).thenReturn(0); when(world.getMaxHeight()).thenReturn(16);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        when(world.getChunkAt(0, 0)).thenReturn(chunk);
        when(chunk.getChunkSnapshot(false, false, false)).thenReturn(snapshot);
        when(snapshot.contains(any(BlockData.class))).thenAnswer(call -> !buried.isEmpty());
        when(snapshot.getBlockType(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                buried.contains(new Node(call.getArgument(0), call.getArgument(1), call.getArgument(2)))
                        ? Material.NETHERITE_BLOCK : Material.AIR);
        start = new Location(world, 1.5, 8, 1.5); goal = new Location(world, 10.5, 8, 10.5);
        index = new NetheriteNodeIndex();
    }
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    private static void finish(NetheriteNodeIndex.Scan scan) {
        for (int i = 0; i < 10_000 && !scan.done(); i++) assertTrue(scan.advance(47) <= 47);
        assertTrue(scan.done());
    }

    @Test void markerIsTwoRowsBelowFeetAndDifferentFloorsAreDiscovered() {
        buried.add(new Node(2, 3, 2)); buried.add(new Node(2, 10, 2));
        NetheriteNodeIndex.Scan scan = index.discover(world, start, goal, 0, 256);
        assertEquals(3, scan.advance(3));
        assertFalse(scan.done()); assertTrue(scan.result().isEmpty());
        finish(scan);
        assertEquals(Set.of(new Node(2, 5, 2), new Node(2, 12, 2)), new HashSet<>(scan.result()));
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    @Test void emptyChunksSkipAllBlockReads() {
        NetheriteNodeIndex.Scan scan = index.discover(world, start, goal, 0, 256);
        finish(scan); assertTrue(scan.result().isEmpty());
        verify(snapshot, never()).getBlockType(anyInt(), anyInt(), anyInt());
    }

    @Test void unloadedChunksAreNeverObtainedEvenWithCachedMarkers() {
        buried.add(new Node(2, 3, 2));
        finish(index.discover(world, start, goal, 0, 256));
        clearInvocations(world);
        when(world.isChunkLoaded(0, 0)).thenReturn(false);
        NetheriteNodeIndex.Scan scan = index.discover(world, start, goal, 24, 256);
        finish(scan); assertTrue(scan.result().isEmpty());
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test void invalidateRefreshesRemovedAndNewMarkers() {
        buried.add(new Node(2, 3, 2));
        NetheriteNodeIndex.Scan first = index.discover(world, start, goal, 0, 256); finish(first);
        buried.clear(); buried.add(new Node(8, 8, 8));
        NetheriteNodeIndex.Scan cached = index.discover(world, start, goal, 0, 256); finish(cached);
        assertEquals(first.result(), cached.result());
        index.invalidate(world, 8, 8);
        NetheriteNodeIndex.Scan refreshed = index.discover(world, start, goal, 0, 256); finish(refreshed);
        assertEquals(java.util.List.of(new Node(8, 10, 8)), refreshed.result());
    }

    @Test void chunkUnloadingDuringScanDiscardsItsPartialSnapshot() {
        buried.add(new Node(0, 0, 0));
        NetheriteNodeIndex.Scan scan = index.discover(world, start, goal, 0, 256);
        scan.advance(3);
        when(world.isChunkLoaded(0, 0)).thenReturn(false);
        finish(scan); assertTrue(scan.result().isEmpty());
    }

    @Test void invalidationDuringScanRestartsSnapshotAndNeverCachesOldMarkers() {
        buried.add(new Node(0, 0, 0));
        NetheriteNodeIndex.Scan scan = index.discover(world, start, goal, 0, 256);
        scan.advance(10);
        buried.clear(); buried.add(new Node(8, 8, 8));
        index.invalidate(world, 0, 0);
        finish(scan);
        assertEquals(java.util.List.of(new Node(8, 10, 8)), scan.result());
        NetheriteNodeIndex.Scan cached = index.discover(world, start, goal, 0, 256); finish(cached);
        assertEquals(scan.result(), cached.result());
        verify(world, times(2)).getChunkAt(0, 0);
    }

    @Test void unrelatedEditsDoNotRestartAnActiveScan() {
        buried.add(new Node(0, 0, 0));
        NetheriteNodeIndex.Scan scan = index.discover(world, start, goal, 0, 256);
        scan.advance(10);
        index.invalidate(world, 1000, 1000);
        finish(scan);
        assertEquals(java.util.List.of(new Node(0, 2, 0)), scan.result());
        verify(world, times(1)).getChunkAt(0, 0);
    }

    @Test void tooManyMarkersDeclineRoutingWithinFiniteWork() {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < 3; y++)
            buried.add(new Node(x, y, z));
        NetheriteNodeIndex.Scan scan = index.discover(world, start, goal, 0, 256); finish(scan);
        assertTrue(scan.truncated()); assertTrue(scan.result().isEmpty());
    }

    @Test void excessiveDistanceDeclinesBeforeTouchingChunks() {
        goal.setX(400);
        NetheriteNodeIndex.Scan scan = index.discover(world, start, goal, 24, 256);
        assertTrue(scan.done()); assertTrue(scan.truncated()); assertTrue(scan.result().isEmpty());
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }
}
