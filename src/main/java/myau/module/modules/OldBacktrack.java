package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.Module;
import myau.property.properties.*;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.init.Items;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.*;
import net.minecraft.potion.Potion;
import net.minecraft.util.*;
import org.lwjgl.opengl.GL11;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class OldBacktrack extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();
    public static volatile boolean isRaytracing = false;

    public final ModeProperty mode = new ModeProperty("Mode", 0, new String[]{"Manual", "Lag Based"});
    public final ModeProperty anticheat = new ModeProperty("Anticheat", 0, new String[]{
            "Old Grim", "New Grim", "Vulcan", "Intave", "Polar", "Verus",
            "Matrix", "NCP", "Spartan", "AAC", "Karhu", "Hawk"});
    public final IntProperty ticks = new IntProperty("Ticks", 6, 1, 12, () -> mode.getValue() == 0);
    public final IntProperty lag = new IntProperty("Latency", 150, 50, 300, () -> mode.getValue() == 1);
    public final BooleanProperty renderEnabled = new BooleanProperty("Render", true);
    public final ColorProperty color = new ColorProperty("Color", 0x2068FF);
    public final BooleanProperty smartActivation = new BooleanProperty("Smart Activation", true);
    public final BooleanProperty safeMode = new BooleanProperty("Safe Mode", true);

    // ──────────────────────────────────────────────
    //  Profile: per-anticheat tuning
    //
    //  Architecture:
    //  The core bypass works by holding C0F transaction confirmations.
    //  This artificially inflates the server's perceived "transaction ping"
    //  for our connection. When the server performs a reach check on our
    //  attack, it rewinds the target entity by (transactionPing) ms.
    //  The rewound entity position may have been closer to our
    //  CONFIRMED server position (the last pos the server acknowledged
    //  via transaction).
    //
    //  Each AC validates differently:
    //  - Old Grim: dist(confirmed_pos, rewound_entity) <= 3.0 + 0.03
    //  - New Grim: Same but tracks transaction response variance,
    //    runs prediction engine, correlates with keepalive ping
    //  - Vulcan: Checks reach from interpolated positions, more lenient
    //  - Polar: Uses prediction + strict transaction correlation
    //  - etc.
    //
    //  Edge cases that flag:
    //  1. Transaction ping >> keepalive ping (Grim, Polar, Karhu)
    //  2. Sudden ping variance spike (New Grim)
    //  3. Position desync between predicted and actual (New Grim, Polar)
    //  4. Attacking while in invalid state (airborne, eating, etc.)
    //  5. Sprint state inconsistency during hold
    //  6. Attacking from impossible angles
    //  7. Movement acceleration doesn't match physics during hold
    // ──────────────────────────────────────────────

    private static final class Profile {
        final double serverReach;
        final double safetyMargin;
        final int    maxTransactions;
        final long   maxHoldMs;
        final int    softMaxTicks;
        final int    sessionCooldown;
        final long   globalCooldownMs;
        final int    sessionsBeforeGlobal;
        final double maxHExpand;
        final double maxVExpand;
        final boolean strictTransactionOrder;
        final boolean accountForPrediction;
        final double  movementThreshold;
        final int     transactionFlushDelay;
        final boolean useAdaptiveTiming;
        final double maxPlayerSpeed;
        final double maxTargetSpeed;
        final int    minTicksExisted;

        // Extended fields for edge-case handling
        final boolean correlateKeepAlive;     // compare transaction vs keepalive ping
        final double  maxPingDriftRatio;      // max allowed transaction/keepalive ratio
        final boolean trackSprintState;       // validate sprint consistency
        final boolean validateAttackAngle;    // check look vector alignment
        final double  maxAttackAngle;         // max degrees off from target center
        final boolean handleAirborneStrict;   // strict airborne checks
        final int     airborneGraceTicks;     // ticks allowed airborne before cancel
        final boolean handleWaterCombat;      // special water/lava handling
        final boolean handleProjectileKB;     // rod/snowball/egg velocity
        final int     postPearlCooldown;      // ticks after pearl to wait
        final int     postRespawnCooldown;    // ticks after respawn
        final boolean predictMovementPhysics; // validate physics during hold
        final double  maxAcceleration;        // max blocks/tick² during hold
        final boolean trackC03Positions;      // track outgoing position packets
        final boolean validateHitSequence;    // check attack packet timing
        final long    minAttackInterval;      // minimum ms between attacks during hold
        final boolean compensateLatencySpikes;// handle natural lag spikes gracefully
        final int     postDamageCooldown;     // ticks to wait after taking damage
        final boolean requireLineOfSight;     // require LOS to target for activation
        final double  maxYDiffActivation;     // max Y difference for activation
        final double  maxYDiffMaintain;       // max Y difference to maintain hold
        final boolean handleBlockhit;         // handle sword blocking during combat
        final int     postBlockhitDelay;      // ticks after blockhit before hold
        final boolean handleCriticals;        // special handling for crit attacks
        final boolean handleComboTrading;     // detect when trading hits (both hitting)

        Profile(double serverReach, double safetyMargin,
                int maxTransactions, long maxHoldMs, int softMaxTicks,
                int sessionCooldown, long globalCooldownMs, int sessionsBeforeGlobal,
                double maxHExpand, double maxVExpand,
                boolean strictTransactionOrder, boolean accountForPrediction,
                double movementThreshold, int transactionFlushDelay,
                boolean useAdaptiveTiming, double maxPlayerSpeed,
                double maxTargetSpeed, int minTicksExisted,
                boolean correlateKeepAlive, double maxPingDriftRatio,
                boolean trackSprintState, boolean validateAttackAngle,
                double maxAttackAngle, boolean handleAirborneStrict,
                int airborneGraceTicks, boolean handleWaterCombat,
                boolean handleProjectileKB, int postPearlCooldown,
                int postRespawnCooldown, boolean predictMovementPhysics,
                double maxAcceleration, boolean trackC03Positions,
                boolean validateHitSequence, long minAttackInterval,
                boolean compensateLatencySpikes, int postDamageCooldown,
                boolean requireLineOfSight, double maxYDiffActivation,
                double maxYDiffMaintain, boolean handleBlockhit,
                int postBlockhitDelay, boolean handleCriticals,
                boolean handleComboTrading) {
            this.serverReach = serverReach;
            this.safetyMargin = safetyMargin;
            this.maxTransactions = maxTransactions;
            this.maxHoldMs = maxHoldMs;
            this.softMaxTicks = softMaxTicks;
            this.sessionCooldown = sessionCooldown;
            this.globalCooldownMs = globalCooldownMs;
            this.sessionsBeforeGlobal = sessionsBeforeGlobal;
            this.maxHExpand = maxHExpand;
            this.maxVExpand = maxVExpand;
            this.strictTransactionOrder = strictTransactionOrder;
            this.accountForPrediction = accountForPrediction;
            this.movementThreshold = movementThreshold;
            this.transactionFlushDelay = transactionFlushDelay;
            this.useAdaptiveTiming = useAdaptiveTiming;
            this.maxPlayerSpeed = maxPlayerSpeed;
            this.maxTargetSpeed = maxTargetSpeed;
            this.minTicksExisted = minTicksExisted;
            this.correlateKeepAlive = correlateKeepAlive;
            this.maxPingDriftRatio = maxPingDriftRatio;
            this.trackSprintState = trackSprintState;
            this.validateAttackAngle = validateAttackAngle;
            this.maxAttackAngle = maxAttackAngle;
            this.handleAirborneStrict = handleAirborneStrict;
            this.airborneGraceTicks = airborneGraceTicks;
            this.handleWaterCombat = handleWaterCombat;
            this.handleProjectileKB = handleProjectileKB;
            this.postPearlCooldown = postPearlCooldown;
            this.postRespawnCooldown = postRespawnCooldown;
            this.predictMovementPhysics = predictMovementPhysics;
            this.maxAcceleration = maxAcceleration;
            this.trackC03Positions = trackC03Positions;
            this.validateHitSequence = validateHitSequence;
            this.minAttackInterval = minAttackInterval;
            this.compensateLatencySpikes = compensateLatencySpikes;
            this.postDamageCooldown = postDamageCooldown;
            this.requireLineOfSight = requireLineOfSight;
            this.maxYDiffActivation = maxYDiffActivation;
            this.maxYDiffMaintain = maxYDiffMaintain;
            this.handleBlockhit = handleBlockhit;
            this.postBlockhitDelay = postBlockhitDelay;
            this.handleCriticals = handleCriticals;
            this.handleComboTrading = handleComboTrading;
        }

        double safeReach() { return serverReach - safetyMargin; }
    }

    private static Profile makeProfile(
            double serverReach, double safetyMargin,
            int maxTransactions, long maxHoldMs, int softMaxTicks,
            int sessionCooldown, long globalCooldownMs, int sessionsBeforeGlobal,
            double maxHExpand, double maxVExpand,
            boolean strictTransactionOrder, boolean accountForPrediction,
            double movementThreshold, int transactionFlushDelay,
            boolean useAdaptiveTiming, double maxPlayerSpeed,
            double maxTargetSpeed, int minTicksExisted,
            // Extended
            boolean correlateKeepAlive, double maxPingDriftRatio,
            boolean trackSprintState, boolean validateAttackAngle,
            double maxAttackAngle, boolean handleAirborneStrict,
            int airborneGraceTicks, boolean handleWaterCombat,
            boolean handleProjectileKB, int postPearlCooldown,
            int postRespawnCooldown, boolean predictMovementPhysics,
            double maxAcceleration, boolean trackC03Positions,
            boolean validateHitSequence, long minAttackInterval,
            boolean compensateLatencySpikes, int postDamageCooldown,
            boolean requireLineOfSight, double maxYDiffActivation,
            double maxYDiffMaintain, boolean handleBlockhit,
            int postBlockhitDelay, boolean handleCriticals,
            boolean handleComboTrading) {
        return new Profile(serverReach, safetyMargin, maxTransactions, maxHoldMs,
                softMaxTicks, sessionCooldown, globalCooldownMs, sessionsBeforeGlobal,
                maxHExpand, maxVExpand, strictTransactionOrder, accountForPrediction,
                movementThreshold, transactionFlushDelay, useAdaptiveTiming,
                maxPlayerSpeed, maxTargetSpeed, minTicksExisted,
                correlateKeepAlive, maxPingDriftRatio, trackSprintState,
                validateAttackAngle, maxAttackAngle, handleAirborneStrict,
                airborneGraceTicks, handleWaterCombat, handleProjectileKB,
                postPearlCooldown, postRespawnCooldown, predictMovementPhysics,
                maxAcceleration, trackC03Positions, validateHitSequence,
                minAttackInterval, compensateLatencySpikes, postDamageCooldown,
                requireLineOfSight, maxYDiffActivation, maxYDiffMaintain,
                handleBlockhit, postBlockhitDelay, handleCriticals,
                handleComboTrading);
    }

    private static final Profile[] PROFILES = {
        /* 0 Old Grim */
        makeProfile(
            3.03, 0.12, 5, 180, 4, 25, 8000, 3, 0.15, 0.04,
            false, false, 0.03, 0, true, 0.22, 0.7, 20,
            true, 1.8, true, true, 55.0, true, 2, true, true,
            60, 80, false, 0.08, true, true, 45, true, 12,
            true, 1.5, 1.8, true, 3, true, true),
        /* 1 New Grim */
        makeProfile(
            3.00, 0.20, 3, 100, 2, 40, 15000, 2, 0.08, 0.02,
            true, true, 0.00, 1, true, 0.15, 0.6, 30,
            true, 1.4, true, true, 45.0, true, 1, true, true,
            80, 100, true, 0.06, true, true, 50, true, 18,
            true, 1.2, 1.5, true, 5, true, true),
        /* 2 Vulcan */
        makeProfile(
            3.10, 0.12, 7, 260, 5, 18, 5000, 4, 0.20, 0.05,
            false, false, 0.05, 0, true, 0.25, 0.8, 20,
            false, 2.5, true, true, 60.0, true, 3, true, true,
            50, 60, false, 0.10, true, true, 40, true, 10,
            true, 1.5, 1.8, true, 2, true, true),
        /* 3 Intave */
        makeProfile(
            3.03, 0.14, 5, 200, 4, 22, 6000, 3, 0.16, 0.04,
            false, false, 0.03, 0, true, 0.22, 0.7, 20,
            true, 2.0, true, true, 55.0, true, 2, true, true,
            55, 70, false, 0.08, true, true, 45, true, 12,
            true, 1.5, 1.8, true, 3, true, true),
        /* 4 Polar */
        makeProfile(
            3.00, 0.18, 4, 160, 3, 28, 8000, 2, 0.12, 0.03,
            true, true, 0.01, 1, true, 0.18, 0.6, 25,
            true, 1.5, true, true, 50.0, true, 1, true, true,
            70, 90, true, 0.06, true, true, 50, true, 15,
            true, 1.3, 1.6, true, 4, true, true),
        /* 5 Verus */
        makeProfile(
            3.30, 0.08, 9, 320, 6, 10, 3000, 5, 0.30, 0.08,
            false, false, 0.10, 0, false, 0.35, 1.0, 10,
            false, 3.0, false, false, 90.0, false, 5, false, true,
            30, 40, false, 0.15, false, false, 30, false, 6,
            false, 2.0, 2.5, false, 1, false, false),
        /* 6 Matrix */
        makeProfile(
            3.10, 0.12, 7, 250, 5, 16, 4000, 4, 0.22, 0.05,
            false, false, 0.05, 0, true, 0.25, 0.8, 15,
            false, 2.2, true, true, 60.0, true, 3, true, true,
            45, 55, false, 0.10, true, true, 40, true, 10,
            true, 1.5, 1.8, true, 2, true, true),
        /* 7 NCP */
        makeProfile(
            3.10, 0.10, 8, 280, 6, 12, 3500, 5, 0.28, 0.07,
            false, false, 0.08, 0, false, 0.30, 0.9, 15,
            false, 2.5, true, false, 70.0, true, 3, true, true,
            40, 50, false, 0.12, true, false, 35, true, 8,
            true, 1.8, 2.0, true, 2, false, true),
        /* 8 Spartan */
        makeProfile(
            3.50, 0.08, 10, 340, 7, 8, 2000, 6, 0.40, 0.10,
            false, false, 0.15, 0, false, 0.40, 1.2, 10,
            false, 3.5, false, false, 90.0, false, 5, false, true,
            25, 35, false, 0.18, false, false, 25, false, 5,
            false, 2.5, 3.0, false, 1, false, false),
        /* 9 AAC */
        makeProfile(
            3.10, 0.12, 7, 240, 5, 18, 4500, 4, 0.20, 0.05,
            false, false, 0.05, 0, true, 0.25, 0.8, 15,
            false, 2.2, true, true, 60.0, true, 3, true, true,
            50, 60, false, 0.10, true, true, 40, true, 10,
            true, 1.5, 1.8, true, 2, true, true),
        /* 10 Karhu */
        makeProfile(
            3.00, 0.16, 4, 160, 3, 28, 7000, 2, 0.10, 0.02,
            true, true, 0.01, 1, true, 0.15, 0.6, 25,
            true, 1.5, true, true, 50.0, true, 1, true, true,
            70, 85, true, 0.06, true, true, 50, true, 15,
            true, 1.3, 1.5, true, 4, true, true),
        /* 11 Hawk */
        makeProfile(
            3.03, 0.12, 5, 220, 4, 20, 5500, 3, 0.16, 0.04,
            false, false, 0.03, 0, true, 0.22, 0.7, 20,
            false, 2.0, true, true, 55.0, true, 2, true, true,
            50, 65, false, 0.08, true, true, 45, true, 12,
            true, 1.5, 1.8, true, 3, true, true),
    };

    // ──────────────────────────────────────────────
    //  Snap: immutable entity position record
    // ──────────────────────────────────────────────

    private static final class Snap {
        final double x, y, z;
        final long   timestamp;
        final int    serverTick;

        Snap(double x, double y, double z, long timestamp, int serverTick) {
            this.x = x; this.y = y; this.z = z;
            this.timestamp = timestamp;
            this.serverTick = serverTick;
        }

        AxisAlignedBB box() {
            return new AxisAlignedBB(x - 0.3, y, z - 0.3,
                                     x + 0.3, y + 1.8, z + 0.3);
        }

        double distSqTo(double px, double py, double pz) {
            AxisAlignedBB b = box();
            double cx = clamp(px, b.minX, b.maxX);
            double cy = clamp(py, b.minY, b.maxY);
            double cz = clamp(pz, b.minZ, b.maxZ);
            return sq(cx - px) + sq(cy - py) + sq(cz - pz);
        }

        double distTo(double px, double py, double pz) {
            return Math.sqrt(distSqTo(px, py, pz));
        }
    }

    // ──────────────────────────────────────────────
    //  Confirmed position tracking
    // ──────────────────────────────────────────────

    private static final class ConfirmedPosition {
        final double x, y, z;
        final long timestamp;
        final boolean sneaking;
        final boolean sprinting;
        final double motionX, motionY, motionZ;

        ConfirmedPosition(double x, double y, double z, long timestamp,
                          boolean sneaking, boolean sprinting,
                          double motionX, double motionY, double motionZ) {
            this.x = x; this.y = y; this.z = z;
            this.timestamp = timestamp;
            this.sneaking = sneaking;
            this.sprinting = sprinting;
            this.motionX = motionX;
            this.motionY = motionY;
            this.motionZ = motionZ;
        }

        double eyeY() { return y + (sneaking ? 1.54 : 1.62); }
    }

    // ──────────────────────────────────────────────
    //  Held transaction wrapper
    // ──────────────────────────────────────────────

    private static final class HeldTransaction {
        final Packet<?> packet;
        final long heldAt;
        final ConfirmedPosition playerPosWhenHeld;

        HeldTransaction(Packet<?> packet, long heldAt, ConfirmedPosition pos) {
            this.packet = packet;
            this.heldAt = heldAt;
            this.playerPosWhenHeld = pos;
        }
    }

    // ──────────────────────────────────────────────
    //  Outgoing position tracker
    //  Tracks C04/C05/C06 packets we've sent to know
    //  what the server thinks our position is
    // ──────────────────────────────────────────────

    private static final class SentPosition {
        final double x, y, z;
        final boolean onGround;
        final long sentAt;
        final boolean confirmed; // set true when transaction confirms it

        SentPosition(double x, double y, double z, boolean onGround, long sentAt) {
            this.x = x; this.y = y; this.z = z;
            this.onGround = onGround;
            this.sentAt = sentAt;
            this.confirmed = false;
        }
    }

    // ──────────────────────────────────────────────
    //  Combat state tracking
    // ──────────────────────────────────────────────

    private enum CombatPhase {
        IDLE,       // no combat
        APPROACH,   // moving toward target
        ENGAGE,     // in melee range, fighting
        TRADING,    // both players hitting each other
        RETREATING, // moving away / taking KB
        RECOVERY    // post-KB, waiting for ground
    }

    // ──────────────────────────────────────────────
    //  State fields
    // ──────────────────────────────────────────────

    private final HashMap<Integer, ArrayDeque<Snap>> positions = new HashMap<>();
    private final HashMap<Integer, Snap> selected = new HashMap<>();
    private final ArrayDeque<HeldTransaction> held = new ArrayDeque<>();
    private final ArrayDeque<SentPosition> sentPositions = new ArrayDeque<>();

    private volatile List<Snap> renderSnaps = Collections.emptyList();
    private final AtomicBoolean releasing = new AtomicBoolean(false);

    // Session state
    private boolean active;
    private long    activeStart;
    private int     activeTicks;
    private ConfirmedPosition confirmedPos;

    // Cooldowns
    private int cd;
    private int velCd;
    private int respCd;
    private int kbCd;
    private int flushDelayCd;
    private int pearlCd;
    private int damageCd;
    private int blockhitCd;
    private int waterCd;
    private int voidCd;
    private int teleportCd;
    private int sprintResetCd;

    // Session tracking
    private int sessions;
    private long nextAllowMs;

    // Safety state
    private float prevHp;
    private boolean waitGround;
    private int lastWorldId;

    // Combat state
    private CombatPhase combatPhase = CombatPhase.IDLE;
    private int combatPhaseTicks;
    private int lastAttackTick;
    private long lastAttackMs;
    private int attacksDuringHold;
    private int hitsReceived;
    private int hitsLanded;
    private boolean wasTrading;

    // Transaction timing
    private final ArrayDeque<Long> recentTransactionTimes = new ArrayDeque<>();
    private long avgTransactionTime = 50;
    private long transactionVariance = 15;

    // KeepAlive correlation (Grim compares transaction ping to keepalive ping)
    private final ArrayDeque<Long> keepAliveSendTimes = new ArrayDeque<>();
    private final HashMap<Long, Long> keepAliveMap = new HashMap<>();
    private long avgKeepAlivePing = 50;
    private long lastKeepAliveId = -1;

    // Server position tracking
    private double serverPosX, serverPosY, serverPosZ;
    private boolean hasServerPos;

    // Sent position tracking (what server ACTUALLY knows)
    private double lastSentX, lastSentY, lastSentZ;
    private boolean lastSentOnGround;
    private long lastSentTime;
    private boolean hasSentPos;

    // Server tick estimation
    private int estimatedServerTick;

    // Player velocity tracking
    private double lastPlayerX, lastPlayerY, lastPlayerZ;
    private double playerSpeed;
    private double lastPlayerSpeed;
    private double playerAcceleration;

    // Sprint state tracking
    private boolean wasSprinting;
    private int sprintTicks;
    private int noSprintTicks;
    private boolean lastSprintState;

    // Airborne tracking
    private int airborneTicks;
    private int groundTicks;
    private boolean wasOnGround;
    private double lastFallDistance;

    // Environment tracking
    private boolean inWater;
    private boolean inLava;
    private boolean inWeb;
    private boolean onLadder;
    private boolean nearVoid;

    // Target tracking
    private int currentTargetId = -1;
    private double targetApproachSpeed; // positive = getting closer
    private double lastTargetDist;

    // Blockhit / item use tracking
    private boolean isBlocking;
    private boolean isEating;
    private boolean isUsingBow;

    // Rod/projectile tracking
    private final HashSet<Integer> trackedProjectiles = new HashSet<>();

    // Damage tracking
    private int lastHurtTick;
    private int comboCount; // how many hits we've landed in a row
    private int receivedComboCount; // how many hits we've received in a row

    // BedWars specific
    private boolean justRespawned;
    private int respawnTicks;

    // Sumo specific
    private boolean edgeSituation; // near arena edge
    private boolean criticalKBSituation; // KB that could ring us out

    public OldBacktrack() {
        super("OldBacktrack", false);
    }

    private Profile prof() {
        int i = anticheat.getValue();
        return (i >= 0 && i < PROFILES.length) ? PROFILES[i] : PROFILES[0];
    }

    // ──────────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────────

    @Override
    public void onEnabled() {
        fullReset();
    }

    @Override
    public void onDisabled() {
        flushAll();
        fullReset();
    }

    private void fullReset() {
        positions.clear();
        selected.clear();
        held.clear();
        sentPositions.clear();
        renderSnaps = Collections.emptyList();
        releasing.set(false);
        active = false;
        activeStart = 0;
        activeTicks = 0;
        confirmedPos = null;
        cd = 0;
        velCd = 0;
        respCd = 0;
        kbCd = 0;
        flushDelayCd = 0;
        pearlCd = 0;
        damageCd = 0;
        blockhitCd = 0;
        waterCd = 0;
        voidCd = 0;
        teleportCd = 0;
        sprintResetCd = 0;
        sessions = 0;
        nextAllowMs = 0;
        prevHp = -1;
        waitGround = false;
        isRaytracing = false;
        lastWorldId = 0;
        recentTransactionTimes.clear();
        avgTransactionTime = 50;
        transactionVariance = 15;
        keepAliveSendTimes.clear();
        keepAliveMap.clear();
        avgKeepAlivePing = 50;
        lastKeepAliveId = -1;
        hasServerPos = false;
        estimatedServerTick = 0;
        lastPlayerX = lastPlayerY = lastPlayerZ = 0;
        playerSpeed = 0;
        lastPlayerSpeed = 0;
        playerAcceleration = 0;
        wasSprinting = false;
        sprintTicks = 0;
        noSprintTicks = 0;
        lastSprintState = false;
        airborneTicks = 0;
        groundTicks = 0;
        wasOnGround = true;
        lastFallDistance = 0;
        inWater = false;
        inLava = false;
        inWeb = false;
        onLadder = false;
        nearVoid = false;
        currentTargetId = -1;
        targetApproachSpeed = 0;
        lastTargetDist = 0;
        isBlocking = false;
        isEating = false;
        isUsingBow = false;
        trackedProjectiles.clear();
        lastHurtTick = -100;
        comboCount = 0;
        receivedComboCount = 0;
        justRespawned = false;
        respawnTicks = 0;
        combatPhase = CombatPhase.IDLE;
        combatPhaseTicks = 0;
        lastAttackTick = -100;
        lastAttackMs = 0;
        attacksDuringHold = 0;
        hitsReceived = 0;
        hitsLanded = 0;
        wasTrading = false;
        hasSentPos = false;
    }

    // ──────────────────────────────────────────────
    //  Tick handler — CORE LOGIC
    // ──────────────────────────────────────────────

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        isRaytracing = false;
        estimatedServerTick++;

        // ── World change detection ──
        int wid = System.identityHashCode(mc.theWorld);
        if (wid != lastWorldId) {
            flushAll();
            fullReset();
            lastWorldId = wid;
            return;
        }

        // ── Update environment state ──
        updateEnvironmentState();
        updatePlayerMovement();
        updateSprintState();
        updateAirborneState();
        updateItemUseState();
        updateCombatPhase();

        // ── Decrement all cooldowns ──
        decrementCooldowns();

        // ── Post-respawn handling (BedWars) ──
        if (justRespawned) {
            respawnTicks++;
            if (respawnTicks > prof().postRespawnCooldown) {
                justRespawned = false;
                respawnTicks = 0;
            }
            if (active) cancelHold("respawn_recovery");
            updateRenderSnaps();
            return;
        }

        // ── Flush delay handling ──
        if (flushDelayCd > 0) {
            flushDelayCd--;
            if (flushDelayCd == 0 && !held.isEmpty()) {
                flushAll();
            }
        }

        // ── KB recovery ──
        if (waitGround) {
            if (mc.thePlayer.onGround && mc.thePlayer.fallDistance < 0.01) {
                waitGround = false;
                kbCd = Math.max(kbCd, 20);
            }
            updateRenderSnaps();
            return;
        }

        // ── Dead check ──
        if (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0) {
            if (active) cancelHold("player_dead");
            updateRenderSnaps();
            return;
        }

        // ── Damage detection ──
        float hp = mc.thePlayer.getHealth();
        if (prevHp > 0 && hp < prevHp - 0.5f) {
            receivedComboCount++;
            comboCount = 0;
            lastHurtTick = mc.thePlayer.ticksExisted;
            
            if (active) {
                Profile p = prof();
                // If we're trading hits and AC handles it, be more lenient
                if (p.handleComboTrading && combatPhase == CombatPhase.TRADING) {
                    // Still cancel if taking too many hits
                    if (receivedComboCount >= 3) {
                        cancelHold("combo_received");
                    }
                } else {
                    cancelHold("damage_taken");
                }
                damageCd = p.postDamageCooldown;
            } else {
                damageCd = Math.max(damageCd, prof().postDamageCooldown / 2);
            }
        }
        prevHp = hp;

        // ── Environment safety gates ──
        if (handleEnvironmentSafety()) {
            updateRenderSnaps();
            return;
        }

        // ── Invalid player states ──
        if (mc.thePlayer.isRiding() || mc.thePlayer.isPlayerSleeping()) {
            cancelHold("invalid_state");
            updateRenderSnaps();
            return;
        }
        if (mc.thePlayer.ticksExisted < 40) {
            updateRenderSnaps();
            return;
        }

        // ── Active cooldown gates ──
        if (kbCd > 0 || velCd > 0 || pearlCd > 0 || teleportCd > 0) {
            if (active) cancelHold("cooldown_active");
            recordPositions();
            pruneDeadEntities();
            updateRenderSnaps();
            return;
        }

        // ── Item use gates ──
        if (handleItemUseSafety()) {
            updateRenderSnaps();
            return;
        }

        recordPositions();
        pruneDeadEntities();
        handleHoldLogic();

        if (active) {
            attacksDuringHold = 0; // reset per tick
            for (EntityPlayer p : safePlayerList()) {
                if (validTarget(p)) computeBestSnap(p);
            }
        }

        updateRenderSnaps();
    }

    // ──────────────────────────────────────────────
    //  Environment state updates
    // ──────────────────────────────────────────────

    private void updateEnvironmentState() {
        if (mc.thePlayer == null) return;

        inWater = mc.thePlayer.isInWater();
        inLava = mc.thePlayer.isInLava();
        inWeb = mc.thePlayer.isInsideOfMaterial(net.minecraft.block.material.Material.web);
        onLadder = mc.thePlayer.isOnLadder();

        // Void detection (BedWars/SkyWars/Sumo critical)
        nearVoid = mc.thePlayer.posY < 5;
        if (!nearVoid && mc.theWorld != null) {
            // Check blocks below
            BlockPos below = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY - 1, mc.thePlayer.posZ);
            boolean hasGround = false;
            for (int y = below.getY(); y >= Math.max(0, below.getY() - 10); y--) {
                BlockPos check = new BlockPos(below.getX(), y, below.getZ());
                if (!mc.theWorld.isAirBlock(check)) {
                    hasGround = true;
                    break;
                }
            }
            nearVoid = !hasGround;
        }

        // Edge detection (Sumo)
        edgeSituation = false;
        if (mc.thePlayer.onGround && mc.theWorld != null) {
            // Check if near edge of platform
            double edgeCheckDist = 1.5;
            boolean noBlockAhead = true;
            Vec3 lookFlat = new Vec3(mc.thePlayer.getLookVec().xCoord, 0, mc.thePlayer.getLookVec().zCoord).normalize();
            for (double d = 0.5; d <= edgeCheckDist; d += 0.5) {
                BlockPos ahead = new BlockPos(
                    mc.thePlayer.posX + lookFlat.xCoord * d,
                    mc.thePlayer.posY - 1,
                    mc.thePlayer.posZ + lookFlat.zCoord * d);
                if (!mc.theWorld.isAirBlock(ahead)) {
                    noBlockAhead = false;
                    break;
                }
            }
            edgeSituation = noBlockAhead;
        }
    }

    private void updatePlayerMovement() {
        if (mc.thePlayer == null) return;

        double dx = mc.thePlayer.posX - lastPlayerX;
        double dy = mc.thePlayer.posY - lastPlayerY;
        double dz = mc.thePlayer.posZ - lastPlayerZ;
        lastPlayerSpeed = playerSpeed;
        playerSpeed = Math.sqrt(dx * dx + dy * dy + dz * dz);
        playerAcceleration = playerSpeed - lastPlayerSpeed;
        lastPlayerX = mc.thePlayer.posX;
        lastPlayerY = mc.thePlayer.posY;
        lastPlayerZ = mc.thePlayer.posZ;
    }

    private void updateSprintState() {
        if (mc.thePlayer == null) return;

        boolean sprinting = mc.thePlayer.isSprinting();
        if (sprinting) {
            sprintTicks++;
            noSprintTicks = 0;
        } else {
            noSprintTicks++;
            if (noSprintTicks > 1) sprintTicks = 0;
        }

        // Detect sprint reset (W-tap)
        if (wasSprinting && !sprinting && noSprintTicks == 1) {
            sprintResetCd = 3; // brief cooldown during sprint reset
        }
        wasSprinting = sprinting;
        lastSprintState = sprinting;
    }

    private void updateAirborneState() {
        if (mc.thePlayer == null) return;

        if (mc.thePlayer.onGround) {
            groundTicks++;
            airborneTicks = 0;
        } else {
            airborneTicks++;
            groundTicks = 0;
        }
        lastFallDistance = mc.thePlayer.fallDistance;
        wasOnGround = mc.thePlayer.onGround;
    }

    private void updateItemUseState() {
        if (mc.thePlayer == null) return;

        isBlocking = false;
        isEating = false;
        isUsingBow = false;

        if (mc.thePlayer.isUsingItem()) {
            ItemStack held = mc.thePlayer.getHeldItem();
            if (held != null) {
                if (held.getItem() instanceof ItemSword) {
                    isBlocking = true;
                } else if (held.getItem() instanceof ItemFood) {
                    isEating = true;
                } else if (held.getItem() instanceof ItemBow) {
                    isUsingBow = true;
                }
            }
        }
    }

    private void updateCombatPhase() {
        if (mc.thePlayer == null) return;

        EntityPlayer target = findNearestTarget();
        CombatPhase prevPhase = combatPhase;

        if (target == null) {
            combatPhase = CombatPhase.IDLE;
            currentTargetId = -1;
            targetApproachSpeed = 0;
            lastTargetDist = 0;
            comboCount = 0;
            receivedComboCount = 0;
            wasTrading = false;
        } else {
            double dist = distTo(target);
            int tid = target.getEntityId();

            // Track approach speed
            if (tid == currentTargetId && lastTargetDist > 0) {
                targetApproachSpeed = lastTargetDist - dist; // positive = getting closer
            } else {
                targetApproachSpeed = 0;
            }
            currentTargetId = tid;
            lastTargetDist = dist;

            // Determine combat phase
            if (waitGround || kbCd > 5) {
                combatPhase = CombatPhase.RECOVERY;
            } else if (velCd > 0 || (mc.thePlayer.hurtTime > 0 && airborneTicks > 0)) {
                combatPhase = CombatPhase.RETREATING;
            } else if (dist > prof().safeReach() + 1.0) {
                combatPhase = CombatPhase.APPROACH;
            } else {
                // In range — are we trading?
                int recentTick = mc.thePlayer.ticksExisted - 20;
                boolean weHitRecently = lastAttackTick > recentTick;
                boolean gotHitRecently = lastHurtTick > recentTick;

                if (weHitRecently && gotHitRecently) {
                    combatPhase = CombatPhase.TRADING;
                    wasTrading = true;
                } else {
                    combatPhase = CombatPhase.ENGAGE;
                }
            }
        }

        if (combatPhase == prevPhase) {
            combatPhaseTicks++;
        } else {
            combatPhaseTicks = 0;
        }
    }

    // ──────────────────────────────────────────────
    //  Environment safety — handles all edge cases
    //  BedWars: void, respawn, fireballs, bridges
    //  SkyWars: pearls, void, islands
    //  Sumo: edges, KB, ring-out risk
    // ──────────────────────────────────────────────

    private boolean handleEnvironmentSafety() {
        Profile p = prof();

        // ── Water/Lava combat ──
        if (p.handleWaterCombat && (inWater || inLava)) {
            if (active) cancelHold("in_liquid");
            waterCd = 15;
            return true;
        }
        if (waterCd > 0 && !active) return false; // don't block recording

        // ── Web ──
        if (inWeb) {
            if (active) cancelHold("in_web");
            return true;
        }

        // ── Ladder ──
        if (onLadder) {
            if (active) cancelHold("on_ladder");
            return true;
        }

        // ── Void proximity ──
        if (nearVoid) {
            if (active) cancelHold("near_void");
            voidCd = 20;
            return true;
        }

        // ── Edge situation (Sumo) ──
        if (edgeSituation && active) {
            // Don't hold while near edge — KB during hold could ring us out
            cancelHold("edge_situation");
            return false; // don't block everything, just cancel hold
        }

        // ── High fall distance ──
        if (mc.thePlayer.fallDistance > 2.0) {
            if (active) cancelHold("falling");
            return true;
        }

        // ── Airborne handling ──
        if (p.handleAirborneStrict) {
            if (airborneTicks > p.airborneGraceTicks) {
                if (active) cancelHold("airborne_timeout");
                return true;
            }
        } else {
            // Basic: just cancel if not on ground
            if (!mc.thePlayer.onGround && active) {
                cancelHold("not_on_ground");
            }
        }

        // ── Speed potion check (movement is faster, stricter limits needed) ──
        if (mc.thePlayer.isPotionActive(Potion.moveSpeed)) {
            int amp = mc.thePlayer.getActivePotionEffect(Potion.moveSpeed).getAmplifier();
            if (playerSpeed > p.maxPlayerSpeed * (1.0 + amp * 0.2) * 1.1) {
                if (active) cancelHold("speed_too_fast");
                return false;
            }
        }

        // ── Slowness (changes reach validation timing) ──
        if (mc.thePlayer.isPotionActive(Potion.moveSlowdown)) {
            // Slowness is fine, but be aware movement prediction changes
        }

        return false;
    }

    private boolean handleItemUseSafety() {
        Profile p = prof();

        // ── Eating ──
        if (isEating) {
            if (active) cancelHold("eating");
            return true;
        }

        // ── Bow drawing ──
        if (isUsingBow) {
            if (active) cancelHold("using_bow");
            return true;
        }

        // ── Blocking (sword block) ──
        if (isBlocking && p.handleBlockhit) {
            if (active) {
                // During blockhit combo, release but with shorter cooldown
                cancelHold("blocking");
                blockhitCd = p.postBlockhitDelay;
            }
            return false; // don't block recording — we still want position data
        }

        // ── Blockhit cooldown ──
        if (blockhitCd > 0) {
            return false; // allow recording, block activation
        }

        return false;
    }

    private void decrementCooldowns() {
        if (cd > 0) cd--;
        if (velCd > 0) velCd--;
        if (respCd > 0) respCd--;
        if (kbCd > 0) kbCd--;
        if (pearlCd > 0) pearlCd--;
        if (damageCd > 0) damageCd--;
        if (blockhitCd > 0) blockhitCd--;
        if (waterCd > 0) waterCd--;
        if (voidCd > 0) voidCd--;
        if (teleportCd > 0) teleportCd--;
        if (sprintResetCd > 0) sprintResetCd--;
    }

    // ──────────────────────────────────────────────
    //  Position recording
    // ──────────────────────────────────────────────

    private void recordPositions() {
        long now = System.currentTimeMillis();
        int cap = getMaxSnapCount() + 4;
        Profile p = prof();

        for (EntityPlayer pl : safePlayerList()) {
            if (!validTarget(pl)) continue;

            int id = pl.getEntityId();
            ArrayDeque<Snap> deque = positions.computeIfAbsent(id, k -> new ArrayDeque<>(cap));

            if (!deque.isEmpty()) {
                Snap last = deque.peekFirst();
                double moveSq = sq(last.x - pl.posX) + sq(last.y - pl.posY) + sq(last.z - pl.posZ);
                if (moveSq < 0.0001) continue;

                double speed = Math.sqrt(moveSq);
                if (speed > p.maxTargetSpeed) {
                    deque.clear();
                    continue;
                }
            }

            deque.addFirst(new Snap(pl.posX, pl.posY, pl.posZ, now, estimatedServerTick));
            while (deque.size() > cap) deque.removeLast();
        }
    }

    private void pruneDeadEntities() {
        if (mc.theWorld == null) return;
        Set<Integer> alive = new HashSet<>();
        for (EntityPlayer p : safePlayerList()) {
            alive.add(p.getEntityId());
        }
        positions.keySet().retainAll(alive);
        selected.keySet().retainAll(alive);
    }

    private int getMaxSnapCount() {
        return mode.getValue() == 0 ? ticks.getValue() : Math.max(1, lag.getValue() / 50);
    }

    // ──────────────────────────────────────────────
    //  Hold logic — comprehensive bypass mechanism
    // ──────────────────────────────────────────────

    private void handleHoldLogic() {
        Profile p = prof();
        long now = System.currentTimeMillis();

        if (active) {
            // ── Speed check during hold ──
            double speedLimit = p.maxPlayerSpeed;
            if (mc.thePlayer.isPotionActive(Potion.moveSpeed)) {
                int amp = mc.thePlayer.getActivePotionEffect(Potion.moveSpeed).getAmplifier();
                speedLimit *= (1.0 + amp * 0.2);
            }
            if (playerSpeed > speedLimit) {
                endHold("speed_exceeded");
                return;
            }

            // ── Acceleration check (physics validation) ──
            if (p.predictMovementPhysics) {
                if (Math.abs(playerAcceleration) > p.maxAcceleration) {
                    endHold("acceleration_exceeded");
                    return;
                }
            }

            // ── Ground check ──
            if (!mc.thePlayer.onGround) {
                if (p.handleAirborneStrict) {
                    if (airborneTicks > p.airborneGraceTicks) {
                        cancelHold("airborne_during_hold");
                        return;
                    }
                } else {
                    cancelHold("not_on_ground_hold");
                    return;
                }
            }

            // ── KB/vel during hold ──
            if (velCd > 0 || kbCd > 0) {
                cancelHold("kb_during_hold");
                return;
            }

            // ── Sprint state validation ──
            if (p.trackSprintState && confirmedPos != null) {
                boolean currentSprint = mc.thePlayer.isSprinting();
                if (confirmedPos.sprinting && !currentSprint && sprintResetCd <= 0) {
                    // Sprint state changed — for strict ACs this matters
                    // Sprint reset during hold could desync prediction
                    if (p.accountForPrediction) {
                        endHold("sprint_state_change");
                        return;
                    }
                }
            }

            // ── Prediction drift check ──
            if (p.accountForPrediction && confirmedPos != null) {
                double movedFromConfirmed = Math.sqrt(
                    sq(mc.thePlayer.posX - confirmedPos.x) +
                    sq(mc.thePlayer.posY - confirmedPos.y) +
                    sq(mc.thePlayer.posZ - confirmedPos.z));

                long elapsed = now - activeStart;
                
                // The server predicts our movement based on:
                // 1. Our last known velocity (from C03 packets before hold)
                // 2. Friction/drag coefficients
                // 3. Sprint state
                // 4. Potion effects
                //
                // We need our actual drift to match what the server predicts
                double expectedDrift = computeExpectedDrift(elapsed);
                double maxAllowedDrift = expectedDrift + p.safeReach() * 0.15;
                maxAllowedDrift = Math.min(maxAllowedDrift, p.safeReach() * 0.25);

                if (movedFromConfirmed > maxAllowedDrift) {
                    endHold("prediction_drift");
                    return;
                }
            }

            // ── KeepAlive correlation check (New Grim, Polar, Karhu) ──
            if (p.correlateKeepAlive) {
                long holdDuration = now - activeStart;
                // Transaction ping = actual ping + hold duration
                // If (transaction_ping / keepalive_ping) > maxPingDriftRatio → flag
                long estimatedTransPing = avgKeepAlivePing + holdDuration;
                if (avgKeepAlivePing > 0) {
                    double ratio = (double) estimatedTransPing / avgKeepAlivePing;
                    if (ratio > p.maxPingDriftRatio) {
                        endHold("keepalive_correlation");
                        return;
                    }
                }
            }

            // ── Damage cooldown ──
            if (damageCd > 0) {
                cancelHold("damage_cooldown");
                return;
            }

            // ── Trading detection ──
            if (p.handleComboTrading && combatPhase == CombatPhase.TRADING) {
                // During hit trades, holding transactions is more suspicious
                // because the server sees attacks from both sides and can
                // cross-reference timing
                if (receivedComboCount >= 2) {
                    endHold("trading_detected");
                    return;
                }
            }
        }

        // ── Try to start hold ──
        if (!active && canStartHold(now)) {
            EntityPlayer t = findNearestTarget();
            if (t != null) {
                double dist = distTo(t);
                double yDiff = Math.abs(mc.thePlayer.posY - t.posY);

                double maxActivation = p.safeReach() + 0.3;
                if (yDiff > 0.3) {
                    maxActivation -= yDiff * 0.4;
                }
                maxActivation = Math.max(2.0, maxActivation);

                // ── Smart activation ──
                boolean shouldActivate = true;
                
                if (smartActivation.getValue()) {
                    // Only activate when backtracking will help
                    boolean hasCloserPast = hasCloserHistoricalPosition(t);
                    if (!hasCloserPast) shouldActivate = false;

                    // Don't activate if approaching fast (will be in range soon anyway)
                    if (targetApproachSpeed > 0.15) shouldActivate = false;

                    // Don't activate during sprint resets
                    if (sprintResetCd > 0) shouldActivate = false;

                    // Don't activate if in critical combat trading
                    if (combatPhase == CombatPhase.TRADING && p.handleComboTrading) {
                        shouldActivate = false;
                    }

                    // Don't activate while retreating
                    if (combatPhase == CombatPhase.RETREATING) shouldActivate = false;

                    // Don't activate immediately after landing a hit (combo already going)
                    if (mc.thePlayer.ticksExisted - lastAttackTick < 3) shouldActivate = false;

                    // Don't activate if LOS is required and we can't see them
                    if (p.requireLineOfSight && !hasLineOfSight(t)) shouldActivate = false;

                    // Don't activate during damage cooldown
                    if (damageCd > 0) shouldActivate = false;

                    // Don't activate near void (too risky)
                    if (nearVoid) shouldActivate = false;

                    // Don't activate on edge (sumo)
                    if (edgeSituation) shouldActivate = false;
                }

                if (shouldActivate && dist > 1.5 && dist < maxActivation 
                    && yDiff < p.maxYDiffActivation) {

                    float skipChance = p.useAdaptiveTiming
                        ? 0.08f + sessions * 0.06f
                        : 0.05f;
                    if (ThreadLocalRandom.current().nextFloat() >= skipChance) {
                        startHold(now);
                    }
                }
            }
        }

        // ── Check active hold termination ──
        if (active) {
            activeTicks++;
            long elapsed = now - activeStart;
            int maxT = Math.min(getMaxSnapCount(), p.softMaxTicks);
            long maxMs = Math.min(
                mode.getValue() == 0 ? ticks.getValue() * 50L : (long) lag.getValue(),
                p.maxHoldMs);

            // Adaptive timing: vary hold duration to avoid patterns
            if (p.useAdaptiveTiming) {
                double variance = 0.15 + ThreadLocalRandom.current().nextDouble() * 0.25;
                maxMs = (long)(maxMs * (1.0 - variance + ThreadLocalRandom.current().nextDouble() * variance * 2));
                maxMs = Math.max(50, maxMs);
            }

            EntityPlayer t = findNearestTarget();
            boolean shouldEnd = false;
            String reason = "";

            if (elapsed >= maxMs) { shouldEnd = true; reason = "time_limit"; }
            if (activeTicks >= maxT) { shouldEnd = true; reason = "tick_limit"; }
            if (held.size() >= p.maxTransactions) { shouldEnd = true; reason = "transaction_limit"; }
            if (t == null) { shouldEnd = true; reason = "no_target"; }
            if (velCd > 0 || kbCd > 0) { shouldEnd = true; reason = "kb_vel"; }

            if (t != null && !shouldEnd) {
                double dist = distTo(t);
                double yDiff = Math.abs(mc.thePlayer.posY - t.posY);
                if (dist > p.safeReach() + 0.8) { shouldEnd = true; reason = "too_far"; }
                if (yDiff > p.maxYDiffMaintain) { shouldEnd = true; reason = "y_diff"; }

                if (confirmedPos != null) {
                    double confirmedDist = snapDistFromConfirmed(t);
                    if (confirmedDist > p.safeReach()) {
                        shouldEnd = true; reason = "confirmed_too_far";
                    }
                }

                // Target teleported or moved erratically
                ArrayDeque<Snap> snaps = positions.get(t.getEntityId());
                if (snaps != null && snaps.size() >= 2) {
                    Iterator<Snap> it = snaps.iterator();
                    Snap newest = it.next();
                    Snap prev = it.next();
                    double targetMoveSpeed = Math.sqrt(
                        sq(newest.x - prev.x) + sq(newest.y - prev.y) + sq(newest.z - prev.z));
                    if (targetMoveSpeed > p.maxTargetSpeed) {
                        shouldEnd = true; reason = "target_teleport";
                    }
                }
            }

            // Absolute safety timeout
            if (elapsed > 600) { shouldEnd = true; reason = "absolute_timeout"; }

            // Transaction timing consistency (New Grim)
            if (p.strictTransactionOrder && elapsed > avgTransactionTime + transactionVariance * 3) {
                shouldEnd = true; reason = "transaction_variance";
            }

            // Critical state checks
            if (!mc.thePlayer.onGround && airborneTicks > p.airborneGraceTicks) {
                shouldEnd = true; reason = "airborne";
            }
            if (isBlocking || isEating || isUsingBow) {
                shouldEnd = true; reason = "item_use";
            }
            if (nearVoid) { shouldEnd = true; reason = "void"; }
            if (edgeSituation) { shouldEnd = true; reason = "edge"; }
            if (inWater || inLava) { shouldEnd = true; reason = "liquid"; }
            if (inWeb) { shouldEnd = true; reason = "web"; }
            if (onLadder) { shouldEnd = true; reason = "ladder"; }
            if (damageCd > 0) { shouldEnd = true; reason = "damage_cd"; }

            if (shouldEnd) endHold(reason);
        }
    }

    /**
     * Compute expected drift based on last known velocity and physics.
     * This mirrors what a prediction engine would calculate.
     */
    private double computeExpectedDrift(long holdDurationMs) {
        if (confirmedPos == null) return 0;

        double ticks = holdDurationMs / 50.0;
        double vx = confirmedPos.motionX;
        double vz = confirmedPos.motionZ;

        // Ground friction = 0.91 * 0.6 (slipperiness)
        double friction = 0.91 * 0.6;
        if (confirmedPos.sprinting) {
            friction *= 1.3; // sprint multiplier
        }

        // Speed potion
        if (mc.thePlayer.isPotionActive(Potion.moveSpeed)) {
            int amp = mc.thePlayer.getActivePotionEffect(Potion.moveSpeed).getAmplifier();
            friction *= (1.0 + 0.2 * (amp + 1));
        }

        double totalDist = 0;
        double curVx = vx;
        double curVz = vz;
        for (int i = 0; i < (int) ticks; i++) {
            totalDist += Math.sqrt(curVx * curVx + curVz * curVz);
            curVx *= friction;
            curVz *= friction;
        }

        return totalDist;
    }

    private boolean hasCloserHistoricalPosition(EntityPlayer target) {
        ArrayDeque<Snap> snaps = positions.get(target.getEntityId());
        if (snaps == null || snaps.size() < 2) return false;

        double eyeX = mc.thePlayer.posX;
        double eyeY = mc.thePlayer.posY + getEyeHeight();
        double eyeZ = mc.thePlayer.posZ;
        double currentDistSq = target.getDistanceSqToEntity(mc.thePlayer);

        int checked = 0;
        for (Snap s : snaps) {
            if (checked++ >= getMaxSnapCount()) break;
            if (checked == 1) continue;
            double distSq = s.distSqTo(eyeX, eyeY, eyeZ);
            if (distSq < currentDistSq - 0.1) return true;
        }
        return false;
    }

    private double snapDistFromConfirmed(EntityPlayer target) {
        if (confirmedPos == null) return Double.MAX_VALUE;

        ArrayDeque<Snap> snaps = positions.get(target.getEntityId());
        if (snaps == null || snaps.isEmpty()) return Double.MAX_VALUE;

        double eyeX = confirmedPos.x;
        double eyeY = confirmedPos.eyeY();
        double eyeZ = confirmedPos.z;

        double bestDist = Double.MAX_VALUE;
        int checked = 0;
        for (Snap s : snaps) {
            if (checked++ >= getMaxSnapCount()) break;
            double dist = s.distTo(eyeX, eyeY, eyeZ);
            if (dist < bestDist) bestDist = dist;
        }
        return bestDist;
    }

    private boolean hasLineOfSight(EntityPlayer target) {
        if (mc.thePlayer == null || mc.theWorld == null) return false;
        Vec3 eyePos = new Vec3(mc.thePlayer.posX,
                               mc.thePlayer.posY + getEyeHeight(),
                               mc.thePlayer.posZ);
        Vec3 targetPos = new Vec3(target.posX,
                                   target.posY + target.getEyeHeight(),
                                   target.posZ);
        MovingObjectPosition ray = mc.theWorld.rayTraceBlocks(eyePos, targetPos, false, true, false);
        return ray == null || ray.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK;
    }

    private boolean canStartHold(long now) {
        Profile p = prof();
        return cd <= 0
            && respCd <= 0
            && velCd <= 0
            && kbCd <= 0
            && flushDelayCd <= 0
            && pearlCd <= 0
            && damageCd <= 0
            && blockhitCd <= 0
            && waterCd <= 0
            && voidCd <= 0
            && teleportCd <= 0
            && sprintResetCd <= 0
            && now >= nextAllowMs
            && mc.thePlayer.onGround
            && groundTicks >= 3 // must be on ground for a few ticks
            && mc.thePlayer.getHealth() >= 6
            && !waitGround
            && !mc.thePlayer.isDead
            && mc.thePlayer.fallDistance < 0.01
            && playerSpeed < p.maxPlayerSpeed
            && held.isEmpty()
            && !nearVoid
            && !edgeSituation
            && !inWater && !inLava && !inWeb && !onLadder
            && !isBlocking && !isEating && !isUsingBow
            && !justRespawned
            && combatPhase != CombatPhase.RECOVERY
            && combatPhase != CombatPhase.RETREATING
            && airborneTicks == 0;
    }

    private void startHold(long now) {
        Profile p = prof();
        active = true;
        activeStart = now;
        activeTicks = 0;
        attacksDuringHold = 0;

        confirmedPos = new ConfirmedPosition(
            mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ,
            now, mc.thePlayer.isSneaking(), mc.thePlayer.isSprinting(),
            mc.thePlayer.motionX, mc.thePlayer.motionY, mc.thePlayer.motionZ);

        sessions++;
        if (sessions >= p.sessionsBeforeGlobal + ThreadLocalRandom.current().nextInt(2)) {
            nextAllowMs = now + p.globalCooldownMs
                        + ThreadLocalRandom.current().nextLong(2000);
            sessions = 0;
        }
    }

    private void endHold(String reason) {
        Profile p = prof();
        active = false;
        activeTicks = 0;
        cd = p.sessionCooldown;
        selected.clear();
        attacksDuringHold = 0;

        if (p.transactionFlushDelay > 0) {
            flushDelayCd = p.transactionFlushDelay;
        } else {
            flushAll();
        }
    }

    private void cancelHold(String reason) {
        active = false;
        activeTicks = 0;
        confirmedPos = null;
        selected.clear();
        attacksDuringHold = 0;
        flushAll();
        cd = Math.max(cd, prof().sessionCooldown);
    }

    // ──────────────────────────────────────────────
    //  Best snap selection
    // ──────────────────────────────────────────────

    private void computeBestSnap(EntityPlayer target) {
        Profile p = prof();
        int id = target.getEntityId();
        ArrayDeque<Snap> snaps = positions.get(id);

        if (snaps == null || snaps.isEmpty()) {
            selected.remove(id);
            return;
        }

        if (confirmedPos == null) {
            selected.remove(id);
            return;
        }

        double playerDist = distTo(target);
        if (playerDist > p.safeReach() + p.maxHExpand + 0.5) {
            selected.remove(id);
            return;
        }

        double yDiffToTarget = Math.abs(mc.thePlayer.posY - target.posY);
        if (yDiffToTarget > p.maxYDiffMaintain) {
            selected.remove(id);
            return;
        }

        // LOS check
        if (p.requireLineOfSight && !hasLineOfSight(target)) {
            selected.remove(id);
            return;
        }

        // Effective expansion limits
        double effMaxH = p.maxHExpand;
        double effMaxV = p.maxVExpand;
        if (yDiffToTarget > 0.3) {
            double penalty = (yDiffToTarget - 0.3) * 0.6;
            effMaxH = Math.max(0.02, effMaxH - penalty * 0.4);
            effMaxV = Math.max(0.01, effMaxV - penalty * 0.3);
        }

        // Reduce expansion during critical situations
        if (edgeSituation || nearVoid) {
            effMaxH *= 0.3;
            effMaxV *= 0.3;
        }

        // All eye positions for validation
        double currentEyeX = mc.thePlayer.posX;
        double currentEyeY = mc.thePlayer.posY + getEyeHeight();
        double currentEyeZ = mc.thePlayer.posZ;

        double confirmedEyeX = confirmedPos.x;
        double confirmedEyeY = confirmedPos.eyeY();
        double confirmedEyeZ = confirmedPos.z;

        // Predicted position for prediction-aware ACs
        double predictedEyeX = confirmedEyeX;
        double predictedEyeY = confirmedEyeY;
        double predictedEyeZ = confirmedEyeZ;
        if (p.accountForPrediction) {
            long holdDuration = System.currentTimeMillis() - activeStart;
            double predTicks = holdDuration / 50.0;
            double vx = confirmedPos.motionX;
            double vz = confirmedPos.motionZ;
            double vy = confirmedPos.motionY;
            // Apply friction per tick
            double friction = 0.91 * 0.6;
            double totalDx = 0, totalDy = 0, totalDz = 0;
            for (int i = 0; i < (int) predTicks && i < 10; i++) {
                totalDx += vx;
                totalDy += vy;
                totalDz += vz;
                vx *= friction;
                vz *= friction;
                vy = (vy - 0.08) * 0.98; // gravity
            }
            predictedEyeX = confirmedPos.x + totalDx;
            predictedEyeY = confirmedPos.eyeY() + totalDy;
            predictedEyeZ = confirmedPos.z + totalDz;
        }

        // Sent position (what the server actually received from us)
        double sentEyeX = hasSentPos ? lastSentX : currentEyeX;
        double sentEyeY = hasSentPos ? (lastSentY + getEyeHeight()) : currentEyeY;
        double sentEyeZ = hasSentPos ? lastSentZ : currentEyeZ;

        double safeReachSq = sq(p.safeReach());
        double strictReachSq = sq(p.safeReach() - 0.03);
        double ultraStrictReachSq = sq(p.safeReach() - 0.06);

        Vec3 look = mc.thePlayer.getLookVec();
        int maxCheck = Math.min(snaps.size(), getMaxSnapCount());

        Snap best = null;
        double bestScore = -1;
        int idx = 0;

        for (Snap s : snaps) {
            if (idx++ >= maxCheck) break;

            double snapYDiff = Math.abs(s.y - target.posY);
            if (snapYDiff > effMaxV) continue;

            double hExpand = Math.sqrt(sq(s.x - target.posX) + sq(s.z - target.posZ));
            if (hExpand > effMaxH) continue;

            // ═══ COMPREHENSIVE REACH CHECKS ═══

            // 1. From current eye position (client raytrace)
            double distFromCurrent = s.distTo(currentEyeX, currentEyeY, currentEyeZ);
            if (distFromCurrent > p.safeReach()) continue;

            // 2. From confirmed position (WHAT GRIM CHECKS — most important)
            double distFromConfirmedSq = s.distSqTo(confirmedEyeX, confirmedEyeY, confirmedEyeZ);
            if (distFromConfirmedSq > strictReachSq) continue;

            // 3. From last tick position
            double distFromLastTickSq = s.distSqTo(
                mc.thePlayer.lastTickPosX,
                mc.thePlayer.lastTickPosY + getEyeHeight(),
                mc.thePlayer.lastTickPosZ);
            if (distFromLastTickSq > safeReachSq) continue;

            // 4. From predicted position (prediction ACs)
            if (p.accountForPrediction) {
                double distFromPredicted = s.distTo(predictedEyeX, predictedEyeY, predictedEyeZ);
                if (distFromPredicted > p.safeReach()) continue;
            }

            // 5. From known server position
            if (hasServerPos) {
                double distFromServerSq = s.distSqTo(serverPosX, serverPosY + getEyeHeight(), serverPosZ);
                if (distFromServerSq > safeReachSq) continue;
            }

            // 6. From last sent C03 position
            if (hasSentPos && p.trackC03Positions) {
                double distFromSentSq = s.distSqTo(sentEyeX, sentEyeY, sentEyeZ);
                if (distFromSentSq > safeReachSq) continue;
            }

            // 7. Angle validation
            if (p.validateAttackAngle) {
                AxisAlignedBB bb = s.box();
                double centerX = (bb.minX + bb.maxX) / 2 - currentEyeX;
                double centerY = (bb.minY + bb.maxY) / 2 - currentEyeY;
                double centerZ = (bb.minZ + bb.maxZ) / 2 - currentEyeZ;
                double len = Math.sqrt(centerX * centerX + centerY * centerY + centerZ * centerZ);
                if (len > 0.01) {
                    double dot = look.xCoord * (centerX / len) +
                                 look.yCoord * (centerY / len) +
                                 look.zCoord * (centerZ / len);
                    double angleDeg = Math.toDegrees(Math.acos(Math.min(1, Math.max(-1, dot))));
                    if (angleDeg > p.maxAttackAngle) continue;
                }
            }

            // ═══ SCORING ═══
            double confirmedReachRatio = 1.0 - (Math.sqrt(distFromConfirmedSq) / p.safeReach());
            double currentReachRatio = 1.0 - (distFromCurrent / p.safeReach());

            AxisAlignedBB bb = s.box();
            double dx = (bb.minX + bb.maxX) / 2 - currentEyeX;
            double dy = (bb.minY + bb.maxY) / 2 - currentEyeY;
            double dz = (bb.minZ + bb.maxZ) / 2 - currentEyeZ;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double align = len > 0.01
                ? (look.xCoord * dx / len + look.yCoord * dy / len + look.zCoord * dz / len + 1) / 2
                : 0.5;
            double recency = Math.pow(0.80, idx);
            double yPenalty = snapYDiff / Math.max(effMaxV, 0.001);

            double score = confirmedReachRatio * 0.40
                         + currentReachRatio * 0.10
                         + align * 0.15
                         + recency * 0.25
                         + (1 - yPenalty) * 0.10;

            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }

        if (best != null && bestScore > 0.20) {
            selected.put(id, best);
        } else {
            selected.remove(id);
        }
    }

    // ──────────────────────────────────────────────
    //  Packet handler
    // ──────────────────────────────────────────────

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null) return;

        if (event.getType() == EventType.SEND) {
            handleOutbound(event);
        } else if (event.getType() == EventType.RECEIVE) {
            handleInbound(event);
        }
    }

    private void handleOutbound(PacketEvent event) {
        if (releasing.get()) return;

        Packet<?> pkt = event.getPacket();

        // ── Track outgoing position packets ──
        if (pkt instanceof C03PacketPlayer) {
            C03PacketPlayer c03 = (C03PacketPlayer) pkt;
            if (c03.isMoving()) {
                lastSentX = c03.getPositionX();
                lastSentY = c03.getPositionY();
                lastSentZ = c03.getPositionZ();
                lastSentOnGround = c03.isOnGround();
                lastSentTime = System.currentTimeMillis();
                hasSentPos = true;

                // Trim old sent positions
                while (sentPositions.size() > 40) sentPositions.removeFirst();
                sentPositions.addLast(new SentPosition(
                    c03.getPositionX(), c03.getPositionY(), c03.getPositionZ(),
                    c03.isOnGround(), System.currentTimeMillis()));
            }
        }

        // ── Track attack packets for hit sequence validation ──
        if (pkt instanceof C02PacketUseEntity) {
            C02PacketUseEntity c02 = (C02PacketUseEntity) pkt;
            if (c02.getAction() == C02PacketUseEntity.Action.ATTACK) {
                lastAttackTick = mc.thePlayer.ticksExisted;
                long now = System.currentTimeMillis();

                if (active) {
                    Profile p = prof();
                    attacksDuringHold++;

                    // Validate attack timing
                    if (p.validateHitSequence && lastAttackMs > 0) {
                        long interval = now - lastAttackMs;
                        if (interval < p.minAttackInterval) {
                            // Attacking too fast during hold — suspicious
                            endHold("attack_too_fast");
                        }
                    }

                    // Track hits landed
                    comboCount++;
                    receivedComboCount = 0;
                }

                lastAttackMs = now;
            }
        }

        // ── Track KeepAlive sends (for correlation) ──
        if (pkt instanceof C00PacketKeepAlive) {
            C00PacketKeepAlive ka = (C00PacketKeepAlive) pkt;
            // Can't easily correlate since server generates ID
            // But we track send times
        }

        // ── Track pearl throws ──
        if (pkt instanceof C08PacketPlayerBlockPlacement) {
            C08PacketPlayerBlockPlacement c08 = (C08PacketPlayerBlockPlacement) pkt;
            ItemStack held = mc.thePlayer.getHeldItem();
            if (held != null && held.getItem() == Items.ender_pearl) {
                if (active) cancelHold("pearl_thrown");
                pearlCd = prof().postPearlCooldown;
            }
        }

        // ── Sprint action packets ──
        if (pkt instanceof C0BPacketEntityAction) {
            C0BPacketEntityAction c0b = (C0BPacketEntityAction) pkt;
            if (c0b.getAction() == C0BPacketEntityAction.Action.START_SPRINTING ||
                c0b.getAction() == C0BPacketEntityAction.Action.STOP_SPRINTING) {
                // Sprint state change during hold — may need to end
                if (active && prof().trackSprintState) {
                    // Allow brief sprint resets (W-tap) but not prolonged changes
                    if (c0b.getAction() == C0BPacketEntityAction.Action.STOP_SPRINTING) {
                        sprintResetCd = 3;
                    }
                }
            }
        }

        // ── Only hold transaction confirmations ──
        if (!(pkt instanceof C0FPacketConfirmTransaction)) return;

        // Safety gates
        if (!active) return;
        if (waitGround || kbCd > 0 || velCd > 0 || respCd > 0) return;
        if (pearlCd > 0 || damageCd > 0 || teleportCd > 0) return;
        if (!mc.thePlayer.onGround && airborneTicks > prof().airborneGraceTicks) return;
        if (mc.thePlayer.isDead) return;
        if (nearVoid || edgeSituation) return;
        if (inWater || inLava || inWeb || onLadder) return;

        Profile p = prof();

        // KeepAlive correlation check before holding
        if (p.correlateKeepAlive) {
            long now = System.currentTimeMillis();
            long holdTime = now - activeStart;
            long estimatedTransPing = avgKeepAlivePing + holdTime;
            if (avgKeepAlivePing > 0) {
                double ratio = (double) estimatedTransPing / avgKeepAlivePing;
                if (ratio > p.maxPingDriftRatio) {
                    endHold("keepalive_correlation_pre");
                    return;
                }
            }
        }

        // Transaction timing consistency (New Grim)
        if (p.strictTransactionOrder) {
            long now = System.currentTimeMillis();
            long holdTime = now - activeStart;
            if (holdTime > avgTransactionTime * 2 + transactionVariance * 2) {
                endHold("transaction_timing");
                return;
            }
        }

        event.setCancelled(true);
        held.addLast(new HeldTransaction(
            pkt, System.currentTimeMillis(),
            new ConfirmedPosition(
                mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ,
                System.currentTimeMillis(), mc.thePlayer.isSneaking(),
                mc.thePlayer.isSprinting(),
                mc.thePlayer.motionX, mc.thePlayer.motionY, mc.thePlayer.motionZ)));

        if (held.size() >= p.maxTransactions) {
            endHold("max_transactions");
        }
    }

    private void handleInbound(PacketEvent event) {
        Packet<?> pkt = event.getPacket();

        // ── Transaction timing tracking ──
        if (pkt instanceof S32PacketConfirmTransaction) {
            long now = System.currentTimeMillis();
            recentTransactionTimes.addLast(now);
            while (recentTransactionTimes.size() > 20) {
                recentTransactionTimes.removeFirst();
            }
            updatePingEstimate();
        }

        // ── KeepAlive ping tracking ──
        if (pkt instanceof S00PacketKeepAlive) {
            S00PacketKeepAlive ka = (S00PacketKeepAlive) pkt;
            long now = System.currentTimeMillis();
            keepAliveSendTimes.addLast(now);
            while (keepAliveSendTimes.size() > 10) {
                keepAliveSendTimes.removeFirst();
            }
            lastKeepAliveId = ka.func_149134_c();
            keepAliveMap.put(lastKeepAliveId, now);
        }

        // ── Knockback velocity ──
        if (pkt instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity vel = (S12PacketEntityVelocity) pkt;
            if (vel.getEntityID() == mc.thePlayer.getEntityId()) {
                cancelHold("knockback");
                velCd = 30;
                kbCd = 45;
                receivedComboCount++;
                comboCount = 0;
                lastHurtTick = mc.thePlayer.ticksExisted;

                // Detect critical KB (sumo ring-out risk)
                double kbStrength = Math.sqrt(
                    sq(vel.getMotionX() / 8000.0) + sq(vel.getMotionZ() / 8000.0));
                if (edgeSituation && kbStrength > 0.3) {
                    criticalKBSituation = true;
                    kbCd = Math.max(kbCd, 60);
                }

                if (vel.getMotionY() > 100) {
                    waitGround = true;
                }
            }

            // Track target getting KB (we hit them)
            EntityPlayer target = null;
            if (currentTargetId >= 0 && mc.theWorld != null) {
                Entity e = mc.theWorld.getEntityByID(vel.getEntityID());
                if (e instanceof EntityPlayer && e.getEntityId() == currentTargetId) {
                    hitsLanded++;
                }
            }
            return;
        }

        // ── Explosion ──
        if (pkt instanceof S27PacketExplosion) {
            S27PacketExplosion exp = (S27PacketExplosion) pkt;
            if (exp.func_149149_c() != 0 || exp.func_149144_d() != 0 || exp.func_149147_e() != 0) {
                cancelHold("explosion");
                velCd = 30;
                kbCd = 45;
                waitGround = true;
            }
            return;
        }

        // ── Server position correction (teleport) ──
        if (pkt instanceof S08PacketPlayerPosLook) {
            S08PacketPlayerPosLook pos = (S08PacketPlayerPosLook) pkt;
            serverPosX = pos.getX();
            serverPosY = pos.getY();
            serverPosZ = pos.getZ();
            hasServerPos = true;
            cancelHold("server_teleport");
            cd = Math.max(cd, prof().sessionCooldown + 15);
            velCd = Math.max(velCd, 20);
            kbCd = Math.max(kbCd, 30);
            teleportCd = 40;

            // Also update sent position since server corrected us
            lastSentX = pos.getX();
            lastSentY = pos.getY();
            lastSentZ = pos.getZ();
            hasSentPos = true;
            return;
        }

        // ── World change / disconnect ──
        if (pkt instanceof S07PacketRespawn) {
            flushAll();
            fullReset();
            respCd = 60;
            justRespawned = true;
            respawnTicks = 0;
            return;
        }
        if (pkt instanceof S01PacketJoinGame || pkt instanceof S40PacketDisconnect) {
            flushAll();
            fullReset();
            respCd = 60;
            return;
        }

        // ── Entity teleport ──
        if (pkt instanceof S18PacketEntityTeleport) {
            int eid = ((S18PacketEntityTeleport) pkt).getEntityId();
            clearEntityData(eid);
            return;
        }

        // ── Entity death ──
        if (pkt instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus status = (S19PacketEntityStatus) pkt;
            if (status.getOpCode() == 3 && mc.theWorld != null) {
                Entity e = status.getEntity(mc.theWorld);
                if (e != null) {
                    clearEntityData(e.getEntityId());
                    if (e == mc.thePlayer) {
                        flushAll();
                        fullReset();
                        respCd = 60;
                        justRespawned = true;
                        respawnTicks = 0;
                    }
                }
            }
            return;
        }

        // ── Entity destroy ──
        if (pkt instanceof S13PacketDestroyEntities) {
            for (int eid : ((S13PacketDestroyEntities) pkt).getEntityIDs()) {
                clearEntityData(eid);
            }
            return;
        }

        // ── Projectile tracking (fishing rod, snowball, egg) ──
        if (pkt instanceof S0EPacketSpawnObject) {
            S0EPacketSpawnObject spawn = (S0EPacketSpawnObject) pkt;
            // Type 90 = fishing bobber, 61 = snowball, 62 = egg
            int type = spawn.getType();
            if (type == 90 || type == 61 || type == 62) {
                // Check if it's aimed at us
                if (prof().handleProjectileKB) {
                    trackedProjectiles.add(spawn.getEntityID());
                }
            }
            return;
        }

        // ── Spawn player (potential target) ──
        if (pkt instanceof S0CPacketSpawnPlayer) {
            // New player spawned — their entity may not have ticksExisted yet
            // Don't need special handling, validTarget checks ticksExisted
            return;
        }

        // ── Game state change (BedWars bed break, game over, etc.) ──
        if (pkt instanceof S2BPacketChangeGameState) {
            S2BPacketChangeGameState state = (S2BPacketChangeGameState) pkt;
            // Reason 1 = begin raining, 2 = end raining, etc.
            // Reason 4 = credits (game end)
            if (state.getGameState() == 4) {
                flushAll();
                fullReset();
            }
            return;
        }

        // ── Combat event (1.8 doesn't have this but some servers send it) ──
        // Handle S42PacketCombatEvent if available

        // ── Entity effect (potion) ──
        if (pkt instanceof S1DPacketEntityEffect) {
            S1DPacketEntityEffect effect = (S1DPacketEntityEffect) pkt;
            if (effect.getEntityId() == mc.thePlayer.getEntityId()) {
                // Potion applied to us — might affect movement prediction
                // Speed/Slowness changes physics, so flush if active
                if (active && (effect.getEffectId() == Potion.moveSpeed.id
                            || effect.getEffectId() == Potion.moveSlowdown.id)) {
                    endHold("potion_change");
                }
            }
            return;
        }

        // ── Remove entity effect ──
        if (pkt instanceof S1EPacketRemoveEntityEffect) {
            S1EPacketRemoveEntityEffect remove = (S1EPacketRemoveEntityEffect) pkt;
            if (remove.getEntityId() == mc.thePlayer.getEntityId()) {
                if (active && (remove.getEffectId() == Potion.moveSpeed.id
                            || remove.getEffectId() == Potion.moveSlowdown.id)) {
                    endHold("potion_removed");
                }
            }
            return;
        }

        // ── Health update ──
        if (pkt instanceof S06PacketUpdateHealth) {
            S06PacketUpdateHealth health = (S06PacketUpdateHealth) pkt;
            if (health.getHealth() <= 0) {
                flushAll();
                fullReset();
                respCd = 60;
                justRespawned = true;
            }
            return;
        }

        // ── Block change near us (bed break, block placing) ──
        if (pkt instanceof S23PacketBlockChange) {
            // Block changed — could affect collision/movement
            // Only care if it's near us
            S23PacketBlockChange change = (S23PacketBlockChange) pkt;
            BlockPos pos = change.getBlockPosition();
            if (mc.thePlayer != null) {
                double distSq = mc.thePlayer.getDistanceSq(pos);
                if (distSq < 4 && active) {
                    // Block changed right next to us — could affect ground state
                    endHold("block_change_nearby");
                }
            }
        }
    }

    private void updatePingEstimate() {
        if (recentTransactionTimes.size() < 3) return;

        Long[] times = recentTransactionTimes.toArray(new Long[0]);
        long sum = 0;
        int count = 0;
        for (int i = 1; i < times.length; i++) {
            long diff = times[i] - times[i-1];
            if (diff > 0 && diff < 2000) {
                sum += diff;
                count++;
            }
        }
        if (count > 0) {
            avgTransactionTime = sum / count;

            long mean = avgTransactionTime;
            long varSum = 0;
            int vc = 0;
            for (int i = 1; i < times.length; i++) {
                long diff = times[i] - times[i-1];
                if (diff > 0 && diff < 2000) {
                    varSum += sqL(diff - mean);
                    vc++;
                }
            }
            if (vc > 0) {
                transactionVariance = (long) Math.sqrt(varSum / vc);
                transactionVariance = Math.max(transactionVariance, 10);
            }
        }
    }

    // ── KeepAlive ping estimation ──
    // Called when we receive S00 and send C00 response
    // We estimate RTT by tracking when we receive keepalive and when we respond
    private void updateKeepAlivePing(long id) {
        Long sentTime = keepAliveMap.remove(id);
        if (sentTime != null) {
            long rtt = System.currentTimeMillis() - sentTime;
            if (rtt > 0 && rtt < 5000) {
                // Smooth average
                avgKeepAlivePing = (avgKeepAlivePing * 3 + rtt) / 4;
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Hitbox expansion — called from attack/raytrace
    // ──────────────────────────────────────────────

    public AxisAlignedBB getExpandedHitbox(Entity entity, AxisAlignedBB cur) {
        if (!isEnabled()) return null;
        if (!(entity instanceof EntityPlayer)) return null;
        if (entity == mc.thePlayer) return null;
        if (cur == null) return null;
        if (!active) return null;
        if (mc.thePlayer == null) return null;

        // ── Comprehensive safety gates ──
        if (velCd > 0 || kbCd > 0 || waitGround) return null;
        if (pearlCd > 0 || damageCd > 0 || teleportCd > 0) return null;
        if (!mc.thePlayer.onGround && airborneTicks > prof().airborneGraceTicks) return null;
        if (confirmedPos == null) return null;
        if (nearVoid || edgeSituation) return null;
        if (inWater || inLava || inWeb || onLadder) return null;
        if (isEating || isUsingBow) return null;
        if (mc.thePlayer.isDead) return null;
        if (justRespawned) return null;

        Profile p = prof();
        Snap s = selected.get(entity.getEntityId());
        if (s == null) return null;

        // ── Target validation ──
        EntityPlayer targetPlayer = (EntityPlayer) entity;
        if (!validTarget(targetPlayer)) return null;

        double yDiffToTarget = Math.abs(mc.thePlayer.posY - entity.posY);
        if (yDiffToTarget > p.maxYDiffMaintain) return null;

        // Effective limits
        double effMaxH = p.maxHExpand;
        double effMaxV = p.maxVExpand;
        if (yDiffToTarget > 0.3) {
            double penalty = (yDiffToTarget - 0.3) * 0.6;
            effMaxH = Math.max(0.02, effMaxH - penalty * 0.4);
            effMaxV = Math.max(0.01, effMaxV - penalty * 0.3);
        }

        // Safe mode: reduce expansion further
        if (safeMode.getValue()) {
            effMaxH *= 0.8;
            effMaxV *= 0.8;
        }

        // ═══ ALL POSITION REACH CHECKS ═══

        double currentEyeX = mc.thePlayer.posX;
        double currentEyeY = mc.thePlayer.posY + getEyeHeight();
        double currentEyeZ = mc.thePlayer.posZ;

        double confirmedEyeX = confirmedPos.x;
        double confirmedEyeY = confirmedPos.eyeY();
        double confirmedEyeZ = confirmedPos.z;

        double safeReach = p.safeReach();
        double strictReach = safeReach - 0.05;
        double ultraStrictReach = safeReach - 0.08;

        // Check 1: From current position
        if (s.distTo(currentEyeX, currentEyeY, currentEyeZ) > safeReach) {
            selected.remove(entity.getEntityId());
            return null;
        }

        // Check 2: From confirmed position (GRIM'S CHECK)
        if (s.distTo(confirmedEyeX, confirmedEyeY, confirmedEyeZ) > strictReach) {
            selected.remove(entity.getEntityId());
            return null;
        }

        // Check 3: From last tick position
        double lastTickEyeY = mc.thePlayer.lastTickPosY + getEyeHeight();
        if (s.distTo(mc.thePlayer.lastTickPosX, lastTickEyeY, mc.thePlayer.lastTickPosZ) > safeReach) {
            selected.remove(entity.getEntityId());
            return null;
        }

        // Check 4: From known server position
        if (hasServerPos) {
            double serverEyeY = serverPosY + getEyeHeight();
            if (s.distTo(serverPosX, serverEyeY, serverPosZ) > safeReach) {
                selected.remove(entity.getEntityId());
                return null;
            }
        }

        // Check 5: From last sent C03 position
        if (hasSentPos && p.trackC03Positions) {
            double sentEyeY = lastSentY + getEyeHeight();
            if (s.distTo(lastSentX, sentEyeY, lastSentZ) > safeReach) {
                selected.remove(entity.getEntityId());
                return null;
            }
        }

        // Check 6: From predicted position
        if (p.accountForPrediction) {
            long holdDuration = System.currentTimeMillis() - activeStart;
            double predTicks = holdDuration / 50.0;
            double vx = confirmedPos.motionX;
            double vy = confirmedPos.motionY;
            double vz = confirmedPos.motionZ;
            double friction = 0.91 * 0.6;
            double totalDx = 0, totalDy = 0, totalDz = 0;
            for (int i = 0; i < (int) predTicks && i < 10; i++) {
                totalDx += vx;
                totalDy += vy;
                totalDz += vz;
                vx *= friction;
                vz *= friction;
                vy = (vy - 0.08) * 0.98;
            }
            double predX = confirmedPos.x + totalDx;
            double predY = confirmedPos.eyeY() + totalDy;
            double predZ = confirmedPos.z + totalDz;
            if (s.distTo(predX, predY, predZ) > safeReach) {
                selected.remove(entity.getEntityId());
                return null;
            }
        }

        // Check 7: Attack angle validation
        if (p.validateAttackAngle) {
            AxisAlignedBB sbox = s.box();
            Vec3 look = mc.thePlayer.getLookVec();
            double centerX = (sbox.minX + sbox.maxX) / 2 - currentEyeX;
            double centerY = (sbox.minY + sbox.maxY) / 2 - currentEyeY;
            double centerZ = (sbox.minZ + sbox.maxZ) / 2 - currentEyeZ;
            double len = Math.sqrt(centerX * centerX + centerY * centerY + centerZ * centerZ);
            if (len > 0.01) {
                double dot = look.xCoord * (centerX / len) +
                             look.yCoord * (centerY / len) +
                             look.zCoord * (centerZ / len);
                double angleDeg = Math.toDegrees(Math.acos(Math.min(1, Math.max(-1, dot))));
                if (angleDeg > p.maxAttackAngle) {
                    return null;
                }
            }
        }

        // ═══ BUILD EXPANDED BOX ═══
        AxisAlignedBB sb = s.box();
        double curCx = (cur.minX + cur.maxX) / 2;
        double curCz = (cur.minZ + cur.maxZ) / 2;
        double curCy = (cur.minY + cur.maxY) / 2;
        double limH = effMaxH + 0.3;

        double nX = clamp(Math.min(cur.minX, sb.minX), curCx - limH, curCx + limH);
        double xX = clamp(Math.max(cur.maxX, sb.maxX), curCx - limH, curCx + limH);
        double nZ = clamp(Math.min(cur.minZ, sb.minZ), curCz - limH, curCz + limH);
        double xZ = clamp(Math.max(cur.maxZ, sb.maxZ), curCz - limH, curCz + limH);
        double nY = Math.max(Math.min(cur.minY, sb.minY), curCy - effMaxV - 0.9);
        double xY = Math.min(Math.max(cur.maxY, sb.maxY), curCy + 0.9 + effMaxV);

        AxisAlignedBB exp = new AxisAlignedBB(nX, nY, nZ, xX, xY, xZ);

        // Validate expanded box against ALL reach positions
        if (!validateExpandedBoxReach(exp, p)) {
            exp = binarySearchSafeBox(cur, nX, nY, nZ, xX, xY, xZ, p);
            if (exp == null) return null;
        }

        // Ultra-strict final validation from confirmed position
        double finalReachSq = sq(ultraStrictReach);
        if (!isBoxSafeFromPoint(exp, confirmedEyeX, confirmedEyeY, confirmedEyeZ, finalReachSq)) {
            return null;
        }

        // Check meaningful expansion
        if (exp.minX >= cur.minX - 0.01 && exp.maxX <= cur.maxX + 0.01
         && exp.minY >= cur.minY - 0.01 && exp.maxY <= cur.maxY + 0.01
         && exp.minZ >= cur.minZ - 0.01 && exp.maxZ <= cur.maxZ + 0.01) {
            return null;
        }

        return exp;
    }

    private boolean validateExpandedBoxReach(AxisAlignedBB box, Profile p) {
        double safeReachSq = sq(p.safeReach());
        double strictReachSq = sq(p.safeReach() - 0.05);
        double ultraStrictReachSq = sq(p.safeReach() - 0.08);

        // From current eye
        double eyeY = mc.thePlayer.posY + getEyeHeight();
        if (!isBoxSafeFromPoint(box, mc.thePlayer.posX, eyeY, mc.thePlayer.posZ, safeReachSq))
            return false;

        // From confirmed position (strictest — this is what matters)
        if (confirmedPos != null) {
            if (!isBoxSafeFromPoint(box, confirmedPos.x, confirmedPos.eyeY(), confirmedPos.z, strictReachSq))
                return false;
        }

        // From last tick
        double lastEyeY = mc.thePlayer.lastTickPosY + getEyeHeight();
        if (!isBoxSafeFromPoint(box, mc.thePlayer.lastTickPosX, lastEyeY, mc.thePlayer.lastTickPosZ, safeReachSq))
            return false;

        // From server position
        if (hasServerPos) {
            double serverEyeY = serverPosY + getEyeHeight();
            if (!isBoxSafeFromPoint(box, serverPosX, serverEyeY, serverPosZ, safeReachSq))
                return false;
        }

        // From sent position
        if (hasSentPos && p.trackC03Positions) {
            double sentEyeY = lastSentY + getEyeHeight();
            if (!isBoxSafeFromPoint(box, lastSentX, sentEyeY, lastSentZ, safeReachSq))
                return false;
        }

        // From predicted position
        if (p.accountForPrediction && confirmedPos != null) {
            long holdDuration = System.currentTimeMillis() - activeStart;
            double predTicks = holdDuration / 50.0;
            double vx = confirmedPos.motionX;
            double vy = confirmedPos.motionY;
            double vz = confirmedPos.motionZ;
            double friction = 0.91 * 0.6;
            double totalDx = 0, totalDy = 0, totalDz = 0;
            for (int i = 0; i < (int) predTicks && i < 10; i++) {
                totalDx += vx;
                totalDy += vy;
                totalDz += vz;
                vx *= friction;
                vz *= friction;
                vy = (vy - 0.08) * 0.98;
            }
            double predX = confirmedPos.x + totalDx;
            double predY = confirmedPos.eyeY() + totalDy;
            double predZ = confirmedPos.z + totalDz;
            if (!isBoxSafeFromPoint(box, predX, predY, predZ, safeReachSq))
                return false;
        }

        return true;
    }

    private boolean isBoxSafeFromPoint(AxisAlignedBB box, double px, double py, double pz, double maxDistSq) {
        double cx = clamp(px, box.minX, box.maxX);
        double cy = clamp(py, box.minY, box.maxY);
        double cz = clamp(pz, box.minZ, box.maxZ);
        return sq(cx - px) + sq(cy - py) + sq(cz - pz) <= maxDistSq;
    }

    private AxisAlignedBB binarySearchSafeBox(AxisAlignedBB cur,
            double nX, double nY, double nZ, double xX, double xY, double xZ,
            Profile p) {

        double lo = 0, hi = 1;
        AxisAlignedBB safe = null;

        for (int i = 0; i < 16; i++) {
            double m = (lo + hi) / 2;
            AxisAlignedBB test = new AxisAlignedBB(
                lerp(cur.minX, nX, m), lerp(cur.minY, nY, m), lerp(cur.minZ, nZ, m),
                lerp(cur.maxX, xX, m), lerp(cur.maxY, xY, m), lerp(cur.maxZ, xZ, m));

            if (validateExpandedBoxReach(test, p)) {
                lo = m;
                safe = test;
            } else {
                hi = m;
            }
        }

        return safe;
    }

    // ──────────────────────────────────────────────
    //  Packet flush
    // ──────────────────────────────────────────────

    private void flushAll() {
        if (held.isEmpty()) return;
        if (mc.getNetHandler() == null) {
            held.clear();
            return;
        }

        releasing.set(true);
        try {
            HeldTransaction ht;
            while ((ht = held.pollFirst()) != null) {
                try {
                    mc.getNetHandler().addToSendQueue(ht.packet);
                } catch (Exception ignored) {
                    held.clear();
                    break;
                }
            }
        } finally {
            releasing.set(false);
            confirmedPos = null;
        }
    }

    // ──────────────────────────────────────────────
    //  Utility
    // ──────────────────────────────────────────────

    private double getEyeHeight() {
        return mc.thePlayer.isSneaking() ? 1.54 : 1.62;
    }

    private void clearEntityData(int id) {
        positions.remove(id);
        selected.remove(id);
        if (id == currentTargetId) {
            currentTargetId = -1;
            lastTargetDist = 0;
            targetApproachSpeed = 0;
        }
    }

    private boolean validTarget(EntityPlayer p) {
        if (p == null || p == mc.thePlayer) return false;
        if (p.isDead || p.deathTime > 0) return false;
        if (p.isInvisible()) return false;
        if (TeamUtil.isFriend(p) || TeamUtil.isBot(p)) return false;
        if (distTo(p) > 7) return false;
        if (p.ticksExisted < prof().minTicksExisted) return false;
        // Additional: don't target players in spectator mode / creative
        if (p.capabilities.isCreativeMode) return false;
        // Don't target players who are already dead but not yet removed
        if (p.getHealth() <= 0) return false;
        return true;
    }

    private EntityPlayer findNearestTarget() {
        if (mc.theWorld == null) return null;
        EntityPlayer best = null;
        double bestDistSq = 49;
        for (EntityPlayer p : safePlayerList()) {
            if (!validTarget(p)) continue;
            double dSq = distSqTo(p);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = p;
            }
        }
        return best;
    }

    private List<EntityPlayer> safePlayerList() {
        if (mc.theWorld == null) return Collections.emptyList();
        try {
            return new ArrayList<>(mc.theWorld.playerEntities);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private double distTo(Entity e) {
        return Math.sqrt(distSqTo(e));
    }

    private double distSqTo(Entity e) {
        return sq(mc.thePlayer.posX - e.posX)
             + sq(mc.thePlayer.posY - e.posY)
             + sq(mc.thePlayer.posZ - e.posZ);
    }

    // ──────────────────────────────────────────────
    //  Render
    // ──────────────────────────────────────────────

    private void updateRenderSnaps() {
        if (!renderEnabled.getValue() || !active || selected.isEmpty()) {
            renderSnaps = Collections.emptyList();
            return;
        }

        List<Snap> list = new ArrayList<>(selected.size());
        for (Map.Entry<Integer, Snap> e : selected.entrySet()) {
            if (mc.theWorld == null) break;
            Entity et = mc.theWorld.getEntityByID(e.getKey());
            if (et instanceof EntityPlayer && distTo(et) <= 7) {
                list.add(e.getValue());
            }
        }
        renderSnaps = list;
    }

    @EventTarget
    public void onRender(Render3DEvent event) {
        if (!isEnabled() || !renderEnabled.getValue()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        List<Snap> snaps = renderSnaps;
        if (snaps.isEmpty()) return;

        int rgb = color.getValue();
        float r = ((rgb >> 16) & 0xFF) / 255F;
        float g = ((rgb >> 8) & 0xFF) / 255F;
        float b = (rgb & 0xFF) / 255F;

        RenderManager rm = mc.getRenderManager();

        GlStateManager.pushMatrix();
        GlStateManager.pushAttrib();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glEnable(GL11.GL_POLYGON_SMOOTH);

        for (Snap s : snaps) {
            AxisAlignedBB bx = new AxisAlignedBB(
                s.x - rm.viewerPosX - 0.3,
                s.y - rm.viewerPosY,
                s.z - rm.viewerPosZ - 0.3,
                s.x - rm.viewerPosX + 0.3,
                s.y - rm.viewerPosY + 1.8,
                s.z - rm.viewerPosZ + 0.3);

            drawFill(bx, r, g, b, 0.18F);
            GL11.glLineWidth(1.5F);
            drawOutline(bx, r, g, b, 0.55F);
        }

        GL11.glDisable(GL11.GL_POLYGON_SMOOTH);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GlStateManager.enableCull();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.resetColor();
        GlStateManager.popAttrib();
        GlStateManager.popMatrix();
    }

    private static void drawFill(AxisAlignedBB b, float r, float g, float bl, float a) {
        Tessellator t = Tessellator.getInstance();
        WorldRenderer w = t.getWorldRenderer();
        w.begin(7, DefaultVertexFormats.POSITION_COLOR);
        w.pos(b.minX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        t.draw();
    }

    private static void drawOutline(AxisAlignedBB b, float r, float g, float bl, float a) {
        GL11.glColor4f(r, g, bl, a);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex3d(b.minX, b.minY, b.minZ); GL11.glVertex3d(b.maxX, b.minY, b.minZ);
        GL11.glVertex3d(b.maxX, b.minY, b.minZ); GL11.glVertex3d(b.maxX, b.minY, b.maxZ);
        GL11.glVertex3d(b.maxX, b.minY, b.maxZ); GL11.glVertex3d(b.minX, b.minY, b.maxZ);
        GL11.glVertex3d(b.minX, b.minY, b.maxZ); GL11.glVertex3d(b.minX, b.minY, b.minZ);
        GL11.glVertex3d(b.minX, b.maxY, b.minZ); GL11.glVertex3d(b.maxX, b.maxY, b.minZ);
        GL11.glVertex3d(b.maxX, b.maxY, b.minZ); GL11.glVertex3d(b.maxX, b.maxY, b.maxZ);
        GL11.glVertex3d(b.maxX, b.maxY, b.maxZ); GL11.glVertex3d(b.minX, b.maxY, b.maxZ);
        GL11.glVertex3d(b.minX, b.maxY, b.maxZ); GL11.glVertex3d(b.minX, b.maxY, b.minZ);
        GL11.glVertex3d(b.minX, b.minY, b.minZ); GL11.glVertex3d(b.minX, b.maxY, b.minZ);
        GL11.glVertex3d(b.maxX, b.minY, b.minZ); GL11.glVertex3d(b.maxX, b.maxY, b.minZ);
        GL11.glVertex3d(b.maxX, b.minY, b.maxZ); GL11.glVertex3d(b.maxX, b.maxY, b.maxZ);
        GL11.glVertex3d(b.minX, b.minY, b.maxZ); GL11.glVertex3d(b.minX, b.maxY, b.maxZ);
        GL11.glEnd();
        GlStateManager.resetColor();
    }

    // ──────────────────────────────────────────────
    //  Math helpers
    // ──────────────────────────────────────────────

    private static double sq(double v) { return v * v; }
    private static long sqL(long v) { return v * v; }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (Math.min(v, max));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString() + " " + anticheat.getModeString()};
    }
}