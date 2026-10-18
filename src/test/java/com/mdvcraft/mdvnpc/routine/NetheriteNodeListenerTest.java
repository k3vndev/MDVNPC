package com.mdvcraft.mdvnpc.routine;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class NetheriteNodeListenerTest {
    private final RoutineNavigator navigator = mock(RoutineNavigator.class);
    private final NetheriteNodeListener listener = new NetheriteNodeListener(navigator);
    private final World world = mock(World.class);

    @Test void chunkLifecycleConvertsNegativeChunkCoordinatesToBlockCoordinates() {
        Chunk chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(-2);
        when(chunk.getZ()).thenReturn(3);
        ChunkLoadEvent loaded = mock(ChunkLoadEvent.class);
        ChunkUnloadEvent unloaded = mock(ChunkUnloadEvent.class);
        when(loaded.getChunk()).thenReturn(chunk);
        when(unloaded.getChunk()).thenReturn(chunk);

        listener.chunkLoad(loaded);
        listener.chunkUnload(unloaded);

        verify(navigator, times(2)).invalidateNodes(world, -32, 48);
        verifyNoMoreInteractions(navigator);
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test void pistonExtensionInvalidatesBothSidesOfAChunkBoundaryWithoutLoadingChunks() {
        BlockPistonExtendEvent event = mock(BlockPistonExtendEvent.class);
        Block piston=block(14,5), moved=block(15,5);
        when(event.getBlock()).thenReturn(piston);
        when(event.getDirection()).thenReturn(BlockFace.EAST);
        when(event.getBlocks()).thenReturn(List.of(moved));

        listener.pistonExtend(event);

        verify(navigator).invalidateNodes(world, 16, 5);
        verify(navigator, atLeastOnce()).invalidateNodes(world, 15, 5);
        verify(world, never()).getChunkAt(anyInt(), anyInt());
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    @Test void pistonRetractionInvalidatesTheMovedBlockAndItsDestination() {
        BlockPistonRetractEvent event = mock(BlockPistonRetractEvent.class);
        Block piston=block(-3,5), moved=block(0,5);
        when(event.getBlock()).thenReturn(piston);
        when(event.getDirection()).thenReturn(BlockFace.EAST);
        when(event.getBlocks()).thenReturn(List.of(moved));

        listener.pistonRetract(event);

        verify(navigator).invalidateNodes(world, 0, 5);
        verify(navigator).invalidateNodes(world, -1, 5);
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test void explosionInvalidatesEveryAffectedChunk() {
        EntityExplodeEvent event = mock(EntityExplodeEvent.class);
        List<Block> affected=List.of(block(-1,0),block(16,0));
        when(event.blockList()).thenReturn(affected);

        listener.entityExplosion(event);

        verify(navigator).invalidateNodes(world, -1, 0);
        verify(navigator).invalidateNodes(world, 16, 0);
        verifyNoMoreInteractions(navigator);
    }

    private Block block(int x, int z) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getZ()).thenReturn(z);
        return block;
    }
}
