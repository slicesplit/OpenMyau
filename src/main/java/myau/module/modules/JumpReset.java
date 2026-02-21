package myau.module.modules;

import myau.Myau;
import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.MathHelper;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class JumpReset extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final IntProperty chance = new IntProperty("Chance", 100, 1, 100);
    public final IntProperty accuracy = new IntProperty("Accuracy", 100, 1, 100);
    public final BooleanProperty onlyTargeting = new BooleanProperty("OnlyWhenTargeting", false);
    public final BooleanProperty waterCheck = new BooleanProperty("WaterCheck", true);

    // Internal state
    private boolean shouldJump = false;
    private boolean shouldFail = false;
    private int jumpDelay = 0;
    private long lastVelocityTime = 0;

    // Vanilla jump cooldown tracking
    private static final long VELOCITY_COOLDOWN_MS = 500;

    public JumpReset() {
        super("JumpReset", false);
    }

    @Override
    public void onEnabled() {
        shouldJump = false;
        shouldFail = false;
        jumpDelay = 0;
        lastVelocityTime = 0;
    }

    @Override
    public void onDisabled() {
        shouldJump = false;
        shouldFail = false;
        jumpDelay = 0;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.RECEIVE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        // Detect incoming knockback
        if (event.getPacket() instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity packet = (S12PacketEntityVelocity) event.getPacket();

            // Only react to our own velocity
            if (packet.getEntityID() != mc.thePlayer.getEntityId()) return;

            // Ignore tiny velocities (fall damage, etc.)
            double velX = packet.getMotionX() / 8000.0;
            double velZ = packet.getMotionZ() / 8000.0;
            double horizontalVel = Math.sqrt(velX * velX + velZ * velZ);

            // Only trigger on actual combat knockback
            if (horizontalVel < 0.05) return;

            handleIncomingKnockback();
            return;
        }

        // Also handle explosion knockback
        if (event.getPacket() instanceof S27PacketExplosion) {
            S27PacketExplosion packet = (S27PacketExplosion) event.getPacket();
            double horizontalVel = Math.sqrt(
                    packet.func_149149_c() * packet.func_149149_c() +
                    packet.func_149144_d() * packet.func_149144_d()
            );
            if (horizontalVel < 0.05) return;

            handleIncomingKnockback();
        }
    }

    private void handleIncomingKnockback() {
        // Cooldown — don't double-jump from rapid hits
        long now = System.currentTimeMillis();
        if (now - lastVelocityTime < VELOCITY_COOLDOWN_MS) return;
        lastVelocityTime = now;

        // Water check
        if (waterCheck.getValue() && (mc.thePlayer.isInWater()
                || mc.thePlayer.isInLava()
                || mc.thePlayer.isInsideOfMaterial(net.minecraft.block.material.Material.water))) {
            return;
        }

        // Must be on ground to jump reset
        if (!mc.thePlayer.onGround) return;

        // Only when targeting check
        if (onlyTargeting.getValue() && !isLookingAtAttacker()) return;

        // Chance roll
        if (Math.random() * 100 >= chance.getValue()) return;

        // Accuracy roll — decide if this jump will be perfectly timed or mistimed
        shouldFail = Math.random() * 100 >= accuracy.getValue();

        // Schedule the jump
        shouldJump = true;

        if (shouldFail) {
            // Intentionally mistime — delay by 1-3 extra ticks (too late)
            jumpDelay = 1 + (int) (Math.random() * 3);
        } else {
            // Perfect timing — jump immediately on next tick
            // The velocity packet arrives between ticks, so jumping on the
            // very next game tick is the frame-perfect window.
            // Delay 0 = this tick's update will handle it.
            jumpDelay = 0;
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || !shouldJump) return;

        // Count down delay for intentional mistiming
        if (jumpDelay > 0) {
            jumpDelay--;
            return;
        }

        // Execute jump
        if (mc.thePlayer.onGround) {
            mc.thePlayer.jump();
        }

        // Reset state
        shouldJump = false;
        shouldFail = false;
    }

    /**
     * Check if the player who hit us is roughly in our crosshair direction.
     * Finds the nearest player within 6 blocks and checks if they're within
     * ~60 degree cone of our look direction.
     */
    private boolean isLookingAtAttacker() {
        // First check if KillAura has a target
        try {
            KillAura ka = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
            if (ka != null && ka.isEnabled()) {
                // KillAura is active — we're definitely targeting someone
                return true;
            }
        } catch (Exception ignored) {}

        // Manual crosshair check — find nearest player and check angle
        EntityPlayer nearest = null;
        double nearestDist = 6.0;

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player.isDead) continue;
            double dist = mc.thePlayer.getDistanceToEntity(player);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = player;
            }
        }

        if (nearest == null) return false;

        // Calculate angle between look direction and direction to attacker
        double dx = nearest.posX - mc.thePlayer.posX;
        double dz = nearest.posZ - mc.thePlayer.posZ;
        float angleToTarget = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float yawDiff = MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - angleToTarget);

        // Within ~60 degree cone
        return Math.abs(yawDiff) < 60.0F;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{chance.getValue() + "%"};
    }
}