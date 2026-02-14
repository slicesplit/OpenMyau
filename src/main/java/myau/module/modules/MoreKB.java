package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.events.AttackEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.FloatProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C0BPacketEntityAction;

/**
 * MoreKB - GrimAC-Bypass Compatible Knockback Module
 * 
 * Uses prediction engine knowledge to bypass GrimAC's sprint detection.
 * 
 * How it bypasses Grim:
 * - Maintains proper sprint state transitions to avoid BadPacketsF
 * - Works with Grim's prediction engine (minAttackSlow/maxAttackSlow)
 * - All sprint resets are predicted by Grim's movement simulator
 * - No invalid state changes that trigger detection
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class MoreKB extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // ==================== SETTINGS ====================
    
    public final ModeProperty mode = new ModeProperty("Mode", 0, 
        new String[]{"Legit", "Packet", "Double-Packet", "Triple-Packet", "Extreme"});
    
    public final FloatProperty range = new FloatProperty("Range", 3.5f, 3.0f, 6.0f);
    public final IntProperty delay = new IntProperty("Delay", 0, 0, 500);
    public final BooleanProperty onlySprinting = new BooleanProperty("Only-Sprint", true);
    public final BooleanProperty onlyGround = new BooleanProperty("Only-Ground", false);
    public final BooleanProperty hurtTimeCheck = new BooleanProperty("Hurt-Time", false);
    
    // ==================== STATE ====================
    
    private EntityLivingBase lastTarget;
    private long lastKBTime = 0L;
    private boolean sprintState = false; // Track sprint state for Grim bypass

    public MoreKB() {
        super("MoreKB", false);
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        
        Entity target = event.getTarget();
        if (!(target instanceof EntityLivingBase)) {
            return;
        }
        
        EntityLivingBase entity = (EntityLivingBase) target;
        
        // Check delay
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastKBTime < delay.getValue()) {
            return;
        }
        
        // Check range
        double distance = mc.thePlayer.getDistanceToEntity(entity);
        if (distance > range.getValue()) {
            return;
        }
        
        // Check sprint requirement
        if (onlySprinting.getValue() && !mc.thePlayer.isSprinting()) {
            return;
        }
        
        // Check ground requirement
        if (onlyGround.getValue() && !mc.thePlayer.onGround) {
            return;
        }
        
        // Check hurt time - entity should be getting hit right now
        if (hurtTimeCheck.getValue() && entity.hurtTime > 0 && entity.hurtTime < 10) {
            return;
        }
        
        // Sync sprint state before applying KB
        sprintState = mc.thePlayer.isSprinting();
        
        // Execute Grim-compatible knockback
        applyKnockback();
        
        lastTarget = entity;
        lastKBTime = currentTime;
    }
    
    /**
     * Grim-bypass knockback implementation.
     * 
     * Key principles:
     * 1. Always maintain valid sprint state transitions
     * 2. Don't send duplicate START_SPRINTING when already sprinting
     * 3. Don't send duplicate STOP_SPRINTING when already stopped
     * 4. Grim's prediction engine will simulate the attack slow
     */
    private void applyKnockback() {
        switch (mode.getValue()) {
            case 0: // Legit
                // Client-side only - Grim doesn't see packets
                // Safest method, works everywhere
                if (mc.thePlayer.isSprinting()) {
                    mc.thePlayer.setSprinting(false);
                    mc.thePlayer.setSprinting(true);
                }
                break;
                
            case 1: // Packet
                // Single sprint reset with proper state management
                // Grim sees: lastSprinting = true, applies attack slow via prediction
                if (sprintState) {
                    sendSprintPacket(false);
                    sprintState = false;
                }
                sendSprintPacket(true);
                sprintState = true;
                mc.thePlayer.setSprinting(true);
                break;
                
            case 2: // Double-Packet
                // Double reset: STOP->START->STOP->START
                // Increases minAttackSlow/maxAttackSlow counters
                // Grim predicts this as valid movement
                if (sprintState) {
                    sendSprintPacket(false);
                    sprintState = false;
                }
                sendSprintPacket(true);
                sprintState = true;
                
                sendSprintPacket(false);
                sprintState = false;
                sendSprintPacket(true);
                sprintState = true;
                
                mc.thePlayer.setSprinting(true);
                break;
                
            case 3: // Triple-Packet
                // Triple reset for maximum attack slow
                // Grim's prediction engine handles up to 5 attack slows
                for (int i = 0; i < 3; i++) {
                    if (sprintState) {
                        sendSprintPacket(false);
                        sprintState = false;
                    }
                    sendSprintPacket(true);
                    sprintState = true;
                }
                mc.thePlayer.setSprinting(true);
                break;
                
            case 4: // Extreme
                // Combines client and server methods
                // Client: immediate local effect
                // Server: prediction engine simulates attack slow
                mc.thePlayer.setSprinting(false);
                mc.thePlayer.setSprinting(true);
                
                // Send 3 sprint resets (within Grim's 5 attack slow limit)
                for (int i = 0; i < 3; i++) {
                    if (sprintState) {
                        sendSprintPacket(false);
                        sprintState = false;
                    }
                    sendSprintPacket(true);
                    sprintState = true;
                }
                
                mc.thePlayer.setSprinting(true);
                break;
        }
    }
    
    /**
     * Send sprint packet with state tracking for Grim bypass
     */
    private void sendSprintPacket(boolean sprinting) {
        C0BPacketEntityAction.Action action = sprinting 
            ? C0BPacketEntityAction.Action.START_SPRINTING 
            : C0BPacketEntityAction.Action.STOP_SPRINTING;
        
        mc.thePlayer.sendQueue.addToSendQueue(
            new C0BPacketEntityAction(mc.thePlayer, action)
        );
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString()};
    }
}
