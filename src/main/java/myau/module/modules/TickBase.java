package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * TickBase - Speeds up client ticks to get closer to enemies
 * 
 * Ported from LiquidBounce's ModuleTickBase
 * This module simulates player movement and "borrows" ticks from the future
 * to close distance gaps and improve reach effectiveness.
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class TickBase extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // Mode selection
    public final ModeProperty mode = new ModeProperty("Mode", 0, new String[]{"PAST", "FUTURE"});
    public final ModeProperty callMode = new ModeProperty("Call", 0, new String[]{"GAME", "PLAYER"});

    // Range settings
    public final FloatProperty minRange = new FloatProperty("Min Range", 2.5f, 0f, 8f);
    public final FloatProperty maxRange = new FloatProperty("Max Range", 4f, 0f, 8f);

    // Balance system
    public final FloatProperty balanceRecoveryIncrement = new FloatProperty("Balance Recovery", 1f, 0f, 2f);
    public final IntProperty balanceMaxValue = new IntProperty("Balance Max", 20, 0, 200);
    public final IntProperty maxTicksAtATime = new IntProperty("Max Ticks", 4, 1, 20);

    // Control settings
    public final BooleanProperty pauseOnFlag = new BooleanProperty("Pause On Flag", true);
    public final IntProperty pause = new IntProperty("Pause", 0, 0, 20);
    public final IntProperty cooldown = new IntProperty("Cooldown", 0, 0, 100);
    public final BooleanProperty forceGround = new BooleanProperty("Force Ground", false);

    // KillAura integration
    public final BooleanProperty requiresKillAura = new BooleanProperty("Requires KillAura", true);

    // Internal state
    private int ticksToSkip = 0;
    private float tickBalance = 0f;
    private boolean reachedTheLimit = false;
    private final List<TickData> tickBuffer = new ArrayList<>();
    private int cooldownTicks = 0;

    public TickBase() {
        super("TickBase", false);
    }

    @Override
    public void onDisabled() {
        ticksToSkip = 0;
        tickBalance = 0f;
        reachedTheLimit = false;
        tickBuffer.clear();
        cooldownTicks = 0;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) return;

        // Reset balance on flag (teleport packet)
        if (event.getType() == EventType.RECEIVE && event.getPacket() instanceof S08PacketPlayerPosLook) {
            if (pauseOnFlag.getValue()) {
                tickBalance = 0f;
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;

        if (event.getType() != EventType.PRE) return;

        // Don't conflict with blink
        Blink blink = (Blink) Myau.moduleManager.getModule(Blink.class);
        if (mc.thePlayer.isRiding() || (blink != null && blink.isEnabled())) {
            return;
        }

        // Handle tick skipping for PAST mode
        if (ticksToSkip > 0) {
            ticksToSkip--;
            event.setCancelled(true);
            return;
        }

        // Cooldown handling
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        // Update tick buffer with simulated positions
        updateTickBuffer();

        if (tickBuffer.isEmpty()) {
            return;
        }

        // Find nearby enemy
        EntityPlayer nearbyEnemy = findNearbyEnemy();
        if (nearbyEnemy == null) {
            return;
        }

        double currentDistanceSq = mc.thePlayer.getDistanceSqToEntity(nearbyEnemy);
        double minRangeSq = minRange.getValue() * minRange.getValue();
        double maxRangeSq = maxRange.getValue() * maxRange.getValue();

        // Find best tick to execute
        int bestTick = -1;
        TickData bestTickData = null;
        boolean foundCriticalTick = false;

        for (int i = 0; i < tickBuffer.size(); i++) {
            TickData tickData = tickBuffer.get(i);

            // Check if we should only use ground ticks
            if (forceGround.getValue() && !tickData.onGround) {
                continue;
            }

            double distSq = tickData.position.squareDistanceTo(
                    nearbyEnemy.posX, nearbyEnemy.posY, nearbyEnemy.posZ);

            // Must be closer than current distance and in range
            if (distSq < currentDistanceSq && distSq >= minRangeSq && distSq <= maxRangeSq) {
                // Prefer critical hits (falling)
                if (tickData.fallDistance > 0.0f && !foundCriticalTick) {
                    bestTick = i;
                    bestTickData = tickData;
                    foundCriticalTick = true;
                } else if (!foundCriticalTick && bestTick == -1) {
                    bestTick = i;
                    bestTickData = tickData;
                }
            }
        }

        if (bestTick <= 0 || bestTickData == null) {
            return;
        }

        // Check KillAura requirement
        if (requiresKillAura.getValue()) {
            KillAura killAura = (KillAura) Myau.moduleManager.getModule(KillAura.class);
            if (killAura == null || !killAura.isEnabled()) {
                return;
            }
        }

        // Execute tickbase
        executeTickBase(bestTick);
    }

    private void executeTickBase(int tickCount) {
        switch (mode.getValue()) {
            case 0: // PAST mode
                ticksToSkip = tickCount + pause.getValue();

                for (int i = 0; i < tickCount; i++) {
                    executeTick();
                    tickBalance -= 1;
                }

                ticksToSkip = 0;
                cooldownTicks = cooldown.getValue();
                break;

            case 1: // FUTURE mode
                int totalSkipped = 0;

                for (int i = 0; i < tickCount; i++) {
                    executeTick();
                    tickBalance -= 1;
                    totalSkipped++;

                    // Check if we should break early
                    if (requiresKillAura.getValue()) {
                        KillAura killAura = (KillAura) Myau.moduleManager.getModule(KillAura.class);
                        if (killAura == null || !killAura.isEnabled()) {
                            break;
                        }
                    }
                }

                ticksToSkip = totalSkipped + pause.getValue();
                cooldownTicks = cooldown.getValue();
                break;
        }
    }

    private void executeTick() {
        switch (callMode.getValue()) {
            case 0: // GAME - full game tick
                try {
                    mc.runTick();
                } catch (Exception e) {
                    // Fallback to player tick if game tick fails
                    if (mc.thePlayer != null) {
                        mc.thePlayer.onUpdate();
                    }
                }
                break;

            case 1: // PLAYER - only player tick
                if (mc.thePlayer != null) {
                    mc.thePlayer.onUpdate();
                }
                break;
        }
    }

    private void updateTickBuffer() {
        tickBuffer.clear();

        // Manage tick balance
        if (tickBalance <= 0) {
            reachedTheLimit = true;
        }
        if (tickBalance * 2 > balanceMaxValue.getValue()) {
            reachedTheLimit = false;
        }
        if (tickBalance <= balanceMaxValue.getValue()) {
            tickBalance += balanceRecoveryIncrement.getValue();
        }

        if (reachedTheLimit) {
            return;
        }

        // Simulate future positions
        int maxTicks = Math.min((int) tickBalance, maxTicksAtATime.getValue());
        
        // Simple position prediction based on current velocity
        Vec3 currentPos = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        Vec3 currentVel = new Vec3(mc.thePlayer.motionX, mc.thePlayer.motionY, mc.thePlayer.motionZ);
        
        boolean onGround = mc.thePlayer.onGround;
        float fallDistance = mc.thePlayer.fallDistance;

        for (int i = 0; i < maxTicks; i++) {
            // Simple physics simulation
            currentPos = currentPos.addVector(currentVel.xCoord, currentVel.yCoord, currentVel.zCoord);
            
            // Apply gravity if not on ground
            if (!onGround) {
                currentVel = new Vec3(currentVel.xCoord * 0.91, (currentVel.yCoord - 0.08) * 0.98, currentVel.zCoord * 0.91);
                fallDistance += Math.abs(currentVel.yCoord);
            } else {
                currentVel = new Vec3(currentVel.xCoord * 0.6, currentVel.yCoord, currentVel.zCoord * 0.6);
            }

            // Simple ground check (y velocity negative and low)
            if (currentVel.yCoord < 0 && Math.abs(currentVel.yCoord) < 0.1) {
                onGround = true;
                fallDistance = 0;
            } else if (currentVel.yCoord > 0) {
                onGround = false;
            }

            tickBuffer.add(new TickData(currentPos, fallDistance, currentVel, onGround));
        }
    }

    private EntityPlayer findNearbyEnemy() {
        EntityPlayer closest = null;
        double closestDist = maxRange.getValue() * maxRange.getValue();

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player.isDead || player.deathTime > 0) {
                continue;
            }

            // Skip teammates and friends
            if (TeamUtil.isSameTeam(player) || TeamUtil.isFriend(player)) {
                continue;
            }

            double distSq = mc.thePlayer.getDistanceSqToEntity(player);
            if (distSq <= closestDist) {
                closest = player;
                closestDist = distSq;
            }
        }

        return closest;
    }

    private static class TickData {
        final Vec3 position;
        final float fallDistance;
        final Vec3 velocity;
        final boolean onGround;

        TickData(Vec3 position, float fallDistance, Vec3 velocity, boolean onGround) {
            this.position = position;
            this.fallDistance = fallDistance;
            this.velocity = velocity;
            this.onGround = onGround;
        }
    }
}
