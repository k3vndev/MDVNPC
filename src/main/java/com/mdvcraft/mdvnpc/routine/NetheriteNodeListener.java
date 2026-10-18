package com.mdvcraft.mdvnpc.routine;

import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/** Keeps the bounded marker cache current after ordinary world edits. */
public final class NetheriteNodeListener implements Listener {
    @FunctionalInterface private interface Invalidation {
        void apply(org.bukkit.World world,int blockX,int blockZ);
    }
    private final Invalidation invalidation;

    public NetheriteNodeListener(RoutineNavigator navigator) {
        this.invalidation = navigator::invalidateNodes;
    }
    /** Resolve the service's current navigator after every configuration reload. */
    public NetheriteNodeListener(RoutineService service) {
        this.invalidation = service::invalidateNodes;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        invalidate(event.getBlockPlaced());
        if (event instanceof BlockMultiPlaceEvent multi)
            for (BlockState state : multi.getReplacedBlockStates())
                invalidation.apply(state.getWorld(), state.getX(), state.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        invalidate(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityExplosion(EntityExplodeEvent event) {
        for (Block block : event.blockList()) invalidate(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockExplosion(BlockExplodeEvent event) {
        invalidate(event.getBlock());
        for (Block block : event.blockList()) invalidate(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pistonExtend(BlockPistonExtendEvent event) {
        piston(event.getBlock(), event.getDirection(), event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pistonRetract(BlockPistonRetractEvent event) {
        piston(event.getBlock(), event.getDirection(), event.getBlocks());
    }

    private void piston(Block piston, BlockFace direction, Iterable<Block> moved) {
        invalidate(piston);
        invalidateOffset(piston, direction, 1);
        for (Block block : moved) {
            invalidate(block);
            // Cover both ends for extension and retraction, including chunk boundaries.
            invalidateOffset(block, direction, 1);
            invalidateOffset(block, direction, -1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void chunkLoad(ChunkLoadEvent event) {
        invalidate(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void chunkUnload(ChunkUnloadEvent event) {
        invalidate(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void worldUnload(WorldUnloadEvent event) {
        for (Chunk chunk : event.getWorld().getLoadedChunks()) invalidate(chunk);
    }

    private void invalidate(Block block) {
        invalidation.apply(block.getWorld(), block.getX(), block.getZ());
    }

    private void invalidate(Chunk chunk) {
        invalidation.apply(chunk.getWorld(), chunk.getX() << 4, chunk.getZ() << 4);
    }

    private void invalidateOffset(Block block, BlockFace direction, int distance) {
        // Coordinates avoid accessing neighboring chunks merely to invalidate cached data.
        invalidation.apply(block.getWorld(), block.getX() + direction.getModX() * distance,
                block.getZ() + direction.getModZ() * distance);
    }
}
