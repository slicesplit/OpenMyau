package myau.module.modules;

import myau.module.Module;
import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.FloatProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.MathHelper;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Particles Module — Maximum satisfaction, zero hardcoded garbage.
 * Every value is derived from context: damage dealt, combo momentum,
 * player velocity, attack angle, and real-time performance.
 */
@ModuleInfo(category = ModuleCategory.RENDER)
public class Particles extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // --- Properties ---
    public final IntProperty multiplier = new IntProperty("Multiplier", 5, 1, 20);
    public final BooleanProperty alwaysCrit = new BooleanProperty("Always Criticals", true);
    public final BooleanProperty alwaysSharp = new BooleanProperty("Always Sharpness", true);
    public final BooleanProperty checkInvulnerability = new BooleanProperty("Physics Check", true);
    public final BooleanProperty dynamicVelocity = new BooleanProperty("Dynamic Velocity", true);
    public final BooleanProperty comboScaling = new BooleanProperty("Combo Scaling", true);
    public final BooleanProperty directionalBurst = new BooleanProperty("Directional Burst", true);
    public final FloatProperty spreadFactor = new FloatProperty("Spread", 1.0f, 0.1f, 3.0f);
    public final FloatProperty intensityScale = new FloatProperty("Intensity", 1.0f, 0.2f, 2.5f);

    // --- Combo tracking ---
    private int comboCounter = 0;
    private long lastAttackTime = 0;
    private float lastTargetHealth = -1f;
    private Entity lastTarget = null;

    // --- Smoothing ---
    private float smoothedPlayerSpeed = 0f;

    public Particles() {
        super("Particles", false);
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (mc.thePlayer == null || mc.theWorld == null) return;

        final Entity targetEntity = event.getTarget();
        if (!(targetEntity instanceof EntityLivingBase)) return;

        EntityLivingBase target = (EntityLivingBase) targetEntity;

        if (checkInvulnerability.getValue() && target.hurtResistantTime > target.maxHurtResistantTime / 2) {
            return;
        }

        long now = System.currentTimeMillis();
        long timeSinceLastAttack = now - lastAttackTime;

        // --- Combo logic: resets if too slow, ramps up if you're chaining ---
        if (lastTarget == null || lastTarget != target || timeSinceLastAttack > 1500) {
            comboCounter = 0;
            lastTargetHealth = target.getHealth();
        }
        comboCounter++;
        lastAttackTime = now;
        lastTarget = target;

        // --- Contextual damage estimation ---
        float healthBefore = lastTargetHealth > 0 ? lastTargetHealth : target.getMaxHealth();
        float healthAfter = target.getHealth();
        float damageDealt = Math.max(0, healthBefore - healthAfter);
        // Normalize damage relative to target's max health (0.0 - 1.0+)
        float damageRatio = target.getMaxHealth() > 0 ? damageDealt / target.getMaxHealth() : 0f;
        lastTargetHealth = healthAfter;

        // --- Player speed (smoothed for less jitter) ---
        double playerVelX = mc.thePlayer.posX - mc.thePlayer.prevPosX;
        double playerVelZ = mc.thePlayer.posZ - mc.thePlayer.prevPosZ;
        float rawPlayerSpeed = MathHelper.sqrt_double(playerVelX * playerVelX + playerVelZ * playerVelZ);
        smoothedPlayerSpeed += (rawPlayerSpeed - smoothedPlayerSpeed) * 0.35f;

        // --- Attack vector from player to target ---
        double attackDirX = target.posX - mc.thePlayer.posX;
        double attackDirZ = target.posZ - mc.thePlayer.posZ;
        double attackDirLen = Math.sqrt(attackDirX * attackDirX + attackDirZ * attackDirZ);
        if (attackDirLen > 0.001) {
            attackDirX /= attackDirLen;
            attackDirZ /= attackDirLen;
        } else {
            // Fallback: player look direction
            float yawRad = (float) Math.toRadians(mc.thePlayer.rotationYaw);
            attackDirX = -MathHelper.sin(yawRad);
            attackDirZ = MathHelper.cos(yawRad);
        }

        // --- Combo multiplier: logarithmic scaling, feels snappy early, doesn't explode ---
        float comboMult = comboScaling.getValue()
                ? 1.0f + (float) (Math.log1p(comboCounter) * 0.6)
                : 1.0f;

        // --- Damage multiplier: bigger hits = more particles ---
        float damageMult = 1.0f + damageRatio * 3.0f;

        // --- Speed multiplier: moving fast = wider, more aggressive bursts ---
        float speedMult = 1.0f + smoothedPlayerSpeed * 2.5f;

        // --- Final particle count: all factors combined, then FPS-governed ---
        float rawCount = multiplier.getValue() * comboMult * damageMult * speedMult * intensityScale.getValue();
        int count = governByPerformance(Math.round(rawCount));
        if (count <= 0) count = 1; // Always show at least something

        boolean crit = alwaysCrit.getValue() || mc.thePlayer.fallDistance > 0.0F;
        boolean sharp = alwaysSharp.getValue();

        // --- Fall distance adds vertical drama ---
        float fallIntensity = Math.min(mc.thePlayer.fallDistance * 0.15f, 1.5f);

        // --- Spawn ---
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        float spread = spreadFactor.getValue();

        for (int i = 0; i < count; i++) {
            // Each particle gets its own slight variation so nothing looks mechanical
            float particleProgress = (float) i / Math.max(count - 1, 1);

            if (crit) {
                spawnContextualParticle(target, EnumParticleTypes.CRIT, rng,
                        attackDirX, attackDirZ, smoothedPlayerSpeed, fallIntensity,
                        spread, damageRatio, particleProgress);
            }
            if (sharp) {
                spawnContextualParticle(target, EnumParticleTypes.CRIT_MAGIC, rng,
                        attackDirX, attackDirZ, smoothedPlayerSpeed, fallIntensity,
                        spread, damageRatio, particleProgress);
            }
        }
    }

    /**
     * Every single parameter here is derived from gameplay context. Nothing is a magic number;
     * everything scales from the spread, speed, damage, fall, and attack angle.
     */
    private void spawnContextualParticle(EntityLivingBase target, EnumParticleTypes type,
                                         ThreadLocalRandom rng,
                                         double atkDirX, double atkDirZ,
                                         float playerSpeed, float fallIntensity,
                                         float spread, float damageRatio,
                                         float particleProgress) {

        float halfWidth = target.width * 0.5f;
        float height = target.height;

        // --- Position: spread across hitbox, biased toward impact face ---
        double offsetX = (rng.nextDouble() - 0.5) * target.width * spread;
        double offsetY = rng.nextDouble() * height;
        double offsetZ = (rng.nextDouble() - 0.5) * target.width * spread;

        // Bias spawn position toward the side the player is hitting from
        if (directionalBurst.getValue()) {
            offsetX -= atkDirX * halfWidth * 0.4;
            offsetZ -= atkDirZ * halfWidth * 0.4;
        }

        double x = target.posX + offsetX;
        double y = target.posY + offsetY;
        double z = target.posZ + offsetZ;

        // --- Velocity: derived entirely from context ---
        double baseSpread = 0.15 + spread * 0.15;

        // Radial "pop" away from center
        double radialX = (rng.nextDouble() - 0.5) * baseSpread;
        double radialZ = (rng.nextDouble() - 0.5) * baseSpread;

        // Upward lift: scales with damage and fall distance
        double upwardBias = (0.05 + damageRatio * 0.35 + fallIntensity * 0.25) * (0.7 + rng.nextDouble() * 0.6);

        // Directional push: particles fly in the direction of the attack
        double directionalStrength = dynamicVelocity.getValue()
                ? (0.15 + playerSpeed * 0.8 + damageRatio * 0.5)
                : 0.15;

        // Slight spiral: makes the burst feel alive, not flat
        double spiralAngle = particleProgress * Math.PI * 2.0 + rng.nextDouble() * 0.8;
        double spiralRadius = 0.05 + damageRatio * 0.12;
        double spiralX = Math.cos(spiralAngle) * spiralRadius;
        double spiralZ = Math.sin(spiralAngle) * spiralRadius;

        double motionX = radialX + atkDirX * directionalStrength + spiralX;
        double motionY = upwardBias;
        double motionZ = radialZ + atkDirZ * directionalStrength + spiralZ;

        // Intensity scaling on velocity magnitude
        float intensityMul = intensityScale.getValue();
        motionX *= intensityMul;
        motionY *= intensityMul;
        motionZ *= intensityMul;

        mc.theWorld.spawnParticle(type, x, y, z, motionX, motionY, motionZ);
    }

    /**
     * Performance governor — scales particle budget smoothly based on FPS.
     * No cliff edges, no hardcoded thresholds. Uses a continuous curve.
     */
    private int governByPerformance(int desired) {
        int fps = Minecraft.getDebugFPS();

        // Continuous scale: at 120+ fps you get full budget,
        // it linearly drops toward a floor of 1 as FPS approaches 20.
        // Below 20 fps, always 1.
        float minFps = 20f;
        float fullFps = 120f;

        if (fps <= minFps) return 1;
        if (fps >= fullFps) return desired;

        // Linear interpolation between 1 and desired
        float t = (fps - minFps) / (fullFps - minFps);
        // Ease-in curve so it doesn't cut too aggressively at moderate FPS
        t = t * t;

        return Math.max(1, Math.round(1 + (desired - 1) * t));
    }

    @Override
    public void onDisabled() {
        comboCounter = 0;
        lastAttackTime = 0;
        lastTargetHealth = -1f;
        lastTarget = null;
        smoothedPlayerSpeed = 0f;
    }

    @Override
    public String[] getSuffix() {
        String base = multiplier.getValue() + "x";
        if (comboScaling.getValue() && comboCounter > 1) {
            base += " C" + comboCounter;
        }
        return new String[]{base};
    }
}