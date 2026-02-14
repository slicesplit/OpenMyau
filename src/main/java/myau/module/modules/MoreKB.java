package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import myau.event.EventTarget;
import myau.events.AttackEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

/**
 * MoreKB - BRUTAL Knockback Amplifier
 * 
 * Makes enemies take MAXIMUM knockback while staying completely legit.
 * Uses sprint manipulation techniques that top PvPers use manually.
 * 
 * DEFAULT: LEGIT_FAST mode - Best balance of power and safety
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class MoreKB extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // ==================== BRUTAL DEFAULT SETTINGS ====================
    
    // Mode - DEFAULT: LEGIT_FAST (fastest legit mode)
    public final ModeProperty mode = new ModeProperty("mode", 1, 
        new String[]{"LEGIT", "LEGIT_FAST", "LEGIT_BRUTAL", "LESS_PACKET", "PACKET", "DOUBLE_PACKET"});
    
    // Intelligent - DEFAULT: ON (only KB when facing you)
    public final BooleanProperty intelligent = new BooleanProperty("intelligent", true);
    
    // Angle Threshold - DEFAULT: 100° (stricter = more KB)
    public final IntProperty maxAngle = new IntProperty("max-angle", 100, 60, 180);
    
    // Only Ground - DEFAULT: OFF (works in air too for combos)
    public final BooleanProperty onlyGround = new BooleanProperty("only-ground", false);
    
    // Only When Sprinting - DEFAULT: ON (legit, natural)
    public final BooleanProperty requireSprint = new BooleanProperty("require-sprint", true);
    
    // Check Enemy Hurt Time - DEFAULT: ON (perfect timing)
    public final BooleanProperty checkHurtTime = new BooleanProperty("check-hurt-time", true);
    
    // ==================== STATE TRACKING ====================
    
    private boolean shouldSprintReset;
    private EntityLivingBase target;
    private long lastResetTime = 0L;

    public MoreKB() {
        super("MoreKB", false);
        this.shouldSprintReset = false;
        this.target = null;
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        Entity targetEntity = event.getTarget();
        if (targetEntity != null && targetEntity instanceof EntityLivingBase) {
            this.target = (EntityLivingBase) targetEntity;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        
        // ===== MODE 1: LEGIT_FAST (DEFAULT) =====
        // Fastest legit mode - resets sprint internally without packets
        if (this.mode.getValue() == 1) {
            if (this.target != null && this.isMoving()) {
                // Check conditions
                if (!checkConditions(this.target)) {
                    this.target = null;
                    return;
                }
                
                // BRUTAL: Reset sprint on every hit for max KB
                if ((this.onlyGround.getValue() && mc.thePlayer.onGround) || !this.onlyGround.getValue()) {
                    mc.thePlayer.sprintingTicksLeft = 0;
                    lastResetTime = System.currentTimeMillis();
                }
                this.target = null;
            }
            return;
        }
        
        // ===== OTHER MODES: Packet-based for max KB =====
        EntityLivingBase entity = null;
        if (mc.objectMouseOver != null && 
            mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && 
            mc.objectMouseOver.entityHit instanceof EntityLivingBase) {
            entity = (EntityLivingBase) mc.objectMouseOver.entityHit;
        }
        
        if (entity == null) {
            return;
        }
        
        // Check conditions
        if (!checkConditions(entity)) {
            return;
        }
        
        // Check hurt time for perfect timing
        if (this.checkHurtTime.getValue() && entity.hurtTime == 10) {
            executeSprintReset();
        } else if (!this.checkHurtTime.getValue()) {
            // Always reset if hurt time check is disabled
            executeSprintReset();
        }
    }
    
    /**
     * BRUTAL CONDITIONS: Only KB when it's effective
     */
    private boolean checkConditions(EntityLivingBase entity) {
        // Check sprint requirement
        if (requireSprint.getValue() && !mc.thePlayer.isSprinting()) {
            return false;
        }
        
        // Intelligent angle check - only KB enemies facing you
        if (intelligent.getValue()) {
            double x = mc.thePlayer.posX - entity.posX;
            double z = mc.thePlayer.posZ - entity.posZ;
            float calcYaw = (float) (Math.atan2(z, x) * 180.0 / Math.PI - 90.0);
            float diffY = Math.abs(MathHelper.wrapAngleTo180_float(calcYaw - entity.rotationYawHead));
            
            // BRUTAL: Only hit if enemy is facing you (they take more KB)
            if (diffY > maxAngle.getValue()) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Execute sprint reset based on mode
     */
    private void executeSprintReset() {
        switch (this.mode.getValue()) {
            case 0: // LEGIT - Simple double sprint
                this.shouldSprintReset = true;
                if (mc.thePlayer.isSprinting()) {
                    mc.thePlayer.setSprinting(false);
                    mc.thePlayer.setSprinting(true);
                }
                this.shouldSprintReset = false;
                lastResetTime = System.currentTimeMillis();
                break;
                
            case 1: // LEGIT_FAST - Fast but still legit
                this.shouldSprintReset = true;
                if (mc.thePlayer.isSprinting()) {
                    mc.thePlayer.setSprinting(false);
                    mc.thePlayer.setSprinting(true);
                }
                this.shouldSprintReset = false;
                lastResetTime = System.currentTimeMillis();
                break;
                
            case 2: // LEGIT_BRUTAL - EXTREMELY DANGEROUS but 100% LEGIT
                // Perfect sprint reset timing that looks completely human
                // Uses triple-toggle technique for MAXIMUM knockback
                this.shouldSprintReset = true;
                
                if (mc.thePlayer.isSprinting()) {
                    // Triple toggle: Stop -> Start -> Stop -> Start
                    // This creates maximum momentum transfer while being 100% client-side
                    mc.thePlayer.setSprinting(false);
                    mc.thePlayer.setSprinting(true);
                    mc.thePlayer.setSprinting(false);
                    mc.thePlayer.setSprinting(true);
                }
                
                this.shouldSprintReset = false;
                lastResetTime = System.currentTimeMillis();
                break;
                
            case 3: // LESS_PACKET - One packet sprint reset
                // FIX: Only toggle sprint if currently sprinting to avoid simulation desync
                if (mc.thePlayer.isSprinting()) {
                    mc.thePlayer.setSprinting(false);
                    mc.getNetHandler().addToSendQueue(
                        new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING)
                    );
                    mc.thePlayer.setSprinting(true);
                }
                lastResetTime = System.currentTimeMillis();
                break;
                
            case 4: // PACKET - Double packet (more KB)
                // FIX: Only send packets if sprinting to match client state
                if (mc.thePlayer.isSprinting()) {
                    mc.thePlayer.sendQueue.addToSendQueue(
                        new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING)
                    );
                }
                mc.thePlayer.sendQueue.addToSendQueue(
                    new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING)
                );
                mc.thePlayer.setSprinting(true);
                lastResetTime = System.currentTimeMillis();
                break;
                
            case 5: // DOUBLE_PACKET - Maximum KB (4 packets)
                // FIX: Only send packets if sprinting to match client state
                if (mc.thePlayer.isSprinting()) {
                    mc.thePlayer.sendQueue.addToSendQueue(
                        new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING)
                    );
                    mc.thePlayer.sendQueue.addToSendQueue(
                        new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING)
                    );
                    mc.thePlayer.sendQueue.addToSendQueue(
                        new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING)
                    );
                }
                mc.thePlayer.sendQueue.addToSendQueue(
                    new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING)
                );
                mc.thePlayer.setSprinting(true);
                lastResetTime = System.currentTimeMillis();
                break;
        }
    }

    private boolean isMoving() {
        return mc.thePlayer.moveForward != 0.0F || mc.thePlayer.moveStrafing != 0.0F;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
