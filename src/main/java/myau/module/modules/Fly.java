package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.BlockPos;

import java.util.HashSet;
import java.util.Iterator;

@ModuleInfo(category = ModuleCategory.MOVEMENT)
public class Fly extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("Mode", 0, new String[]{
            "Vanilla", "Creative", "Ghost Block"});

    public final FloatProperty speed = new FloatProperty("Speed", 1.0F, 0.1F, 10.0F,
            () -> mode.getValue() != 2);

    public final IntProperty radius = new IntProperty("Radius", 3, 1, 6,
            () -> mode.getValue() == 2);
    public final BooleanProperty keepY = new BooleanProperty("Keep Y", true,
            () -> mode.getValue() == 2);
    public final BooleanProperty visible = new BooleanProperty("Visible", false,
            () -> mode.getValue() == 2);

    private int floorY;
    private final HashSet<BlockPos> ghosts = new HashSet<>();

    private int lastCx, lastCz;
    private int wid;

    public Fly() {
        super("Fly", false);
    }

    @Override
    public void onEnabled() {
        if (mc.thePlayer == null || mc.theWorld == null) return;

        ghosts.clear();
        wid = System.identityHashCode(mc.theWorld);

        switch (mode.getValue()) {
            case 0:
                mc.thePlayer.capabilities.isFlying = true;
                break;
            case 1:
                mc.thePlayer.capabilities.isFlying = true;
                mc.thePlayer.capabilities.allowFlying = true;
                break;
            case 2:
                floorY = (int) Math.floor(mc.thePlayer.posY) - 1;
                lastCx = (int) Math.floor(mc.thePlayer.posX);
                lastCz = (int) Math.floor(mc.thePlayer.posZ);
                build();
                break;
        }
    }

    @Override
    public void onDisabled() {
        if (mc.thePlayer == null) return;

        clear();

        switch (mode.getValue()) {
            case 0:
                mc.thePlayer.capabilities.isFlying = false;
                break;
            case 1:
                mc.thePlayer.capabilities.isFlying = false;
                mc.thePlayer.capabilities.allowFlying = mc.thePlayer.capabilities.isCreativeMode;
                break;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) return;

        int w = System.identityHashCode(mc.theWorld);
        if (w != wid) {
            clear();
            wid = w;
            if (mode.getValue() == 2) {
                floorY = (int) Math.floor(mc.thePlayer.posY) - 1;
                lastCx = (int) Math.floor(mc.thePlayer.posX);
                lastCz = (int) Math.floor(mc.thePlayer.posZ);
                build();
            }
            return;
        }

        switch (mode.getValue()) {
            case 0: tickVanilla(); break;
            case 1: tickCreative(); break;
            case 2: tickGhost(); break;
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (mc.thePlayer == null) return;

        if (event.getType() == EventType.RECEIVE && mode.getValue() == 2) {
            if (event.getPacket() instanceof S08PacketPlayerPosLook) {
                S08PacketPlayerPosLook pkt = (S08PacketPlayerPosLook) event.getPacket();
                floorY = (int) Math.floor(pkt.getY()) - 1;
                rebuild();
            }
        }
    }

    private void tickVanilla() {
        mc.thePlayer.capabilities.isFlying = true;
        mc.thePlayer.capabilities.setFlySpeed(speed.getValue() / 20.0F);
        mc.thePlayer.motionY = 0;

        if (mc.gameSettings.keyBindJump.isKeyDown()) {
            mc.thePlayer.motionY = speed.getValue() * 0.15;
        }
        if (mc.gameSettings.keyBindSneak.isKeyDown()) {
            mc.thePlayer.motionY = -speed.getValue() * 0.15;
        }
    }

    private void tickCreative() {
        mc.thePlayer.capabilities.isFlying = true;
        mc.thePlayer.capabilities.allowFlying = true;
        mc.thePlayer.capabilities.setFlySpeed(speed.getValue() / 20.0F);
    }

    private void tickGhost() {
        double py = mc.thePlayer.posY;

        int desiredFloor;
        if (keepY.getValue()) {
            desiredFloor = floorY;
        } else {
            desiredFloor = (int) Math.floor(py) - 1;
        }

        boolean floorChanged = desiredFloor != floorY;
        if (floorChanged) {
            floorY = desiredFloor;
            rebuild();
        } else {
            sync();
        }
    }

    private void build() {
        int r = radius.getValue();
        int cx = (int) Math.floor(mc.thePlayer.posX);
        int cz = (int) Math.floor(mc.thePlayer.posZ);
        lastCx = cx;
        lastCz = cz;

        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                place(new BlockPos(x, floorY, z));
            }
        }
    }

    private void sync() {
        int r = radius.getValue();
        int cx = (int) Math.floor(mc.thePlayer.posX);
        int cz = (int) Math.floor(mc.thePlayer.posZ);

        if (cx == lastCx && cz == lastCz) return;

        HashSet<BlockPos> need = new HashSet<>();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                need.add(new BlockPos(x, floorY, z));
            }
        }

        Iterator<BlockPos> it = ghosts.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            if (!need.contains(pos)) {
                remove(pos);
                it.remove();
            }
        }

        for (BlockPos pos : need) {
            if (!ghosts.contains(pos)) {
                place(pos);
            }
        }

        lastCx = cx;
        lastCz = cz;
    }

    private void rebuild() {
        clear();
        build();
    }

    private void place(BlockPos pos) {
        if (mc.theWorld == null) return;
        if (ghosts.contains(pos)) return;

        Block cur = mc.theWorld.getBlockState(pos).getBlock();
        if (cur != Blocks.air && !cur.getMaterial().isReplaceable()) return;
        if (realAt(pos)) return;

        IBlockState state = visible.getValue()
                ? Blocks.stained_glass.getStateFromMeta(3)
                : Blocks.barrier.getDefaultState();

        mc.theWorld.setBlockState(pos, state, 2 | 16);
        ghosts.add(pos);
    }

    private void remove(BlockPos pos) {
        if (mc.theWorld == null) return;
        mc.theWorld.setBlockState(pos, Blocks.air.getDefaultState(), 2 | 16);
    }

    private void clear() {
        if (mc.theWorld == null) {
            ghosts.clear();
            return;
        }
        for (BlockPos pos : ghosts) {
            remove(pos);
        }
        ghosts.clear();
    }

    private boolean realAt(BlockPos pos) {
        if (mc.theWorld == null) return false;
        if (ghosts.contains(pos)) return false;
        Block b = mc.theWorld.getBlockState(pos).getBlock();
        return b != Blocks.air && b.getMaterial().isSolid();
    }

    @Override
    public void verifyValue(String m) {
        if (isEnabled()) {
            onDisabled();
            onEnabled();
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString()};
    }
}