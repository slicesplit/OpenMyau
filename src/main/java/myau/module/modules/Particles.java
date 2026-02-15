package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.module.Module;
import myau.property.properties.*;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumParticleTypes;

/**
 * Particles - Customize particle effects for attacks
 * 
 * Features:
 * - Particle multiplier (1-100x) with automatic performance optimization
 * - Always show critical hit particles
 * - Always show sharpness particles
 * - Invulnerability detection (don't show particles if target is invulnerable)
 */
@ModuleInfo(category = ModuleCategory.RENDER)
public class Particles extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Settings
    public final IntProperty multiplier = new IntProperty("Multiplier", 1, 1, 100);
    public final BooleanProperty alwaysCriticals = new BooleanProperty("Always-Criticals", true);
    public final BooleanProperty alwaysSharpness = new BooleanProperty("Always-Sharpness", true);
    public final BooleanProperty checkInvulnerability = new BooleanProperty("Check-Invulnerability", true);
    public final BooleanProperty performanceMode = new BooleanProperty("Performance-Mode", true);
    
    // Performance tracking
    private long lastParticleTime = 0;
    private int particlesThisSecond = 0;
    private long lastSecondReset = 0;
    private int maxParticlesPerSecond = 2000; // Dynamic limit
    
    public Particles() {
        super("Particles", false);
    }
    
    @Override
    public void onEnabled() {
        lastParticleTime = 0;
        particlesThisSecond = 0;
        lastSecondReset = System.currentTimeMillis();
        updatePerformanceSettings();
    }
    
    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        
        Entity target = event.getTarget();
        if (!(target instanceof EntityLivingBase)) {
            return;
        }
        
        EntityLivingBase livingTarget = (EntityLivingBase) target;
        
        // Check invulnerability
        if (checkInvulnerability.getValue() && livingTarget.hurtTime > 0) {
            return; // Target is invulnerable, don't show particles
        }
        
        // Update performance tracking
        updatePerformanceLimits();
        
        // Calculate effective multiplier based on performance
        int effectiveMultiplier = getEffectiveMultiplier();
        
        if (effectiveMultiplier <= 0) {
            return; // Performance limit reached
        }
        
        // Spawn particles
        try {
            spawnAttackParticles(livingTarget, effectiveMultiplier);
        } catch (Exception e) {
            // Catch any particle spawning errors to prevent crashes
        }
    }
    
    /**
     * Spawn attack particles with the given multiplier
     */
    private void spawnAttackParticles(EntityLivingBase target, int multiplier) {
        if (mc.theWorld == null || target == null) {
            return;
        }
        
        // Show critical particles
        if (alwaysCriticals.getValue() || mc.thePlayer.fallDistance > 0.0F) {
            for (int i = 0; i < multiplier; i++) {
                if (!canSpawnMoreParticles()) break;
                
                mc.theWorld.spawnParticle(
                    EnumParticleTypes.CRIT,
                    target.posX + (Math.random() - 0.5) * target.width,
                    target.posY + Math.random() * target.height,
                    target.posZ + (Math.random() - 0.5) * target.width,
                    (Math.random() - 0.5) * 0.5,
                    Math.random() * 0.5,
                    (Math.random() - 0.5) * 0.5
                );
                particlesThisSecond++;
            }
        }
        
        // Show sharpness particles (magic crit)
        if (alwaysSharpness.getValue() || isHoldingEnchantedWeapon()) {
            for (int i = 0; i < multiplier; i++) {
                if (!canSpawnMoreParticles()) break;
                
                mc.theWorld.spawnParticle(
                    EnumParticleTypes.CRIT_MAGIC,
                    target.posX + (Math.random() - 0.5) * target.width,
                    target.posY + Math.random() * target.height,
                    target.posZ + (Math.random() - 0.5) * target.width,
                    (Math.random() - 0.5) * 0.5,
                    Math.random() * 0.5,
                    (Math.random() - 0.5) * 0.5
                );
                particlesThisSecond++;
            }
        }
    }
    
    /**
     * Check if we can spawn more particles based on performance limits
     */
    private boolean canSpawnMoreParticles() {
        if (!performanceMode.getValue()) {
            return true; // No limits when performance mode is off
        }
        
        return particlesThisSecond < maxParticlesPerSecond;
    }
    
    /**
     * Get effective multiplier based on performance settings
     */
    private int getEffectiveMultiplier() {
        int requestedMultiplier = multiplier.getValue();
        
        if (!performanceMode.getValue()) {
            return requestedMultiplier; // No performance limits
        }
        
        // Calculate remaining budget for this second
        int remainingBudget = maxParticlesPerSecond - particlesThisSecond;
        
        if (remainingBudget <= 0) {
            return 0; // Budget exhausted
        }
        
        // Return the smaller of requested multiplier and remaining budget
        return Math.min(requestedMultiplier, remainingBudget / 2); // Divide by 2 for crit + sharpness
    }
    
    /**
     * Update performance limits based on system capability
     */
    private void updatePerformanceLimits() {
        long currentTime = System.currentTimeMillis();
        
        // Reset counter every second
        if (currentTime - lastSecondReset >= 1000) {
            lastSecondReset = currentTime;
            
            // Adjust limits based on actual performance
            if (performanceMode.getValue()) {
                // Check FPS to determine if we should adjust limits
                int currentFps = Minecraft.getDebugFPS();
                
                if (currentFps >= 60) {
                    // High-end system - allow more particles
                    maxParticlesPerSecond = 3000;
                } else if (currentFps >= 30) {
                    // Mid-range system - moderate particles
                    maxParticlesPerSecond = 2000;
                } else {
                    // Potato system - limit particles
                    maxParticlesPerSecond = 1000;
                }
            } else {
                // No performance mode - allow maximum
                maxParticlesPerSecond = 10000;
            }
            
            particlesThisSecond = 0;
        }
    }
    
    /**
     * Update performance settings based on system capability
     */
    private void updatePerformanceSettings() {
        if (!performanceMode.getValue()) {
            return;
        }
        
        // Detect system capability based on initial FPS
        int currentFps = Minecraft.getDebugFPS();
        
        if (currentFps >= 60) {
            maxParticlesPerSecond = 3000; // High-end
        } else if (currentFps >= 30) {
            maxParticlesPerSecond = 2000; // Mid-range
        } else {
            maxParticlesPerSecond = 1000; // Potato
        }
    }
    
    /**
     * Check if player is holding an enchanted weapon
     */
    private boolean isHoldingEnchantedWeapon() {
        if (mc.thePlayer == null || mc.thePlayer.getHeldItem() == null) {
            return false;
        }
        
        return mc.thePlayer.getHeldItem().isItemEnchanted();
    }
    
    @Override
    public String[] getSuffix() {
        if (!isEnabled()) {
            return new String[]{};
        }
        
        return new String[]{multiplier.getValue() + "x"};
    }
}
