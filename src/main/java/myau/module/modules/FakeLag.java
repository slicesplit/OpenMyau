package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.*;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition;
import net.minecraft.network.play.server.*;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * FakeLag — Simulates network latency by delaying position data
 * sent to the server.
 *
 * CRITICAL DESIGN:
 * We do NOT hold/buffer C03 packets. That causes timer violations
 * and BadPackets flags on any modern AC (Grim, Polar, Karhu).
 *
 * Instead, we use POSITION REPLAY:
 * - Every tick, the client generates a C03 with current position
 * - We INTERCEPT it, store the real position in a queue
 * - We REPLACE the position data with an older position from the queue
 * - The packet still gets sent at normal 20/sec rate (timer passes)
 * - But the position data is N ticks old (simulating latency)
 *
 * Result: Server sees smooth, physically valid movement at normal
 * packet rate — just delayed by [Delay] ms. No timer flags, no
 * packet rate spikes, no BadPackets.
 *
 * On opponent screen: You appear to be where you were N ms ago.
 * Your visual position lags behind your real position.
 *
 * Modes:
 *
 * Latency — Constant delay. Position data is always [Delay] ms old.
 *   You effectively play with [Delay] extra ping. Natural looking.
 *
 * Dynamic — Adjusts delay based on combat. Longer delay when
 *   approaching (opponent sees you far), shorter when retreating
 *   or attacking (position catches up quickly for hit registration).
 *
 * Repel — Maximizes the distance between your server position
 *   and your opponent. Dynamically picks delay that makes you
 *   appear as far from the target as possible on their screen.
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class FakeLag extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("Mode", 0,
            new String[]{"Latency", "Dynamic", "Repel"});

    public final IntProperty delay = new IntProperty("Delay", 80, 20, 250);

    public final BooleanProperty nearTargetOnly = new BooleanProperty("Near Target Only", false);
    public final IntProperty targetRange = new IntProperty("Target Range", 6, 3, 10,
            () -> nearTargetOnly.getValue());
    public final BooleanProperty teams = new BooleanProperty("Teams", false);

    // ──────────────────────────────────────────────
    //  Position snapshot — records real position at a point in time
    // ──────────────────────────────────────────────

    private static final class PosSnap {
        final double x, y, z;
        final float yaw, pitch;
        final boolean onGround;
        final long timestamp;
        final boolean sneaking;
        final boolean sprinting;

        PosSnap(double x, double y, double z, float yaw, float pitch,
                boolean onGround, long timestamp, boolean sneaking, boolean sprinting) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.onGround = onGround;
            this.timestamp = timestamp;
            this.sneaking = sneaking;
            this.sprinting = sprinting;
        }
    }

    // ──────────────────────────────────────────────
    //  State
    // ──────────────────────────────────────────────

    // Position history — ring buffer of real positions
    private final Deque<PosSnap> posHistory = new ArrayDeque<>();

    // The position the server currently thinks we're at
    private double serverX, serverY, serverZ;
    private boolean serverPosValid;

    // Last position we actually sent to server (the delayed one)
    private double lastSentX, lastSentY, lastSentZ;
    private boolean lastSentValid;

    // Safety
    private float prevHp;
    private boolean disabled; // temporarily disable (damage, KB, etc.)
    private int disabledTicks;
    private int tick;

    // Target tracking
    private double lastTargetDist;

    // Limits
    private static final int MAX_HISTORY = 60; // 3 seconds at 20tps
    private static final double MAX_DESYNC = 6.0;

    public FakeLag() {
        super("FakeLag", false);
    }

    // ──────────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────────

    @Override
    public void onEnabled() {
        fullReset();
        if (mc.thePlayer != null) {
            serverX = mc.thePlayer.posX;
            serverY = mc.thePlayer.posY;
            serverZ = mc.thePlayer.posZ;
            serverPosValid = true;
            prevHp = mc.thePlayer.getHealth();
        }
    }

    @Override
    public void onDisabled() {
        fullReset();
    }

    private void fullReset() {
        posHistory.clear();
        serverPosValid = false;
        lastSentValid = false;
        prevHp = -1;
        disabled = false;
        disabledTicks = 0;
        tick = 0;
        lastTargetDist = -1;
    }

    // ──────────────────────────────────────────────
    //  Tick handler — record positions & manage state
    // ──────────────────────────────────────────────

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) return;

        tick++;

        // ── Record current real position ──
        posHistory.addLast(new PosSnap(
                mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ,
                mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch,
                mc.thePlayer.onGround, System.currentTimeMillis(),
                mc.thePlayer.isSneaking(), mc.thePlayer.isSprinting()));

        // Trim history
        while (posHistory.size() > MAX_HISTORY) {
            posHistory.pollFirst();
        }

        // ── Safety: check if we should temporarily disable ──
        if (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0
                || mc.thePlayer.isRiding() || mc.thePlayer.isPlayerSleeping()
                || mc.getNetHandler() == null) {
            temporaryDisable(20);
            return;
        }

        // Void
        if (mc.thePlayer.posY < 3) {
            temporaryDisable(10);
            return;
        }

        // Liquid / ladder — movement physics differ, delayed positions
        // create impossible movement sequences
        if (mc.thePlayer.isInWater() || mc.thePlayer.isInLava()
                || mc.thePlayer.isOnLadder()) {
            temporaryDisable(5);
            return;
        }

        // High fall
        if (mc.thePlayer.fallDistance > 2.5) {
            temporaryDisable(10);
            return;
        }

        // Damage — disable briefly so combat is responsive
        float hp = mc.thePlayer.getHealth();
        if (prevHp > 0 && hp < prevHp - 0.01f) {
            temporaryDisable(6);
            prevHp = hp;
            return;
        }
        prevHp = hp;

        // Too early after login
        if (mc.thePlayer.ticksExisted < 40) {
            temporaryDisable(5);
            return;
        }

        // Desync too large
        if (serverPosValid) {
            double dx = mc.thePlayer.posX - serverX;
            double dy = mc.thePlayer.posY - serverY;
            double dz = mc.thePlayer.posZ - serverZ;
            double desync = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (desync > MAX_DESYNC) {
                temporaryDisable(5);
                return;
            }
        }

        // Near target check
        if (nearTargetOnly.getValue() && target() == null) {
            temporaryDisable(1);
            return;
        }

        // Disabled countdown
        if (disabledTicks > 0) {
            disabledTicks--;
            if (disabledTicks <= 0) {
                disabled = false;
            }
        }
    }

    private void temporaryDisable(int ticks) {
        disabled = true;
        disabledTicks = ticks;
        // Clear history — stale positions are dangerous after disable
        posHistory.clear();
    }

    // ──────────────────────────────────────────────
    //  Packet handler — CORE LOGIC
    //
    //  Instead of buffering packets, we REWRITE them.
    //  Every C03 that goes out gets its position replaced
    //  with an older position from our history queue.
    //
    //  The packet itself is never held — it goes out at
    //  normal 20/sec rate. Only the position DATA is delayed.
    //
    //  This means:
    //  - Timer check: PASSES (normal packet rate)
    //  - BadPackets: PASSES (one packet per tick, proper format)
    //  - Movement: PASSES (positions are real, just from the past)
    //  - Transaction: PASSES (we never hold any responses)
    //  - KeepAlive: PASSES (never touched)
    //
    //  The only thing the server notices is that our "ping" appears
    //  higher — we're always slightly behind where we should be.
    //  This is indistinguishable from real network latency.
    // ──────────────────────────────────────────────

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.getNetHandler() == null) return;

        if (event.getType() == EventType.RECEIVE) {
            handleIncoming(event.getPacket());
            return;
        }

        if (event.getType() != EventType.SEND) return;

        Packet<?> pkt = event.getPacket();

        // Only modify C03 position packets
        if (!(pkt instanceof C03PacketPlayer)) return;

        C03PacketPlayer c03 = (C03PacketPlayer) pkt;

        // Only care about packets with position data
        if (!c03.isMoving()) return;

        // If disabled (safety), let real position through
        if (disabled || posHistory.isEmpty()) {
            trackServerPos(c03);
            return;
        }

        // Team check
        if (teams.getValue()) {
            EntityPlayer t = target();
            if (t != null && TeamUtil.isSameTeam(t)) {
                trackServerPos(c03);
                return;
            }
        }

        // ── Calculate effective delay for this tick ──
        int effectiveDelay = getEffectiveDelay();

        // ── Find the position snapshot from [effectiveDelay] ms ago ──
        long now = System.currentTimeMillis();
        long targetTime = now - effectiveDelay;

        PosSnap delayedSnap = findSnapAtTime(targetTime);

        if (delayedSnap == null) {
            // No history old enough — let real position through
            trackServerPos(c03);
            return;
        }

        // ── Validate the delayed position ──
        // Make sure it won't cause flags

        // Check distance from last sent position — must be physically
        // possible movement (no teleporting)
        if (lastSentValid) {
            double dx = delayedSnap.x - lastSentX;
            double dy = delayedSnap.y - lastSentY;
            double dz = delayedSnap.z - lastSentZ;
            double moveDist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            // Max legit movement per tick: ~0.65 blocks sprinting with speed 2
            // Allow some margin for edge cases
            if (moveDist > 0.85) {
                // Movement too large for one tick — could happen if delay
                // jumped suddenly. Send intermediate position or just
                // let real position through this tick.
                trackServerPos(c03);
                return;
            }
        }

        // Check desync distance — delayed pos shouldn't be too far from real
        double desyncX = mc.thePlayer.posX - delayedSnap.x;
        double desyncY = mc.thePlayer.posY - delayedSnap.y;
        double desyncZ = mc.thePlayer.posZ - delayedSnap.z;
        double desync = Math.sqrt(desyncX * desyncX + desyncY * desyncY + desyncZ * desyncZ);
        if (desync > MAX_DESYNC) {
            trackServerPos(c03);
            return;
        }

        // ── Rewrite the packet ──
        // Cancel original, send modified version with delayed position
        event.setCancelled(true);

        // Determine what type of C03 to send
        // Must match the original packet's type (pos, pos+look, look, ground)
        Packet<?> modified;
        if (c03.getRotating()) {
            // C06: position + look
            modified = new C03PacketPlayer.C06PacketPlayerPosLook(
                    delayedSnap.x, delayedSnap.y,
                    delayedSnap.z,
                    c03.getYaw(), c03.getPitch(),
                    delayedSnap.onGround);
        } else {
            // C04: position only
            modified = new C03PacketPlayer.C04PacketPlayerPosition(
                    delayedSnap.x, delayedSnap.y,
                    delayedSnap.z,
                    delayedSnap.onGround);
        }

        mc.getNetHandler().addToSendQueue(modified);

        // Track what we actually sent
        serverX = delayedSnap.x;
        serverY = delayedSnap.y;
        serverZ = delayedSnap.z;
        serverPosValid = true;
        lastSentX = delayedSnap.x;
        lastSentY = delayedSnap.y;
        lastSentZ = delayedSnap.z;
        lastSentValid = true;
    }

    /**
     * Track server position when a real (unmodified) packet goes through.
     */
    private void trackServerPos(C03PacketPlayer c03) {
        if (c03.isMoving()) {
            serverX = c03.getPositionX();
            serverY = c03.getPositionY();
            serverZ = c03.getPositionZ();
            serverPosValid = true;
            lastSentX = c03.getPositionX();
            lastSentY = c03.getPositionY();
            lastSentZ = c03.getPositionZ();
            lastSentValid = true;
        }
    }

    // ──────────────────────────────────────────────
    //  Delay calculation per mode
    // ──────────────────────────────────────────────

    private int getEffectiveDelay() {
        switch (mode.getValue()) {
            case 1: return getDynamicDelay();
            case 2: return getRepelDelay();
            default: return delay.getValue();
        }
    }

    /**
     * DYNAMIC — Adjust delay based on combat situation.
     *
     * Approaching target → higher delay (they see us further back)
     * Retreating → lower delay (position catches up, we appear to jump away)
     * About to attack → minimal delay (position accurate for reach check)
     */
    private int getDynamicDelay() {
        EntityPlayer t = target();
        if (t == null) return Math.max(20, delay.getValue() / 2);

        double currentDist = mc.thePlayer.getDistanceToEntity(t);
        double approachSpeed = 0;
        if (lastTargetDist > 0) {
            approachSpeed = lastTargetDist - currentDist; // positive = approaching
        }
        lastTargetDist = currentDist;

        int base = delay.getValue();
        int effective;

        if (approachSpeed > 0.08) {
            // Approaching — hold longer, they see us further back
            double factor = Math.min(1.4, 1.0 + approachSpeed * 2.5);
            effective = (int) (base * factor);
        } else if (approachSpeed < -0.08) {
            // Retreating — release faster, position catches up
            double factor = Math.max(0.25, 0.6 + approachSpeed * 2.0);
            effective = (int) (base * factor);
        } else {
            // Stable / strafing — moderate delay
            effective = (int) (base * 0.7);
        }

        // If we're in melee range and likely about to attack,
        // reduce delay so hit registers from close position
        if (currentDist < 3.2) {
            effective = Math.min(effective, base / 2);
        }

        // If very close, minimal delay
        if (currentDist < 2.0) {
            effective = Math.min(effective, 30);
        }

        return Math.max(20, Math.min(effective, base + 50));
    }

    /**
     * REPEL — Pick delay that maximizes server-to-target distance.
     *
     * For each candidate delay, check where our server position
     * would be (the snapshot from that many ms ago) and pick
     * the delay where that position is furthest from the target.
     */
    private int getRepelDelay() {
        EntityPlayer t = target();
        if (t == null) return delay.getValue();

        double targetX = t.posX;
        double targetY = t.posY;
        double targetZ = t.posZ;

        long now = System.currentTimeMillis();
        int base = delay.getValue();

        // Test several delay values and pick the one that puts
        // our server position furthest from the target
        double bestDist = -1;
        int bestDelay = base;

        // Test: 40%, 60%, 80%, 100%, 120% of base delay
        int[] candidates = {
                (int) (base * 0.4),
                (int) (base * 0.6),
                (int) (base * 0.8),
                base,
                (int) (base * 1.15)
        };

        for (int candidate : candidates) {
            if (candidate < 20) continue;

            PosSnap snap = findSnapAtTime(now - candidate);
            if (snap == null) continue;

            double dx = snap.x - targetX;
            double dy = snap.y - targetY;
            double dz = snap.z - targetZ;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            // Also check desync — don't pick a delay that creates
            // too much desync
            double desyncX = mc.thePlayer.posX - snap.x;
            double desyncY = mc.thePlayer.posY - snap.y;
            double desyncZ = mc.thePlayer.posZ - snap.z;
            double desync = Math.sqrt(desyncX * desyncX + desyncY * desyncY + desyncZ * desyncZ);

            if (desync > MAX_DESYNC) continue;

            if (dist > bestDist) {
                bestDist = dist;
                bestDelay = candidate;
            }
        }

        return Math.max(20, Math.min(bestDelay, base + 40));
    }

    // ──────────────────────────────────────────────
    //  Position history lookup
    // ──────────────────────────────────────────────

    /**
     * Find the position snapshot closest to the given timestamp.
     * If the exact time isn't available, returns the nearest older snap.
     * Returns null if no suitable snapshot exists.
     */
    private PosSnap findSnapAtTime(long targetTime) {
        if (posHistory.isEmpty()) return null;

        // History is ordered oldest → newest (addLast)
        // We want the most recent snap that is AT or BEFORE targetTime

        PosSnap best = null;
        for (PosSnap snap : posHistory) {
            if (snap.timestamp <= targetTime) {
                best = snap; // keep updating — last one before target time wins
            } else {
                break; // past target time, stop
            }
        }

        return best;
    }

    // ──────────────────────────────────────────────
    //  Incoming packet handler
    // ──────────────────────────────────────────────

    private void handleIncoming(Packet<?> pkt) {
        // ── Server teleport ──
        // Server corrected our position — must sync immediately.
        // Clear history because old positions are now invalid
        // relative to the new server-assigned position.
        if (pkt instanceof S08PacketPlayerPosLook) {
            S08PacketPlayerPosLook p = (S08PacketPlayerPosLook) pkt;
            serverX = p.getX();
            serverY = p.getY();
            serverZ = p.getZ();
            serverPosValid = true;
            lastSentX = p.getX();
            lastSentY = p.getY();
            lastSentZ = p.getZ();
            lastSentValid = true;
            posHistory.clear();
            temporaryDisable(10);
            return;
        }

        // ── Knockback ──
        // Velocity changes our movement trajectory. Old positions
        // in history are pre-KB and sending them post-KB creates
        // impossible movement sequences. Must disable briefly.
        if (pkt instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity vel = (S12PacketEntityVelocity) pkt;
            if (mc.thePlayer != null && vel.getEntityID() == mc.thePlayer.getEntityId()) {
                posHistory.clear();
                temporaryDisable(8);
            }
            return;
        }

        // ── Explosion ──
        if (pkt instanceof S27PacketExplosion) {
            S27PacketExplosion exp = (S27PacketExplosion) pkt;
            if (exp.func_149149_c() != 0 || exp.func_149144_d() != 0 || exp.func_149147_e() != 0) {
                posHistory.clear();
                temporaryDisable(8);
            }
            return;
        }

        // ── World change / disconnect ──
        if (pkt instanceof S07PacketRespawn
                || pkt instanceof S01PacketJoinGame
                || pkt instanceof S40PacketDisconnect) {
            fullReset();
            return;
        }

        // ── Death ──
        if (pkt instanceof S06PacketUpdateHealth) {
            if (((S06PacketUpdateHealth) pkt).getHealth() <= 0) {
                fullReset();
            }
            return;
        }

        // ── Entity death ──
        if (pkt instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus status = (S19PacketEntityStatus) pkt;
            if (status.getOpCode() == 3 && mc.theWorld != null && mc.thePlayer != null) {
                try {
                    if (status.getEntity(mc.theWorld) == mc.thePlayer) {
                        fullReset();
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Utility
    // ──────────────────────────────────────────────

    private EntityPlayer target() {
        if (mc.theWorld == null || mc.thePlayer == null) return null;
        double rSq = targetRange.getValue() * (double) targetRange.getValue();
        EntityPlayer best = null;
        double bestD = rSq;
        try {
            for (EntityPlayer p : mc.theWorld.playerEntities) {
                if (p == mc.thePlayer || p.isDead || p.getHealth() <= 0) continue;
                if (p.isInvisible()) continue;
                if (TeamUtil.isBot(p)) continue;
                if (teams.getValue() && TeamUtil.isSameTeam(p)) continue;
                double d = mc.thePlayer.getDistanceSqToEntity(p);
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
        } catch (Exception ignored) {}
        return best;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString()};
    }
}