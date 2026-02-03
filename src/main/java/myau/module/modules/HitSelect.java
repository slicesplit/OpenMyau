package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.Vec3;

import java.util.Random;

public class HitSelect extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final Random random = new Random();
    
    public final PercentProperty chance = new PercentProperty("chance", 100, 0, 100, null);
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"PAUSE", "ACTIVE"});
    public final ModeProperty preference = new ModeProperty("preference", 0, new String[]{"KB_REDUCTION", "CRITICAL_HITS"});
    
    private boolean sprintState = false;
    private boolean set = false;
    private double savedSlowdown = 0.0;
    
    private int blockedHits = 0;
    private int allowedHits = 0;

    public HitSelect() {
        super("HitSelect", false);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        
        if (event.getType() == EventType.POST) {
            this.resetMotion();
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || event.isCancelled()) {
            return;
        }

        if (event.getPacket() instanceof C0BPacketEntityAction) {
            C0BPacketEntityAction packet = (C0BPacketEntityAction) event.getPacket();
            switch (packet.getAction()) {
                case START_SPRINTING:
                    this.sprintState = true;
                    break;
                case STOP_SPRINTING:
                    this.sprintState = false;
                    break;
            }
            return;
        }

        if (event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity use = (C02PacketUseEntity) event.getPacket();
            
            if (use.getAction() != C02PacketUseEntity.Action.ATTACK) {
                return;
            }

            Entity target = use.getEntityFromWorld(mc.theWorld);
            if (target == null || target instanceof EntityLargeFireball) {
                return;
            }

            if (!(target instanceof EntityLivingBase)) {
                return;
            }

            EntityLivingBase living = (EntityLivingBase) target;
            
            if (random.nextFloat() * 100.0F > this.chance.getValue().floatValue()) {
                this.allowedHits++;
                return;
            }
            
            boolean allow = true;

            switch (this.mode.getValue()) {
                case 0:
                    allow = this.evaluatePauseMode(mc.thePlayer, living);
                    break;
                case 1:
                    allow = this.evaluateActiveMode(mc.thePlayer, living);
                    break;
            }

            if (!allow) {
                event.setCancelled(true);
                this.blockedHits++;
            } else {
                this.allowedHits++;
            }
        }
    }

    private boolean evaluatePauseMode(EntityLivingBase player, EntityLivingBase target) {
        if (target.hurtTime > 0) {
            return true;
        }

        double dist = player.getDistanceToEntity(target);
        if (dist < 2.5) {
            return true;
        }

        this.fixMotion();
        return false;
    }

    private boolean evaluateActiveMode(EntityLivingBase player, EntityLivingBase target) {
        boolean shouldBlock = false;

        switch (this.preference.getValue()) {
            case 0:
                shouldBlock = this.evaluateKBReduction(player, target);
                break;
            case 1:
                shouldBlock = this.evaluateCriticalHits(player, target);
                break;
        }

        if (shouldBlock) {
            this.fixMotion();
            return false;
        }

        return true;
    }

    private boolean evaluateKBReduction(EntityLivingBase player, EntityLivingBase target) {
        if (target.hurtTime > 0) {
            return false;
        }

        if (player.hurtTime > 0 && player.hurtTime <= player.maxHurtTime - 1) {
            return false;
        }

        double dist = player.getDistanceToEntity(target);
        if (dist < 2.5) {
            return false;
        }

        if (!this.isMovingTowards(target, player, 60.0)) {
            return false;
        }

        if (!this.isMovingTowards(player, target, 60.0)) {
            return false;
        }

        if (!this.sprintState) {
            return true;
        }

        return false;
    }

    private boolean evaluateCriticalHits(EntityLivingBase player, EntityLivingBase target) {
        if (target.hurtTime > 0) {
            return false;
        }

        if (player.onGround) {
            return false;
        }

        if (player.hurtTime > 0) {
            return false;
        }

        if (player.fallDistance > 0.0F && player.motionY < 0.0) {
            return false;
        }

        if (player.motionY > -0.1 && player.motionY < 0.1) {
            return true;
        }

        return false;
    }

    private void fixMotion() {
        if (this.set) {
            return;
        }

        KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint == null) {
            return;
        }

        try {
            // Save the current slowdown value
            this.savedSlowdown = keepSprint.slowdown.getValue().doubleValue();
            
            // Enable KeepSprint and set slowdown to 0
            if (!keepSprint.isEnabled()) {
                keepSprint.toggle();
            }
            keepSprint.slowdown.setValue(0);
            
            this.set = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void resetMotion() {
        if (!this.set) {
            return;
        }

        KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint == null) {
            return;
        }

        try {
            // Restore the original slowdown value
            keepSprint.slowdown.setValue((int) this.savedSlowdown);
            
            // Disable KeepSprint if we enabled it
            if (keepSprint.isEnabled()) {
                keepSprint.toggle();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.set = false;
        this.savedSlowdown = 0.0;
    }

    private boolean isMovingTowards(EntityLivingBase source, EntityLivingBase target, double maxAngle) {
        Vec3 currentPos = source.getPositionVector();
        Vec3 lastPos = new Vec3(source.lastTickPosX, source.lastTickPosY, source.lastTickPosZ);
        Vec3 targetPos = target.getPositionVector();

        // Calculate movement vector
        double mx = currentPos.xCoord - lastPos.xCoord;
        double mz = currentPos.zCoord - lastPos.zCoord;
        double movementLength = Math.sqrt(mx * mx + mz * mz);

        // If not moving, return false
        if (movementLength == 0.0) {
            return false;
        }

        // Normalize movement vector
        mx /= movementLength;
        mz /= movementLength;

        // Calculate vector to target
        double tx = targetPos.xCoord - currentPos.xCoord;
        double tz = targetPos.zCoord - currentPos.zCoord;
        double targetLength = Math.sqrt(tx * tx + tz * tz);

        // If target is at same position, return false
        if (targetLength == 0.0) {
            return false;
        }

        // Normalize target vector
        tx /= targetLength;
        tz /= targetLength;

        // Calculate dot product (cosine of angle between vectors)
        double dotProduct = mx * tx + mz * tz;

        // Check if angle is within threshold
        return dotProduct >= Math.cos(Math.toRadians(maxAngle));
    }

    @Override
    public void onDisabled() {
        this.resetMotion();
        this.sprintState = false;
        this.set = false;
        this.savedSlowdown = 0.0;
        this.blockedHits = 0;
        this.allowedHits = 0;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}