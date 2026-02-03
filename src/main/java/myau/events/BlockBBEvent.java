package myau.events;

import myau.event.events.callables.EventCancellable;
import net.minecraft.block.Block;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;

public class BlockBBEvent extends EventCancellable {
    private final Block block;
    private final BlockPos pos;
    private AxisAlignedBB boundingBox;

    public BlockBBEvent(Block block, BlockPos pos, AxisAlignedBB boundingBox) {
        this.block = block;
        this.pos = pos;
        this.boundingBox = boundingBox;
    }

    public Block getBlock() {
        return this.block;
    }

    public BlockPos getPos() {
        return this.pos;
    }

    public AxisAlignedBB getBoundingBox() {
        return this.boundingBox;
    }

    public void setBoundingBox(AxisAlignedBB boundingBox) {
        this.boundingBox = boundingBox;
    }
}
