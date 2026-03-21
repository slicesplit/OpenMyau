package myau.module.modules;

import myau.Myau;
import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class JumpReset extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final IntProperty chance = new IntProperty("Chance", 100, 1, 100);
    public final IntProperty accuracy = new IntProperty("Accuracy", 100, 1, 100);
    public final BooleanProperty onlyTargeting = new BooleanProperty("OnlyWhenTargeting", false);
    public final BooleanProperty waterCheck = new BooleanProperty("WaterCheck", true);

    private boolean pendingJump = false;
    private boolean shouldFail = false;
    private int failDelay = 0;
    private boolean wasGroundedOnKB = false;
    private int jumpCooldown = 0;

    public JumpReset() {
        super("JumpReset", false);
    }

    @Override
    public void onEnabled() {
        this.pendingJump = false;
        this.shouldFail = false;
        this.failDelay = 0;
        this.wasGroundedOnKB = false;
        this.jumpCooldown = 0;
    }

    @Override
    public void onDisabled() {
        this.pendingJump = false;
        this.shouldFail = false;
        this.failDelay = 0;
        this.wasGroundedOnKB = false;
        this.jumpCooldown = 0;
    }

    @EventTarget(Priority.HIGH)
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.RECEIVE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        boolean isKB = false;
        double horizontalVel = 0;

        if (event.getPacket() instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity packet = (S12PacketEntityVelocity) event.getPacket();
            if (packet.getEntityID() != mc.thePlayer.getEntityId()) return;

            double velX = packet.getMotionX() / 8000.0;
            double velZ = packet.getMotionZ() / 8000.0;
            horizontalVel = Math.sqrt(velX * velX + velZ * velZ);
            isKB = true;
        }

        if (event.getPacket() instanceof S27PacketExplosion) {
            S27PacketExplosion packet = (S27PacketExplosion) event.getPacket();
            float expX = packet.func_149149_c();
            float expZ = packet.func_149147_e();
            horizontalVel = Math.sqrt(expX * expX + expZ * expZ);
            isKB = true;
        }

        if (!isKB) return;
        if (horizontalVel < 0.05) return;
        if (this.jumpCooldown > 0) return;

        if (waterCheck.getValue() && (mc.thePlayer.isInWater()
                || mc.thePlayer.isInLava()
                || mc.thePlayer.isInsideOfMaterial(net.minecraft.block.material.Material.water))) {
            return;
        }

        if (onlyTargeting.getValue() && !isTargetingPlayer()) return;
        if (Math.random() * 100 >= chance.getValue()) return;

        this.wasGroundedOnKB = mc.thePlayer.onGround;
        if (!this.wasGroundedOnKB) return;

        this.shouldFail = Math.random() * 100 >= accuracy.getValue();

        if (this.shouldFail) {
            this.failDelay = 1 + (int) (Math.random() * 3);
        } else {
            this.failDelay = 0;
        }

        this.pendingJump = true;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null) return;

        if (this.jumpCooldown > 0) {
            this.jumpCooldown--;
        }

        if (!this.pendingJump) return;

        if (this.failDelay > 0) {
            this.failDelay--;
            if (this.failDelay > 0) return;
        }

        if (mc.thePlayer.onGround || this.wasGroundedOnKB) {
            mc.thePlayer.jump();
            this.jumpCooldown = 10;
        }

        this.pendingJump = false;
        this.shouldFail = false;
        this.wasGroundedOnKB = false;
    }

    /**
     * Check if we're actively fighting someone.
     * 360° — any player within 6 blocks triggers this.
     */
    private boolean isTargetingPlayer() {
        // Check if KillAura is active
        try {
            KillAura ka = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
            if (ka != null && ka.isEnabled()) {
                return true;
            }
        } catch (Exception ignored) {}

        // 360° check — any player within range counts
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player.isDead) continue;
            if (mc.thePlayer.getDistanceToEntity(player) < 6.0) {
                return true;
            }
        }

        return false;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{chance.getValue() + "%"};
    }
}