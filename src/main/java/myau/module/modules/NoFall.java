package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.mixin.IAccessorMinecraft;
import myau.module.Module;
import myau.util.*;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.item.ItemSword;

@ModuleInfo(category = ModuleCategory.MOVEMENT)
public class NoFall extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final TimerUtil packetDelayTimer = new TimerUtil();
    private final TimerUtil scoreboardResetTimer = new TimerUtil();
    private boolean slowFalling = false;
    private boolean lastOnGround = false;
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"PACKET", "BLINK", "NO_GROUND", "SPOOF", "SWORD_BLOCK"});
    public final FloatProperty distance = new FloatProperty("distance", 3.0F, 0.0F, 20.0F);
    public final IntProperty delay = new IntProperty("delay", 0, 0, 10000);

    // ── SWORD_BLOCK state ─────────────────────────────────────────────────────
    private boolean sbBlocking   = false; // currently holding block server-side
    private int     sbPrevSlot   = -1;    // hotbar slot we were on before switching to sword
    private int     sbSwordSlot  = -1;    // hotbar slot of the sword we switched to

    private boolean canTrigger() {
        return this.scoreboardResetTimer.hasTimeElapsed(3000) && this.packetDelayTimer.hasTimeElapsed(this.delay.getValue().longValue());
    }

    public NoFall() {
        super("NoFall", false);
    }

    @EventTarget(Priority.HIGH)
    public void onPacket(PacketEvent event) {
        if (event.getType() == EventType.RECEIVE && event.getPacket() instanceof S08PacketPlayerPosLook) {
            sbStopBlocking();
            this.onDisabled();
        } else if (this.isEnabled() && event.getType() == EventType.SEND && !event.isCancelled()) {
            if (event.getPacket() instanceof C03PacketPlayer) {
                C03PacketPlayer packet = (C03PacketPlayer) event.getPacket();
                switch (this.mode.getValue()) {
                    case 0:
                        if (this.slowFalling) {
                            this.slowFalling = false;
                            ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1.0F;
                        } else if (!packet.isOnGround()) {
                            AxisAlignedBB aabb = mc.thePlayer.getEntityBoundingBox().expand(2.0, 0.0, 2.0);
                            if (PlayerUtil.canFly(this.distance.getValue())
                                    && !PlayerUtil.checkInWater(aabb)
                                    && this.canTrigger()) {
                                this.packetDelayTimer.reset();
                                this.slowFalling = true;
                                ((IAccessorMinecraft) mc).getTimer().timerSpeed = 0.5F;
                            }
                        }
                        break;
                    case 1:
                        boolean allowed = !mc.thePlayer.isOnLadder() && !mc.thePlayer.capabilities.allowFlying && mc.thePlayer.hurtTime == 0;
                        if (Myau.blinkManager.getBlinkingModule() != BlinkModules.NO_FALL) {
                            if (this.lastOnGround
                                    && !packet.isOnGround()
                                    && allowed
                                    && PlayerUtil.canFly(this.distance.getValue().intValue())
                                    && mc.thePlayer.motionY < 0.0) {
                                Myau.blinkManager.setBlinkState(false, Myau.blinkManager.getBlinkingModule());
                                Myau.blinkManager.setBlinkState(true, BlinkModules.NO_FALL);
                            }
                        } else if (!allowed) {
                            Myau.blinkManager.setBlinkState(false, BlinkModules.NO_FALL);
                            ChatUtil.sendFormatted(String.format("%s%s: &cFailed player check!&r", Myau.clientName, this.getName()));
                        } else if (PlayerUtil.checkInWater(mc.thePlayer.getEntityBoundingBox().expand(2.0, 0.0, 2.0))) {
                            Myau.blinkManager.setBlinkState(false, BlinkModules.NO_FALL);
                            ChatUtil.sendFormatted(String.format("%s%s: &cFailed void check!&r", Myau.clientName, this.getName()));
                        } else if (packet.isOnGround()) {
                            for (Packet<?> blinkedPacket : Myau.blinkManager.blinkedPackets) {
                                if (blinkedPacket instanceof C03PacketPlayer) {
                                    ((IAccessorC03PacketPlayer) blinkedPacket).setOnGround(true);
                                }
                            }
                            Myau.blinkManager.setBlinkState(false, BlinkModules.NO_FALL);
                            this.packetDelayTimer.reset();
                        }
                        this.lastOnGround = packet.isOnGround() && allowed && this.canTrigger();
                        break;
                    case 2:
                        ((IAccessorC03PacketPlayer) packet).setOnGround(false);
                        break;
                    case 3:
                        if (!packet.isOnGround()) {
                            AxisAlignedBB aabb = mc.thePlayer.getEntityBoundingBox().expand(2.0, 0.0, 2.0);
                            if (PlayerUtil.canFly(this.distance.getValue())
                                    && !PlayerUtil.checkInWater(aabb)
                                    && this.canTrigger()) {
                                this.packetDelayTimer.reset();
                                ((IAccessorC03PacketPlayer) packet).setOnGround(true);
                                mc.thePlayer.fallDistance = 0.0F;
                            }
                        }
                }
            }
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            if (ServerUtil.hasPlayerCountInfo()) {
                this.scoreboardResetTimer.reset();
            }
            if (this.mode.getValue() == 0 && this.slowFalling) {
                PacketUtil.sendPacketNoEvent(new C03PacketPlayer(true));
                mc.thePlayer.fallDistance = 0.0F;
            }
            if (this.mode.getValue() == 4) {
                tickSwordBlock();
            }
        }
    }

    // ── SWORD_BLOCK logic ─────────────────────────────────────────────────────

    private void tickSwordBlock() {
        if (mc.thePlayer == null) return;

        boolean safe = mc.thePlayer.onGround
                || mc.thePlayer.isInWater()
                || mc.thePlayer.isInLava()
                || mc.thePlayer.isOnLadder()
                || mc.thePlayer.isRiding()
                || mc.thePlayer.capabilities.allowFlying;

        if (sbBlocking && safe) {
            // Landed — release block and restore slot
            sbStopBlocking();
            return;
        }

        if (!sbBlocking && !safe) {
            // Airborne — check if we've fallen far enough to warrant protection
            float fallDist = mc.thePlayer.fallDistance;
            // Immune fall distance in vanilla is 3 blocks. We trigger slightly
            // before that threshold so the block is held when we actually land.
            float threshold = Math.max(0.5f, distance.getValue() - 1.5f);
            if (fallDist >= threshold) {
                sbStartBlocking();
            }
        }
    }

    private void sbStartBlocking() {
        // Find best sword in hotbar (slots 0-8 only — no inventory opens)
        int current = mc.thePlayer.inventory.currentItem;
        int swordSlot = -1;

        // Prefer already-held sword
        if (mc.thePlayer.getHeldItem() != null
                && mc.thePlayer.getHeldItem().getItem() instanceof ItemSword) {
            swordSlot = current;
        } else {
            // Find best sword in hotbar
            double best = -1;
            for (int i = 0; i < 9; i++) {
                net.minecraft.item.ItemStack s = mc.thePlayer.inventory.getStackInSlot(i);
                if (s == null || !(s.getItem() instanceof ItemSword)) continue;
                double dmg = ItemUtil.getAttackBonus(s);
                if (dmg > best) { best = dmg; swordSlot = i; }
            }
        }

        if (swordSlot == -1) return; // no sword in hotbar

        sbPrevSlot  = current;
        sbSwordSlot = swordSlot;

        if (swordSlot != current) {
            mc.thePlayer.inventory.currentItem = swordSlot;
        }

        net.minecraft.item.ItemStack held = mc.thePlayer.inventory.getStackInSlot(swordSlot);
        if (held == null) return;

        // Send block packet + start client-side use so the arm animates
        PacketUtil.sendPacketSafe(new C08PacketPlayerBlockPlacement(held));
        mc.thePlayer.setItemInUse(held, held.getMaxItemUseDuration());
        sbBlocking = true;
    }

    private void sbStopBlocking() {
        if (!sbBlocking) return;

        // Release block server-side
        PacketUtil.sendPacketSafe(new C07PacketPlayerDigging(
                C07PacketPlayerDigging.Action.RELEASE_USE_ITEM,
                BlockPos.ORIGIN, EnumFacing.DOWN));
        mc.thePlayer.stopUsingItem();

        // Restore previous slot if we switched
        if (sbPrevSlot != -1 && mc.thePlayer.inventory.currentItem == sbSwordSlot) {
            mc.thePlayer.inventory.currentItem = sbPrevSlot;
        }

        sbBlocking  = false;
        sbPrevSlot  = -1;
        sbSwordSlot = -1;
    }

    @Override
    public void onDisabled() {
        this.lastOnGround = false;
        Myau.blinkManager.setBlinkState(false, BlinkModules.NO_FALL);
        if (this.slowFalling) {
            this.slowFalling = false;
            ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1.0F;
        }
        sbStopBlocking();
    }

    @Override
    public void verifyValue(String mode) {
        if (this.isEnabled()) {
            this.onDisabled();
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}
