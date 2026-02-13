package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.management.RotationState;
import myau.module.Module;
import myau.util.*;
import myau.property.properties.*;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.AxisAlignedBB;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class AntiFireball extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final ArrayList<EntityFireball> farList = new ArrayList<>();
    private final ArrayList<EntityFireball> nearList = new ArrayList<>();
    private EntityFireball target = null;
    private int consecutiveMisses = 0;
    private long lastHitTime = 0L;
    public final FloatProperty range = new FloatProperty("range", 5.5F, 3.0F, 8.0F);
    public final IntProperty fov = new IntProperty("fov", 360, 1, 360);
    public final BooleanProperty rotations = new BooleanProperty("rotations", true);
    public final BooleanProperty swing = new BooleanProperty("swing", true);
    public final BooleanProperty predictive = new BooleanProperty("predictive", true);
    public final FloatProperty prediction = new FloatProperty("prediction", 2.0F, 0.0F, 5.0F, () -> this.predictive.getValue());
    public final BooleanProperty shootUp = new BooleanProperty("shoot-up", false);
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT"});
    public final ModeProperty showTarget = new ModeProperty("show-target", 0, new String[]{"NONE", "DEFAULT", "HUD"});

    private boolean isValidTarget(EntityFireball entityFireball) {
        if (entityFireball.getEntityBoundingBox().hasNaN()) {
            return false;
        }
        
        double distance = RotationUtil.distanceToEntity(entityFireball);
        double maxDistance = (double) this.range.getValue() + 3.0;
        
        if (this.predictive.getValue()) {
            double velocityTowardsPlayer = calculateVelocityTowardsPlayer(entityFireball);
            if (velocityTowardsPlayer > 0.2) {
                maxDistance += 1.5;
            }
        }
        
        return distance <= maxDistance && RotationUtil.angleToEntity(entityFireball) <= (float) this.fov.getValue();
    }
    
    private double calculateVelocityTowardsPlayer(EntityFireball fireball) {
        double deltaX = mc.thePlayer.posX - fireball.posX;
        double deltaY = mc.thePlayer.posY + mc.thePlayer.getEyeHeight() - fireball.posY;
        double deltaZ = mc.thePlayer.posZ - fireball.posZ;
        double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        
        if (distance < 0.01) {
            return 0.0;
        }
        
        double normalizedX = deltaX / distance;
        double normalizedY = deltaY / distance;
        double normalizedZ = deltaZ / distance;
        
        return fireball.motionX * normalizedX + fireball.motionY * normalizedY + fireball.motionZ * normalizedZ;
    }

    private void doAttackAnimation() {
        if (this.swing.getValue()) {
            mc.thePlayer.swingItem();
        } else {
            PacketUtil.sendPacket(new C0APacketAnimation());
        }
    }

    public AntiFireball() {
        super("AntiFireball", false);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            List<EntityFireball> fireballs = mc.theWorld
                    .loadedEntityList
                    .stream()
                    .filter(entity -> entity instanceof EntityFireball)
                    .map(entity -> (EntityFireball) entity)
                    .collect(Collectors.toList());
            this.farList.removeIf(entityFireball -> !fireballs.contains(entityFireball));
            this.nearList.removeIf(entityFireball -> !fireballs.contains(entityFireball));
            for (EntityFireball fireball : fireballs) {
                if (!this.farList.contains(fireball) && !this.nearList.contains(fireball)) {
                    if (RotationUtil.distanceToEntity(fireball) > 3.0) {
                        this.farList.add(fireball);
                    } else {
                        this.nearList.add(fireball);
                    }
                }
            }
            if (mc.thePlayer.capabilities.allowFlying) {
                this.target = null;
            } else {
                this.target = this.farList.stream().filter(this::isValidTarget).min(Comparator.comparingDouble(RotationUtil::distanceToEntity)).orElse(null);
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            EntityFireball fireball = this.target;
            if (TeamUtil.isEntityLoaded(fireball)) {
                AxisAlignedBB targetBox = getPredictedBox(fireball);
                float[] rotations = RotationUtil.getRotationsToBox(targetBox, event.getYaw(), event.getPitch(), 180.0F, 0.0F);
                
                if (this.rotations.getValue()
                        && !ItemUtil.isHoldingNonEmpty()
                        && !ItemUtil.isUsingBow()
                        && !ItemUtil.hasHoldItem()) {
                    event.setRotation(rotations[0], rotations[1], 0);
                    event.setPervRotation(this.moveFix.getValue() != 0 ? rotations[0] : mc.thePlayer.rotationYaw, 0);
                }
                
                if (!Myau.playerStateManager.attacking && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                    double distance = RotationUtil.distanceToEntity(this.target);
                    boolean shouldAttack = distance <= (double) this.range.getValue().floatValue();
                    
                    if (this.predictive.getValue()) {
                        double velocityMagnitude = Math.sqrt(
                            fireball.motionX * fireball.motionX + 
                            fireball.motionY * fireball.motionY + 
                            fireball.motionZ * fireball.motionZ
                        );
                        
                        if (velocityMagnitude > 0.1 && distance < 4.0) {
                            shouldAttack = true;
                        }
                    }
                    
                    if (shouldAttack) {
                        this.doAttackAnimation();
                        
                        if (this.shootUp.getValue()) {
                            // Modify fireball motion to shoot straight up (90 degrees)
                            this.target.motionX = 0.0;
                            this.target.motionY = 1.0;
                            this.target.motionZ = 0.0;
                        }
                        
                        PacketUtil.sendPacket(new C02PacketUseEntity(this.target, Action.ATTACK));
                        PlayerUtil.attackEntity(this.target);
                        lastHitTime = System.currentTimeMillis();
                        consecutiveMisses = 0;
                    } else if (System.currentTimeMillis() - lastHitTime > 500) {
                        consecutiveMisses++;
                    }
                }
            }
        }
    }
    
    private AxisAlignedBB getPredictedBox(EntityFireball fireball) {
        if (!this.predictive.getValue()) {
            return fireball.getEntityBoundingBox();
        }
        
        float predictionTicks = this.prediction.getValue();
        double predX = fireball.posX + fireball.motionX * predictionTicks;
        double predY = fireball.posY + fireball.motionY * predictionTicks;
        double predZ = fireball.posZ + fireball.motionZ * predictionTicks;
        
        double width = (fireball.getEntityBoundingBox().maxX - fireball.getEntityBoundingBox().minX) / 2.0;
        double height = fireball.getEntityBoundingBox().maxY - fireball.getEntityBoundingBox().minY;
        
        return new AxisAlignedBB(
            predX - width, predY, predZ - width,
            predX + width, predY + height, predZ + width
        );
    }

    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (this.isEnabled()) {
            if (this.moveFix.getValue() == 1
                    && RotationState.isActived()
                    && RotationState.getPriority() == 0.0F
                    && MoveUtil.isForwardPressed()) {
                MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
            }
        }
    }

    @EventTarget
    public void onRender(Render3DEvent event) {
        if (this.isEnabled()) {
            if (this.showTarget.getValue() != 0 && TeamUtil.isEntityLoaded(this.target)) {
                Color color = new Color(-1);
                switch (this.showTarget.getValue()) {
                    case 1:
                        double dist = (this.target.posX - this.target.lastTickPosX) * (mc.thePlayer.posX - this.target.posX)
                                + (this.target.posY - this.target.lastTickPosY)
                                * (mc.thePlayer.posY + (double) mc.thePlayer.getEyeHeight() - this.target.posY - (double) this.target.height / 2.0)
                                + (this.target.posZ - this.target.lastTickPosZ) * (mc.thePlayer.posZ - this.target.posZ);
                        if (dist < 0.0) {
                            color = new Color(16733525);
                        } else {
                            color = new Color(5635925);
                        }
                        break;
                    case 2:
                        color = ((HUD) Myau.moduleManager.modules.get(HUD.class)).getColor(System.currentTimeMillis());
                }
                RenderUtil.enableRenderState();
                RenderUtil.drawEntityBox(this.target, color.getRed(), color.getGreen(), color.getBlue());
                RenderUtil.disableRenderState();
            }
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.farList.clear();
        this.nearList.clear();
    }
}
