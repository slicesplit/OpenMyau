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
import net.minecraft.network.play.server.*;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * FakeLag — Simulates lag by adding a delay to the packets
 * you send to the server.
 *
 * How it works:
 * Outgoing position packets (C03/C04/C05/C06) are held in a buffer
 * instead of being sent immediately. The server and all other players
 * see you at the position of the LAST SENT packet until the buffer
 * is released. When released, the server processes all positions
 * sequentially — movement validation passes because each consecutive
 * position is a valid movement delta.
 *
 * Modes:
 *
 * Latency — Adds a constant delay to your packets. Every packet is
 *   held for exactly [Delay] ms before being sent. If your real ping
 *   is 50ms and delay is 100ms, you effectively play on 150ms.
 *   On opponent screen: you appear to lag naturally, standing still
 *   briefly then teleporting to catch up.
 *
 * Dynamic — Dynamically adjusts your effective connection speed to
 *   give you advantages in combat. Holds packets longer when you're
 *   approaching an opponent (they see you further away than you are,
 *   so their swings miss), and releases quickly when you're moving
 *   away or strafing (updating your server position to the "far"
 *   point). Also flushes right before you attack so your hit
 *   registers from your real (close) position.
 *
 * Repel — Tunes FakeLag with the goal of keeping your opponent as
 *   far away from you as possible on their screen. Continuously
 *   evaluates whether holding or releasing packets would place your
 *   server position further from the target. Holds when the desync
 *   pushes your visible position away from them, releases when
 *   the desync would pull you closer on their screen.
 *   Opponent always sees you slightly out of reach.
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
    //  Internal structures
    // ──────────────────────────────────────────────

    private static final class Timed {
        final Packet<?> packet;
        final long stamp;
        final double px, py, pz; // player position when this packet was created

        Timed(Packet<?> p, long t, double px, double py, double pz) {
            this.packet = p;
            this.stamp = t;
            this.px = px;
            this.py = py;
            this.pz = pz;
        }
    }

    // ──────────────────────────────────────────────
    //  State
    // ──────────────────────────────────────────────

    private final Deque<Timed> buffer = new ArrayDeque<>();
    private boolean releasing;

    // Server position — where opponents see us
    // Updated only when a C03 with position actually gets sent
    private double srvX, srvY, srvZ;
    private boolean srvValid;

    // Safety tracking
    private float prevHp;
    private int tick;
    private long lastFlushTime;

    // Stuck detection
    private double lastPlayerX, lastPlayerY, lastPlayerZ;
    private int stuckTicks;

    // Dynamic mode — track target distance changes
    private double lastTargetDist;
    private double prevDesync;
    private boolean attackPending; // flush on next attack for dynamic

    // Repel mode — track which action maximizes opponent distance
    private double repelBestServerDist; // best server-to-target distance achieved

    // Hard limits
    private static final long ABSOLUTE_MAX_HOLD = 500;
    private static final int MAX_BUFFER_SIZE = 30;
    private static final double STUCK_THRESHOLD = 0.03;
    private static final int STUCK_TICK_LIMIT = 15;
    private static final double MAX_DESYNC_DISTANCE = 6.5;

    public FakeLag() {
        super("FakeLag", false);
    }

    // ──────────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────────

    @Override
    public void onEnabled() {
        reset();
        if (mc.thePlayer != null) {
            syncPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
            prevHp = mc.thePlayer.getHealth();
            lastPlayerX = mc.thePlayer.posX;
            lastPlayerY = mc.thePlayer.posY;
            lastPlayerZ = mc.thePlayer.posZ;
        }
        lastFlushTime = System.currentTimeMillis();
    }

    @Override
    public void onDisabled() {
        flush(true);
        reset();
    }

    private void reset() {
        buffer.clear();
        releasing = false;
        srvValid = false;
        prevHp = -1;
        tick = 0;
        lastTargetDist = -1;
        lastFlushTime = System.currentTimeMillis();
        stuckTicks = 0;
        lastPlayerX = lastPlayerY = lastPlayerZ = 0;
        attackPending = false;
        prevDesync = 0;
        repelBestServerDist = 0;
    }

    private void syncPos(double x, double y, double z) {
        srvX = x;
        srvY = y;
        srvZ = z;
        srvValid = true;
    }

    // ──────────────────────────────────────────────
    //  Tick handler
    // ──────────────────────────────────────────────

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) return;

        // ── Immediate flush: non-negotiable safety ──
        if (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0
                || mc.thePlayer.isRiding() || mc.thePlayer.isPlayerSleeping()
                || mc.getNetHandler() == null) {
            flush(true);
            return;
        }

        // Void
        if (mc.thePlayer.posY < 3 || (srvValid && srvY < 3)) {
            flush(true);
            return;
        }

        // Liquid / ladder / web — movement physics change, holding
        // packets causes rubberbanding because server-side position
        // simulation diverges from client
        if (mc.thePlayer.isInWater() || mc.thePlayer.isInLava()
                || mc.thePlayer.isOnLadder() || mc.thePlayer.isInsideOfMaterial(net.minecraft.block.material.Material.web)) {
            flush(true);
            return;
        }

        // High fall distance — KB/fall damage imminent
        if (mc.thePlayer.fallDistance > 2.5) {
            flush(true);
            return;
        }

        // Damage taken — flush immediately to prevent desynced combat
        float hp = mc.thePlayer.getHealth();
        if (prevHp > 0 && hp < prevHp - 0.01f) {
            flush(true);
            prevHp = hp;
            return;
        }
        prevHp = hp;

        // Too early after login/respawn
        if (mc.thePlayer.ticksExisted < 40) {
            flush(true);
            return;
        }

        long now = System.currentTimeMillis();

        // ── Stuck detection ──
        // If we're holding packets but the player hasn't moved,
        // the desync isn't growing and we're just adding latency
        // for no benefit. Also catches cases where something is
        // blocking movement (collision, server correction, etc.)
        double movedX = mc.thePlayer.posX - lastPlayerX;
        double movedY = mc.thePlayer.posY - lastPlayerY;
        double movedZ = mc.thePlayer.posZ - lastPlayerZ;
        double movedDist = Math.sqrt(movedX * movedX + movedY * movedY + movedZ * movedZ);

        if (movedDist < STUCK_THRESHOLD && !buffer.isEmpty()) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPlayerX = mc.thePlayer.posX;
        lastPlayerY = mc.thePlayer.posY;
        lastPlayerZ = mc.thePlayer.posZ;

        if (stuckTicks >= STUCK_TICK_LIMIT) {
            flush(true);
            stuckTicks = 0;
            return;
        }

        // ── Hard absolute time cap ──
        // No packet should ever be held beyond ABSOLUTE_MAX_HOLD.
        // This prevents server-side timeout and extreme rubberbanding.
        if (!buffer.isEmpty()) {
            Timed oldest = buffer.peekFirst();
            if (oldest != null && now - oldest.stamp > ABSOLUTE_MAX_HOLD) {
                flush(false);
                return;
            }
        }

        // ── Desync distance cap ──
        // If our real position is too far from server position,
        // the teleport on flush will be too obvious and may flag
        if (srvValid && desync() > MAX_DESYNC_DISTANCE) {
            flush(false);
            return;
        }

        // ── Buffer size cap ──
        if (buffer.size() > MAX_BUFFER_SIZE) {
            flush(false);
            return;
        }

        // ── Near target check ──
        if (nearTargetOnly.getValue() && target() == null) {
            flush(false);
            return;
        }

        tick++;

        // ── Mode-specific logic ──
        switch (mode.getValue()) {
            case 0:
                handleLatency(now);
                break;
            case 1:
                handleDynamic(now);
                break;
            case 2:
                handleRepel(now);
                break;
        }

        // ── Safety net: drain stale packets ──
        // Even if mode logic doesn't flush, never let packets
        // sit longer than delay + margin
        if (!buffer.isEmpty() && now - lastFlushTime > delay.getValue() + 150) {
            drainStale(now, delay.getValue() + 50);
        }
    }

    // ──────────────────────────────────────────────
    //  LATENCY MODE
    //
    //  Simple constant delay. Each packet is held for exactly
    //  [delay] ms before being sent. This creates a uniform
    //  lag appearance — you stand still for [delay] ms then
    //  your position catches up.
    //
    //  On opponent screen: natural-looking lag spike pattern.
    //  Your movement appears in bursts separated by brief freezes.
    // ──────────────────────────────────────────────

    private void handleLatency(long now) {
        expire(now, delay.getValue());
    }

    // ──────────────────────────────────────────────
    //  DYNAMIC MODE
    //
    //  Adjusts delay based on combat context to give advantage:
    //
    //  APPROACHING target (distance shrinking):
    //    Hold packets LONGER → server position stays far back
    //    → opponent sees you further away → their attacks miss
    //    → you're actually closer than they think
    //
    //  RETREATING from target (distance growing):
    //    Release packets QUICKLY → server position updates to
    //    the "far" point → opponent sees you jump away
    //    → hard for them to follow up
    //
    //  STRAFING (distance stable):
    //    Moderate delay → lateral desync makes you hard to track
    //
    //  ATTACKING:
    //    Flush ALL packets right before the attack packet goes out
    //    → server position snaps to real position → hit registers
    //    from correct (close) distance → reach check passes
    //
    //  The key insight: by holding when approaching and releasing
    //  when retreating, the opponent always sees you at the WORST
    //  position for them — either too far to hit, or already gone.
    // ──────────────────────────────────────────────

    private void handleDynamic(long now) {
        EntityPlayer t = target();
        if (t == null) {
            // No target — use base delay as fallback
            expire(now, Math.max(20, delay.getValue() / 2));
            return;
        }

        double currentDist = mc.thePlayer.getDistanceToEntity(t);
        double serverDist = srvValid
                ? dist(srvX, srvY, srvZ, t.posX, t.posY, t.posZ)
                : currentDist;

        // How fast are we approaching? Positive = getting closer
        double approachSpeed = 0;
        if (lastTargetDist > 0) {
            approachSpeed = lastTargetDist - currentDist;
        }
        lastTargetDist = currentDist;

        // Calculate effective delay based on combat context
        int effectiveDelay;

        if (approachSpeed > 0.08) {
            // ── APPROACHING ──
            // Hold longer to build desync. Opponent sees us further back.
            // Scale factor: the faster we approach, the longer we hold.
            // Cap at 1.5x base delay to stay within safety limits.
            double factor = Math.min(1.5, 1.0 + approachSpeed * 3.0);
            effectiveDelay = (int) (delay.getValue() * factor);

        } else if (approachSpeed < -0.08) {
            // ── RETREATING ──
            // Release quickly to update server pos to "far" point.
            // Opponent sees us jump away from them.
            double factor = Math.max(0.2, 0.5 + approachSpeed * 2.0);
            effectiveDelay = (int) (delay.getValue() * factor);

        } else {
            // ── STRAFING / STABLE ──
            // Moderate delay. The lateral movement creates desync
            // perpendicular to the opponent's aim, making us harder
            // to track even with moderate delay.
            effectiveDelay = (int) (delay.getValue() * 0.7);
        }

        // If server position is already further from target than our
        // real position → desync is working in our favor → hold longer
        if (serverDist > currentDist + 0.5 && approachSpeed > -0.05) {
            effectiveDelay = (int) Math.min(effectiveDelay * 1.3, delay.getValue() * 1.6);
        }

        // If server position is CLOSER to target than us → bad desync
        // Release quickly to fix this
        if (serverDist < currentDist - 0.3) {
            effectiveDelay = Math.min(effectiveDelay, delay.getValue() / 3);
        }

        // Clamp
        effectiveDelay = Math.max(20, Math.min(effectiveDelay, delay.getValue() + 60));

        expire(now, effectiveDelay);
    }

    // ──────────────────────────────────────────────
    //  REPEL MODE
    //
    //  Goal: maximize distance between our SERVER position
    //  (what opponent sees) and the opponent at all times.
    //
    //  Strategy:
    //  Every tick, we evaluate two scenarios:
    //    A) Keep holding — server pos stays frozen at last sent pos
    //    B) Flush now — server pos updates to current real pos
    //
    //  We pick whichever results in our server position being
    //  FURTHER from the target. This means:
    //
    //  - When we're walking toward the target:
    //    Current pos is closer to target than server pos.
    //    Holding keeps server pos far → HOLD.
    //
    //  - When we've walked past the target / turned around:
    //    Current pos is further from target than server pos.
    //    Flushing updates server pos to far point → FLUSH.
    //
    //  - When we're circling / strafing:
    //    Evaluate both options each tick → pick best.
    //
    //  The result: opponent always sees us at maximum distance.
    //  Their melee attacks consistently whiff because our visual
    //  position is always at the far edge of the desync envelope.
    //
    //  Combined with the delay slider controlling how much desync
    //  can accumulate, this creates a "force field" effect where
    //  the opponent can never quite reach us on their screen.
    // ──────────────────────────────────────────────

    private void handleRepel(long now) {
        EntityPlayer t = target();
        if (t == null) {
            // No target — just use base delay
            expire(now, delay.getValue());
            return;
        }

        double targetX = t.posX;
        double targetY = t.posY;
        double targetZ = t.posZ;

        // Scenario A: keep holding — server pos stays at srvX/Y/Z
        double distIfHold = srvValid
                ? dist(srvX, srvY, srvZ, targetX, targetY, targetZ)
                : 0;

        // Scenario B: flush now — server pos updates to current real pos
        double distIfFlush = dist(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ,
                targetX, targetY, targetZ);

        // Safety: also check desync limit
        double currentDesync = desync();

        if (!buffer.isEmpty()) {
            // We have packets to decide about

            if (distIfFlush > distIfHold + 0.15) {
                // Flushing puts our server position FURTHER from target
                // → flush to lock in the "far" position
                flush(false);
                repelBestServerDist = distIfFlush;

            } else if (currentDesync > delay.getValue() / 50.0 + 1.0) {
                // Desync is getting large relative to delay setting
                // Time-based drain to prevent exceeding limits
                drainStale(now, delay.getValue());

            } else {
                // Holding keeps us further → keep holding
                // But still drain packets that are too old
                drainStale(now, (int) (delay.getValue() * 1.3));
            }

            // Always drain anything beyond absolute age limit
            drainStale(now, delay.getValue() + 80);

        } else {
            // Buffer is empty — nothing to decide
            // Packets will start accumulating on next C03
        }

        // Track best distance for debugging/suffix
        if (srvValid) {
            double currentServerDist = dist(srvX, srvY, srvZ, targetX, targetY, targetZ);
            if (currentServerDist > repelBestServerDist) {
                repelBestServerDist = currentServerDist;
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Packet handler
    // ──────────────────────────────────────────────

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.getNetHandler() == null) return;

        if (event.getType() == EventType.RECEIVE) {
            handleIncoming(event.getPacket());
            return;
        }
        if (event.getType() != EventType.SEND || releasing) return;

        Packet<?> pkt = event.getPacket();

        // ── Never delay critical packets ──
        // These cause server-side issues if delayed (timeout, desync,
        // inventory corruption, chat loss, etc.)
        if (isCritical(pkt)) {
            passthrough(pkt);
            return;
        }

        // ── Only delay position packets ──
        if (!isDelayable(pkt)) {
            passthrough(pkt);
            return;
        }

        // ── Near target filter ──
        if (nearTargetOnly.getValue() && target() == null) {
            passthrough(pkt);
            return;
        }

        // ── Team filter ──
        if (teams.getValue()) {
            EntityPlayer t = target();
            if (t != null && TeamUtil.isSameTeam(t)) {
                passthrough(pkt);
                return;
            }
        }

        // ── Dynamic mode: flush before attacks ──
        // When the player attacks, we need to flush all held packets
        // FIRST so the server knows our real (close) position.
        // Otherwise the attack would register from the old (far)
        // server position and fail the reach check.
        //
        // The attack packet (C02) is handled separately — it passes
        // through isCritical/isDelayable and is never delayed.
        // But we detect the NEXT C03 after an attack in dynamic mode
        // to ensure positions are synced.

        // ── Pre-check: would this packet exceed desync limit? ──
        if (pkt instanceof C03PacketPlayer && srvValid) {
            C03PacketPlayer pp = (C03PacketPlayer) pkt;
            if (pp.isMoving()) {
                double dx = pp.getPositionX() - srvX;
                double dy = pp.getPositionY() - srvY;
                double dz = pp.getPositionZ() - srvZ;
                double nextDesync = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (nextDesync > MAX_DESYNC_DISTANCE) {
                    flush(false);
                    passthrough(pkt);
                    return;
                }
            }
        }

        // ── Buffer size safety ──
        if (buffer.size() >= MAX_BUFFER_SIZE) {
            flush(false);
            passthrough(pkt);
            return;
        }

        // ── Buffer the packet ──
        event.setCancelled(true);
        buffer.addLast(new Timed(pkt, System.currentTimeMillis(),
                mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ));
    }

    // ──────────────────────────────────────────────
    //  Packet classification
    // ──────────────────────────────────────────────

    /**
     * Packets that must NEVER be delayed. Delaying these causes:
     * - C00 KeepAlive: server timeout → disconnect
     * - C01 Chat: chat messages arrive late / out of order
     * - C0D CloseWindow: inventory desync
     * - C0E ClickWindow: item duplication / desync
     * - C0F Transaction: Grim/AC transaction tracking breaks
     * - C16 ClientStatus: respawn fails
     * - C15 ClientSettings: settings don't apply
     * - C17 CustomPayload: plugin channel breaks
     * - C02 UseEntity: attack/interact — must arrive at server
     *   with correct timing relative to position packets
     * - C0A Animation: swing must be in sync with attack
     * - C0B EntityAction: sprint/sneak state must be current
     * - C08 BlockPlacement: interaction timing matters
     * - C07 PlayerDigging: block breaking state
     * - C09 HeldItemChange: held item must be accurate for combat
     */
    private boolean isCritical(Packet<?> pkt) {
        return pkt instanceof C00PacketKeepAlive
                || pkt instanceof C01PacketChatMessage
                || pkt instanceof C0DPacketCloseWindow
                || pkt instanceof C0EPacketClickWindow
                || pkt instanceof C0FPacketConfirmTransaction
                || pkt instanceof C16PacketClientStatus
                || pkt instanceof C15PacketClientSettings
                || pkt instanceof C17PacketCustomPayload
                || pkt instanceof C14PacketTabComplete
                || pkt instanceof C19PacketResourcePackStatus
                || pkt instanceof C02PacketUseEntity
                || pkt instanceof C0APacketAnimation
                || pkt instanceof C0BPacketEntityAction
                || pkt instanceof C08PacketPlayerBlockPlacement
                || pkt instanceof C07PacketPlayerDigging
                || pkt instanceof C09PacketHeldItemChange;
    }

    /**
     * Only C03PacketPlayer variants with position data are delayable.
     * Look-only packets (C05) without position don't affect desync
     * and delaying them causes aim desync without benefit.
     * Ground-only packets (C03 base) are just flags, no benefit to delay.
     */
    private boolean isDelayable(Packet<?> pkt) {
        if (!(pkt instanceof C03PacketPlayer)) return false;
        return ((C03PacketPlayer) pkt).isMoving();
    }

    /**
     * Let a packet pass through without buffering, but still
     * track it for server position updates.
     */
    private void passthrough(Packet<?> pkt) {
        if (pkt instanceof C03PacketPlayer) {
            C03PacketPlayer pp = (C03PacketPlayer) pkt;
            if (pp.isMoving()) {
                syncPos(pp.getPositionX(), pp.getPositionY(), pp.getPositionZ());
            }
        }
        // Dynamic mode: detect attacks for pre-flush
        if (pkt instanceof C02PacketUseEntity) {
            C02PacketUseEntity c02 = (C02PacketUseEntity) pkt;
            if (c02.getAction() == C02PacketUseEntity.Action.ATTACK) {
                if (mode.getValue() == 1 && !buffer.isEmpty()) {
                    // Flush position packets BEFORE attack reaches server
                    // The TCP stream preserves order: flushed C03s arrive
                    // before the C02 attack packet that's already in the
                    // send queue. Server processes positions first, then
                    // validates the attack from the updated position.
                    flush(false);
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Incoming packet handler
    // ──────────────────────────────────────────────

    private void handleIncoming(Packet<?> pkt) {
        // ── Server teleport ──
        // S08 means the server corrected our position.
        // All buffered C03 packets are now based on wrong coordinates.
        // Sending them would cause rubberbanding (server rejects the
        // positions and teleports us back again, creating a loop).
        // Solution: discard all buffered packets silently.
        if (pkt instanceof S08PacketPlayerPosLook) {
            S08PacketPlayerPosLook p = (S08PacketPlayerPosLook) pkt;
            discardBuffer();
            syncPos(p.getX(), p.getY(), p.getZ());
            return;
        }

        // ── Knockback ──
        // Must flush immediately so position packets send before
        // the client applies velocity. Otherwise server thinks
        // we're at old position while client has already moved
        // from KB, causing position mismatch.
        if (pkt instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity vel = (S12PacketEntityVelocity) pkt;
            if (mc.thePlayer != null && vel.getEntityID() == mc.thePlayer.getEntityId()) {
                flush(true);
            }
            return;
        }

        // ── Explosion ──
        if (pkt instanceof S27PacketExplosion) {
            S27PacketExplosion exp = (S27PacketExplosion) pkt;
            if (exp.func_149149_c() != 0 || exp.func_149144_d() != 0 || exp.func_149147_e() != 0) {
                flush(true);
            }
            return;
        }

        // ── World change / disconnect ──
        if (pkt instanceof S07PacketRespawn
                || pkt instanceof S01PacketJoinGame
                || pkt instanceof S40PacketDisconnect) {
            discardBuffer();
            reset();
            return;
        }

        // ── Death ──
        if (pkt instanceof S06PacketUpdateHealth) {
            if (((S06PacketUpdateHealth) pkt).getHealth() <= 0) {
                discardBuffer();
                reset();
            }
            return;
        }

        // ── Entity death status ──
        if (pkt instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus status = (S19PacketEntityStatus) pkt;
            if (status.getOpCode() == 3 && mc.theWorld != null && mc.thePlayer != null) {
                try {
                    if (status.getEntity(mc.theWorld) == mc.thePlayer) {
                        discardBuffer();
                        reset();
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Buffer operations
    // ──────────────────────────────────────────────

    /**
     * Discard all buffered packets without sending them.
     * Used when server corrects our position (S08) — old movement
     * packets are based on wrong coordinates and would cause
     * the server to teleport us back repeatedly.
     */
    private void discardBuffer() {
        buffer.clear();
        lastFlushTime = System.currentTimeMillis();
        stuckTicks = 0;
    }

    /**
     * Release all packets older than [ms] milliseconds.
     * This is the core operation for Latency mode.
     */
    private void expire(long now, int ms) {
        if (buffer.isEmpty()) return;
        releasing = true;
        try {
            while (!buffer.isEmpty()) {
                Timed t = buffer.peekFirst();
                if (t == null || now - t.stamp < ms) break;
                buffer.pollFirst();
                dispatch(t.packet);
            }
            if (buffer.isEmpty()) lastFlushTime = now;
        } finally {
            releasing = false;
        }
    }

    /**
     * Drain packets older than maxAgeMs — safety net that prevents
     * packets from sitting in buffer indefinitely regardless of
     * mode logic decisions.
     */
    private void drainStale(long now, int maxAgeMs) {
        if (buffer.isEmpty()) return;
        releasing = true;
        try {
            while (!buffer.isEmpty()) {
                Timed t = buffer.peekFirst();
                if (t == null || now - t.stamp < maxAgeMs) break;
                buffer.pollFirst();
                dispatch(t.packet);
            }
            if (buffer.isEmpty()) lastFlushTime = now;
        } finally {
            releasing = false;
        }
    }

    /**
     * Release all buffered packets.
     *
     * @param sendAll true = send everything including stale packets.
     *                false = skip extremely stale position packets
     *                (they cause rubberbanding if too old).
     */
    private void flush(boolean sendAll) {
        if (buffer.isEmpty()) {
            lastFlushTime = System.currentTimeMillis();
            return;
        }
        if (mc.getNetHandler() == null) {
            buffer.clear();
            lastFlushTime = System.currentTimeMillis();
            return;
        }

        releasing = true;
        try {
            long now = System.currentTimeMillis();
            Timed t;
            while ((t = buffer.pollFirst()) != null) {
                if (!sendAll && now - t.stamp > ABSOLUTE_MAX_HOLD + 200) {
                    // Packet is extremely stale — sending it causes rubberband
                    // because server already timed out our position.
                    // Drop C03 silently; send non-C03 (shouldn't be any, but safe)
                    if (!(t.packet instanceof C03PacketPlayer)) {
                        dispatch(t.packet);
                    }
                } else {
                    dispatch(t.packet);
                }
            }
        } catch (Exception ignored) {
            buffer.clear();
        } finally {
            releasing = false;
            lastFlushTime = System.currentTimeMillis();
            stuckTicks = 0;
        }
    }

    /**
     * Send a single packet to the server and update tracked state.
     */
    private void dispatch(Packet<?> pkt) {
        if (mc.getNetHandler() == null) return;
        if (pkt instanceof C03PacketPlayer) {
            C03PacketPlayer pp = (C03PacketPlayer) pkt;
            if (pp.isMoving()) {
                syncPos(pp.getPositionX(), pp.getPositionY(), pp.getPositionZ());
            }
        }
        try {
            mc.getNetHandler().addToSendQueue(pkt);
        } catch (Exception ignored) {}
    }

    // ──────────────────────────────────────────────
    //  Utility
    // ──────────────────────────────────────────────

    private double desync() {
        if (!srvValid || mc.thePlayer == null) return 0;
        double dx = mc.thePlayer.posX - srvX;
        double dy = mc.thePlayer.posY - srvY;
        double dz = mc.thePlayer.posZ - srvZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

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

    private double distTo(EntityPlayer e) {
        return mc.thePlayer.getDistanceToEntity(e);
    }

    private static double dist(double x1, double y1, double z1,
                                double x2, double y2, double z2) {
        double dx = x1 - x2, dy = y1 - y2, dz = z1 - z2;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString()};
    }
}