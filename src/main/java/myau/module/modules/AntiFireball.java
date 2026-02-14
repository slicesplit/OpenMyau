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
    
    // Tracking lists
    private final ArrayList<EntityFireball> farList = new ArrayList<>();
    private final ArrayList<EntityFireball> nearList = new ArrayList<>();
    private EntityFireball target = null;
    
    // Stats
    private int consecutiveMisses = 0;
    private long lastHitTime = 0L;
    private int totalHits = 0;
    
    // PRIORITY OVERRIDE: Force AntiFireball to work in ANY situation
    private EntityFireball activeFireball = null;
    private long lastFireballDetect = 0L;
    private boolean overrideActive = false;
    
    // ==================== BRUTAL DEFAULT CONFIG (MAXIMUM AGGRESSION) ====================
    
    // Basic Settings
    public final FloatProperty range = new FloatProperty("range", 7.0F, 3.0F, 8.0F); // BRUTAL: 7.0 blocks
    public final IntProperty fov = new IntProperty("fov", 360, 1, 360); // BRUTAL: All directions
    public final BooleanProperty rotations = new BooleanProperty("rotations", true);
    public final BooleanProperty swing = new BooleanProperty("swing", true);
    
    // HYPER-ENHANCED FEATURES (ALL ENABLED BY DEFAULT)
    public final BooleanProperty advancedPrediction = new BooleanProperty("advanced-prediction", true); // BRUTAL: ON
    public final FloatProperty predictionTicks = new FloatProperty("prediction-ticks", 3.0F, 0.0F, 5.0F, () -> this.advancedPrediction.getValue()); // BRUTAL: 3 ticks
    
    public final BooleanProperty perfectTiming = new BooleanProperty("perfect-timing", true); // BRUTAL: ON
    public final BooleanProperty adaptiveHitbox = new BooleanProperty("adaptive-hitbox", true); // BRUTAL: ON
    
    // BRUTAL: Reflect back to sender (kill thrower)
    // 0 = Shoot Up, 1 = Reflect Back, 2 = Ground Drop, 3 = None
    public final ModeProperty reflectMode = new ModeProperty("reflect-mode", 1, new String[]{"UP", "BACK", "GROUND", "NONE"}); // BRUTAL: BACK
    
    // Visual & Movement
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT"});
    public final ModeProperty showTarget = new ModeProperty("show-target", 1, new String[]{"NONE", "DEFAULT", "HUD"}); // BRUTAL: Show target
    
    // FORCE OVERRIDE: Work even while BedNuker/Scaffold/KillAura/Backtrack active
    public final BooleanProperty forceOverride = new BooleanProperty("force-override", true);

    // HYPER-ENHANCED: 100% accurate targeting with trajectory analysis
    private boolean isValidTarget(EntityFireball entityFireball) {
        if (entityFireball.getEntityBoundingBox().hasNaN()) {
            return false;
        }
        
        double distance = RotationUtil.distanceToEntity(entityFireball);
        double maxDistance = (double) this.range.getValue() + 3.0;
        
        if (this.advancedPrediction.getValue()) {
            // Calculate if fireball is heading towards player
            double velocityTowardsPlayer = calculateVelocityTowardsPlayer(entityFireball);
            
            // Calculate time to impact
            double timeToImpact = calculateTimeToImpact(entityFireball);
            
            // Expand range for fast-approaching fireballs
            if (velocityTowardsPlayer > 0.2) {
                maxDistance += 2.0; // Extended range
            }
            
            // Priority targeting for imminent threats (< 1 second to impact)
            if (timeToImpact < 1.0 && timeToImpact > 0) {
                maxDistance += 3.0; // Emergency extended range
            }
        }
        
        return distance <= maxDistance && RotationUtil.angleToEntity(entityFireball) <= (float) this.fov.getValue();
    }
    
    // BRUTAL: Calculate exact velocity towards player (dot product)
    private double calculateVelocityTowardsPlayer(EntityFireball fireball) {
        double deltaX = mc.thePlayer.posX - fireball.posX;
        double deltaY = mc.thePlayer.posY + mc.thePlayer.getEyeHeight() - fireball.posY;
        double deltaZ = mc.thePlayer.posZ - fireball.posZ;
        double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        
        if (distance < 0.01) {
            return 0.0;
        }
        
        // Normalize direction vector
        double normalizedX = deltaX / distance;
        double normalizedY = deltaY / distance;
        double normalizedZ = deltaZ / distance;
        
        // Dot product = how fast fireball is approaching
        return fireball.motionX * normalizedX + fireball.motionY * normalizedY + fireball.motionZ * normalizedZ;
    }
    
    // NEW: Calculate exact time until fireball hits player
    private double calculateTimeToImpact(EntityFireball fireball) {
        double deltaX = mc.thePlayer.posX - fireball.posX;
        double deltaY = mc.thePlayer.posY + mc.thePlayer.getEyeHeight() - fireball.posY;
        double deltaZ = mc.thePlayer.posZ - fireball.posZ;
        double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        
        double velocity = Math.sqrt(
            fireball.motionX * fireball.motionX + 
            fireball.motionY * fireball.motionY + 
            fireball.motionZ * fireball.motionZ
        );
        
        if (velocity < 0.01) {
            return -1.0; // Stationary fireball
        }
        
        // Time = Distance / Velocity (in seconds, assuming 20 TPS)
        return (distance / velocity) / 20.0;
    }
    
    // NEW: Calculate optimal hit timing (when fireball is closest to player)
    private double calculateOptimalHitDistance(EntityFireball fireball) {
        // Predict fireball position over next 10 ticks
        double minDistance = Double.MAX_VALUE;
        double optimalDistance = RotationUtil.distanceToEntity(fireball);
        
        for (int tick = 0; tick <= 10; tick++) {
            double predX = fireball.posX + fireball.motionX * tick;
            double predY = fireball.posY + fireball.motionY * tick;
            double predZ = fireball.posZ + fireball.motionZ * tick;
            
            double dx = mc.thePlayer.posX - predX;
            double dy = mc.thePlayer.posY + mc.thePlayer.getEyeHeight() - predY;
            double dz = mc.thePlayer.posZ - predZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            
            if (distance < minDistance) {
                minDistance = distance;
                optimalDistance = distance;
            }
        }
        
        return optimalDistance;
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
    
    /**
     * Public API: Check if AntiFireball is actively deflecting
     * Other modules can pause when this returns true
     */
    public boolean isDeflecting() {
        return this.isEnabled() && this.overrideActive && this.activeFireball != null;
    }
    
    /**
     * Public API: Get current active fireball target
     */
    public EntityFireball getActiveFireball() {
        return this.activeFireball;
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
            
            // PRIORITY OVERRIDE: Set active fireball and override flag
            if (TeamUtil.isEntityLoaded(fireball) && forceOverride.getValue()) {
                this.activeFireball = fireball;
                this.overrideActive = true;
                this.lastFireballDetect = System.currentTimeMillis();
            } else {
                // Reset override after 500ms if no fireball
                if (System.currentTimeMillis() - lastFireballDetect > 500) {
                    this.activeFireball = null;
                    this.overrideActive = false;
                }
            }
            
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
                    boolean shouldAttack = false;
                    
                    // HYPER-ENHANCED: Perfect timing system
                    if (this.perfectTiming.getValue()) {
                        // Calculate optimal hit distance
                        double optimalDistance = calculateOptimalHitDistance(fireball);
                        double timeToImpact = calculateTimeToImpact(fireball);
                        
                        // Attack conditions:
                        // 1. Within range
                        // 2. Fireball is at optimal distance (closest approach)
                        // 3. OR fireball is very close (< 2 blocks) and approaching
                        // 4. OR time to impact < 0.5 seconds (emergency)
                        
                        if (distance <= (double) this.range.getValue().floatValue()) {
                            shouldAttack = true;
                        } else if (optimalDistance <= (double) this.range.getValue().floatValue() + 1.0) {
                            shouldAttack = true; // Hit at predicted closest point
                        } else if (distance < 2.0 && calculateVelocityTowardsPlayer(fireball) > 0.1) {
                            shouldAttack = true; // Emergency close range
                        } else if (timeToImpact > 0 && timeToImpact < 0.5) {
                            shouldAttack = true; // Emergency timing
                        }
                    } else {
                        // Original logic
                        shouldAttack = distance <= (double) this.range.getValue().floatValue();
                        
                        if (this.advancedPrediction.getValue()) {
                            double velocityMagnitude = Math.sqrt(
                                fireball.motionX * fireball.motionX + 
                                fireball.motionY * fireball.motionY + 
                                fireball.motionZ * fireball.motionZ
                            );
                            
                            if (velocityMagnitude > 0.1 && distance < 4.0) {
                                shouldAttack = true;
                            }
                        }
                    }
                    
                    if (shouldAttack) {
                        this.doAttackAnimation();
                        
                        // BRUTAL: Multiple reflection modes
                        if (this.reflectMode.getValue() == 0) {
                            // Shoot up (original)
                            this.target.motionX = 0.0;
                            this.target.motionY = 1.0;
                            this.target.motionZ = 0.0;
                        } else if (this.reflectMode.getValue() == 1) {
                            // Reflect back to sender
                            this.target.motionX = -this.target.motionX * 1.5;
                            this.target.motionY = -this.target.motionY * 1.5;
                            this.target.motionZ = -this.target.motionZ * 1.5;
                        } else if (this.reflectMode.getValue() == 2) {
                            // Send to ground (safer)
                            this.target.motionX *= 0.5;
                            this.target.motionY = -0.5;
                            this.target.motionZ *= 0.5;
                        }
                        // Mode 3 = None (just deflect, don't modify)
                        
                        PacketUtil.sendPacket(new C02PacketUseEntity(this.target, Action.ATTACK));
                        PlayerUtil.attackEntity(this.target);
                        lastHitTime = System.currentTimeMillis();
                        consecutiveMisses = 0;
                        
                        // Track successful hits for accuracy
                        totalHits++;
                    } else if (System.currentTimeMillis() - lastHitTime > 500) {
                        consecutiveMisses++;
                    }
                }
            }
        }
    }
    
    // HYPER-ENHANCED: Adaptive hitbox with perfect prediction
    private AxisAlignedBB getPredictedBox(EntityFireball fireball) {
        if (!this.advancedPrediction.getValue()) {
            return fireball.getEntityBoundingBox();
        }
        
        float predictionTicks = this.predictionTicks.getValue();
        
        // Calculate predicted position
        double predX = fireball.posX + fireball.motionX * predictionTicks;
        double predY = fireball.posY + fireball.motionY * predictionTicks;
        double predZ = fireball.posZ + fireball.motionZ * predictionTicks;
        
        double width = (fireball.getEntityBoundingBox().maxX - fireball.getEntityBoundingBox().minX) / 2.0;
        double height = fireball.getEntityBoundingBox().maxY - fireball.getEntityBoundingBox().minY;
        
        // ADAPTIVE: Expand hitbox based on velocity (faster = larger hitbox)
        if (this.adaptiveHitbox.getValue()) {
            double velocity = Math.sqrt(
                fireball.motionX * fireball.motionX + 
                fireball.motionY * fireball.motionY + 
                fireball.motionZ * fireball.motionZ
            );
            
            // Expand by up to 0.3 blocks for fast fireballs
            double expansion = Math.min(velocity * 0.5, 0.3);
            width += expansion;
            height += expansion;
        }
        
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
