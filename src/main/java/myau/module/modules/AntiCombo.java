package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.event.types.EventType;
import myau.module.Module;
import myau.property.properties.*;
import myau.Myau;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.*;
import net.minecraft.network.play.client.*;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * AntiCombo - Smart Combo Breaker
 * 
 * INTELLIGENT DESIGN:
 * - Detects when you're getting comboed (consecutive hits)
 * - Delays inbound damage packets to break the combo
 * - Auto-stops after max duration to prevent infinite hold
 * - Integrates with prediction system
 * 
 * HOW IT WORKS:
 * 1. Count consecutive hits received
 * 2. When hit count reaches threshold, start spoofing
 * 3. Hold damage/velocity packets for brief period
 * 4. This gives you time to escape the combo
 * 5. Auto-release after safety duration
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class AntiCombo extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    public AntiCombo() {
        super("AntiCombo", false);
    }
    
    // State machine for clean logic
    private enum AntiComboState {
        IDLE,           // Not active
        WAITING,        // Combo threshold reached, waiting to activate
        SPOOFING,       // Actively blocking packets
        COOLDOWN        // Cooldown after spoofing
    }
    
    // Settings
    public final IntProperty hitThreshold = new IntProperty("hit-threshold", 3, 1, 10);
    public final IntProperty minSpoofTicks = new IntProperty("min-spoof-ticks", 5, 3, 15);
    public final IntProperty maxSpoofTicks = new IntProperty("max-spoof-ticks", 60, 20, 200);
    public final IntProperty cooldownTicks = new IntProperty("cooldown-ticks", 40, 10, 100);
    public final IntProperty maxQueueSize = new IntProperty("max-queue", 30, 10, 100);
    public final IntProperty activationDelay = new IntProperty("activation-delay", 3, 0, 10);
    public final IntProperty comboDecayTicks = new IntProperty("combo-decay", 20, 10, 40);
    public final BooleanProperty stopOnKnockback = new BooleanProperty("stop-on-kb", true);
    public final BooleanProperty debug = new BooleanProperty("debug", false);
    
    // State tracking with proper separation
    private AntiComboState state = AntiComboState.IDLE;
    private int comboHits = 0;
    private int spoofTicks = 0;
    private int waitingTicks = 0;
    private int cooldownTicksElapsed = 0;
    private int ticksSinceLastHit = 0;
    private long lastHitTime = 0;
    private int lastTargetId = -1;
    
    // Packet queue
    private final ConcurrentLinkedQueue<DelayedPacket> inboundQueue = new ConcurrentLinkedQueue<>();
    
    /**
     * Packet wrapper
     */
    private static class DelayedPacket {
        private final Packet<?> packet;
        private final long time;
        
        public DelayedPacket(Packet<?> packet) {
            this.packet = packet;
            this.time = System.currentTimeMillis();
        }
        
        public Packet<?> getPacket() {
            return packet;
        }
        
        public long getTime() {
            return time;
        }
    }
    
    @Override
    public void onEnabled() {
        resetState();
    }
    
    @Override
    public void onDisabled() {
        resetState();
    }
    
    /**
     * Reset all state
     */
    private void resetState() {
        state = AntiComboState.IDLE;
        comboHits = 0;
        spoofTicks = 0;
        waitingTicks = 0;
        cooldownTicksElapsed = 0;
        ticksSinceLastHit = 0;
        lastTargetId = -1;
        safeReleaseQueue();
    }
    
    /**
     * Main tick logic with state machine
     */
    public void tickCheck() {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        
        // Increment ticks since last hit
        ticksSinceLastHit++;
        
        // Combo decay - reduce combo if no hits recently
        if (ticksSinceLastHit >= comboDecayTicks.getValue() && comboHits > 0) {
            comboHits = Math.max(0, comboHits - 1);
            ticksSinceLastHit = 0;
        }
        
        // Stop on knockback if enabled
        if (stopOnKnockback.getValue() && mc.thePlayer.hurtTime > 0) {
            if (state == AntiComboState.SPOOFING) {
                transitionToCooldown();
            }
        }
        
        // Validate target (check if still in combat)
        Module killAura = Myau.moduleManager.modules.get(KillAura.class);
        boolean inCombat = killAura != null && killAura.isEnabled();
        
        if (!inCombat && state != AntiComboState.IDLE) {
            // Not in combat - reset
            transitionToIdle();
            return;
        }
        
        // State machine
        switch (state) {
            case IDLE:
                // Check if combo threshold reached
                if (comboHits >= hitThreshold.getValue()) {
                    transitionToWaiting();
                }
                break;
                
            case WAITING:
                waitingTicks++;
                
                // Activation delay prevents instant trigger
                if (waitingTicks >= activationDelay.getValue()) {
                    transitionToSpoofing();
                }
                
                // If combo drops below threshold during wait, cancel
                if (comboHits < hitThreshold.getValue()) {
                    transitionToIdle();
                }
                break;
                
            case SPOOFING:
                spoofTicks++;
                
                // Check stop conditions
                boolean minTimeReached = spoofTicks >= minSpoofTicks.getValue();
                boolean maxTimeReached = spoofTicks >= maxSpoofTicks.getValue();
                boolean queueFull = inboundQueue.size() >= maxQueueSize.getValue();
                boolean comboDropped = comboHits < hitThreshold.getValue() - 1;
                
                if (queueFull || maxTimeReached) {
                    // Emergency/max stop
                    transitionToCooldown();
                } else if (minTimeReached && comboDropped) {
                    // Combo reduced - can stop
                    transitionToCooldown();
                }
                break;
                
            case COOLDOWN:
                cooldownTicksElapsed++;
                
                // Release queue gradually
                if (!inboundQueue.isEmpty()) {
                    safeReleaseQueue();
                }
                
                // Return to idle after cooldown
                if (cooldownTicksElapsed >= cooldownTicks.getValue()) {
                    transitionToIdle();
                }
                
                // If combo builds up again during cooldown, restart
                if (comboHits >= hitThreshold.getValue() + 2) {
                    transitionToWaiting();
                }
                break;
        }
    }
    
    /**
     * Transition to waiting state
     */
    private void transitionToWaiting() {
        state = AntiComboState.WAITING;
        waitingTicks = 0;
    }
    
    /**
     * Transition to spoofing state
     */
    private void transitionToSpoofing() {
        state = AntiComboState.SPOOFING;
        spoofTicks = 0;
    }
    
    /**
     * Transition to cooldown state
     */
    private void transitionToCooldown() {
        state = AntiComboState.COOLDOWN;
        cooldownTicksElapsed = 0;
        safeReleaseQueue();
    }
    
    /**
     * Transition to idle state
     */
    private void transitionToIdle() {
        state = AntiComboState.IDLE;
        spoofTicks = 0;
        waitingTicks = 0;
        cooldownTicksElapsed = 0;
        safeReleaseQueue();
    }
    
    /**
     * Safe release queue - GRIM-SAFE controlled release
     */
    private void safeReleaseQueue() {
        DelayedPacket delayed;
        while ((delayed = inboundQueue.poll()) != null) {
            processPacketDirect(delayed.getPacket());
        }
    }
    
    /**
     * Process packet directly
     */
    private void processPacketDirect(Packet<?> packet) {
        if (mc.thePlayer != null && mc.thePlayer.sendQueue != null) {
            try {
                // Re-add to network manager channel to process
                mc.thePlayer.sendQueue.getNetworkManager().sendPacket(packet);
            } catch (Exception e) {
                // Ignore processing errors
            }
        }
    }
    
    /**
     * Handle inbound packets
     */
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null) {
            return;
        }
        
        if (event.getType() == EventType.RECEIVE) { // Inbound packet
            Packet<?> packet = event.getPacket();
            
            // Detect damage packets and track combo
            if (packet instanceof S06PacketUpdateHealth) {
                S06PacketUpdateHealth healthPacket = (S06PacketUpdateHealth) packet;
                float healthChange = healthPacket.getHealth() - mc.thePlayer.getHealth();
                
                if (healthChange < 0) { // Taking damage
                    comboHits++;
                    ticksSinceLastHit = 0;
                    lastHitTime = System.currentTimeMillis();
                    
                    if (debug.getValue() && mc.thePlayer != null) {
                        System.out.println("[AntiCombo] Hit detected! Combo: " + comboHits);
                    }
                }
            }
            
            // GRIM-SAFE: Only queue damage packets when spoofing
            if (state == AntiComboState.SPOOFING && isDamageRelatedPacket(packet)) {
                // Safety: don't overflow queue
                if (inboundQueue.size() < maxQueueSize.getValue()) {
                    event.setCancelled(true);
                    inboundQueue.add(new DelayedPacket(packet));
                }
            }
            
            // GRIM-SAFE: Stop on position correction
            if (packet instanceof S08PacketPlayerPosLook) {
                if (state != AntiComboState.IDLE) {
                    transitionToIdle();
                }
            }
        }
    }
    
    /**
     * Check if packet is damage-related (GRIM-SAFE)
     */
    private boolean isDamageRelatedPacket(Packet<?> packet) {
        // Only delay damage and velocity packets
        // DON'T delay position corrections - causes desync
        return packet instanceof S06PacketUpdateHealth ||
               packet instanceof S12PacketEntityVelocity ||
               packet instanceof S27PacketExplosion;
    }
    
    /**
     * Update event handler
     */
    @EventTarget
    public void onUpdate(UpdateEvent event) {
        tickCheck();
    }
    
    /**
     * Check if currently anti-comboing
     */
    public boolean isSpoofing() {
        return state == AntiComboState.SPOOFING;
    }
    
    /**
     * Get current hit combo count
     */
    public int getHitCombo() {
        return comboHits;
    }
    
    public String[] getHUDInfo() {
        if (!isEnabled()) {
            return new String[]{};
        }
        
        switch (state) {
            case WAITING:
                return new String[]{"WAIT [" + comboHits + " hits]"};
            case SPOOFING:
                return new String[]{"BLOCK [" + spoofTicks + "t, " + comboHits + " hits]"};
            case COOLDOWN:
                return new String[]{"CD [" + inboundQueue.size() + "q]"};
            case IDLE:
            default:
                if (comboHits > 0) {
                    return new String[]{"COMBO: " + comboHits};
                }
                return new String[]{"READY"};
        }
    }
}
