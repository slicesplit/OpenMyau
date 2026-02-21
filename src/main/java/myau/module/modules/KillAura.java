package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventManager;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.management.RotationState;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.*;
import myau.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.DataWatcher.WatchableObject;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntitySilverfish;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntitySquid;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.util.*;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.WorldSettings.GameType;

import java.awt.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Random;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class KillAura extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final DecimalFormat df = new DecimalFormat("+0.0;-0.0", new DecimalFormatSymbols(Locale.US));
    private final TimerUtil timer = new TimerUtil();
    public AttackData target = null;
    private int switchTick = 0;
    private boolean hitRegistered = false;
    private boolean blockingState = false;
    private boolean isBlocking = false;
    private boolean fakeBlockState = false;
    private boolean blinkReset = false;
    private long attackDelayMS = 0L;
    private int blockTick = 0;
    private int lastTickProcessed;
    private final ArrayList<AttackData> multiTargets = new ArrayList<>();
    private int multiTargetIndex = 0;
    private long lastMultiAttackTime = 0L;
    private final long[] targetAttackTimes = new long[10];
    private int attacksThisSecond = 0;
    private long lastSecondReset = 0L;

    // BACKTRACK INTEGRATION
    private final Map<Integer, Long> lastTargetHitTime = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> targetHitCount = new ConcurrentHashMap<>();

    // SWITCH MODE
    private long lastSwitchTime = 0L;
    private int currentSwitchTarget = 0;
    private final Map<Integer, Long> targetLastAttack = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> targetComboCount = new ConcurrentHashMap<>();
    private boolean switchCooldown = false;
    private long switchCooldownEnd = 0L;

    // COMBO PREDICTION ENGINE
    private final ComboPredictionEngine comboPrediction = new ComboPredictionEngine();

    // ═══════════════════════════════════════════════════════════════
    //  PREDAC AUTOBLOCK STATE — ENHANCED FOR MULTI-TARGET
    //
    //  The core bypass principle against prediction anticheats:
    //
    //  Prediction ACs (like Grim, Polar, Vulcan, etc.) validate
    //  the relationship between blocking state and attack packets.
    //  They track:
    //    1. C08 (block) → server marks "isBlocking = true"
    //    2. C07 RELEASE → server marks "isBlocking = false"
    //    3. C02 (attack) → must NOT be sent while isBlocking
    //    4. S32 transactions bracket each action for ordering
    //
    //  Our enhanced bypass:
    //    Tick N:   [C07 unblock] → server: isBlocking=false
    //    Tick N:   [C02 attack target1] → valid, not blocking
    //    Tick N:   [C02 attack target2] → valid, not blocking
    //    Tick N:   [C02 attack target3] → valid, not blocking
    //    Tick N:   [C02 attack target4] → valid, not blocking
    //    Tick N+1: [C08 reblock] → server: isBlocking=true
    //
    //  Multiple attacks in ONE unblock window are legitimate because
    //  vanilla allows click-spam while the server processes unblocked
    //  state. The 1-tick gap is normal vanilla right-click release.
    //
    //  Sprint manipulation adds knockback:
    //    Before attack: ensure sprinting for S12 velocity bonus
    //    After attack: micro-sprint-reset for re-sprint KB stacking
    // ═══════════════════════════════════════════════════════════════

    private enum PredACState {
        IDLE,
        BLOCKED,
        PRE_UNBLOCK,    // NEW: Tick before unblock — prep sprint state
        UNBLOCKED,
        ATTACKING,      // NEW: Actively sending attacks in window
        POST_ATTACK,    // NEW: Attacks sent, preparing to reblock
        REBLOCKING
    }

    private PredACState predACState = PredACState.IDLE;
    private int predACTickCounter = 0;
    private boolean predACServerBlocking = false;
    private int predACAttacksInWindow = 0;
    private long predACLastUnblockTime = 0L;
    private int predACConsecutiveBlocks = 0;      // Track consecutive block ticks for randomization
    private boolean predACSprintResetPending = false;
    private int predACTicksSinceLastAttack = 0;
    private final ArrayList<AttackData> predACAttackQueue = new ArrayList<>(); // Multi-target queue

    // SPRINT MANIPULATION STATE
    private boolean sprintManipActive = false;
    private int sprintResetTick = 0;
    private boolean wasSprintingBeforeAttack = false;

    // TRANSACTION TRACKING — for AC order validation
    private int lastConfirmedTransaction = 0;
    private int pendingTransactions = 0;
    private boolean transactionSafe = true;

    // PER-TARGET COMBO TRACKING (for PredAC multi-target)
    private final Map<Integer, Integer> predACTargetHits = new ConcurrentHashMap<>();
    private final Map<Integer, Long> predACTargetLastHit = new ConcurrentHashMap<>();
    private final Map<Integer, Float> predACTargetLastHealth = new ConcurrentHashMap<>();

    // HURT TIME EXPLOITATION
    private final Map<Integer, Integer> targetHurtTimeTracker = new ConcurrentHashMap<>();
    private final Map<Integer, Long> targetVulnerableWindow = new ConcurrentHashMap<>();

    public final ModeProperty mode;
    public final ModeProperty sort;
    public final ModeProperty autoBlock;
    public final BooleanProperty autoBlockRequirePress;
    public final FloatProperty autoBlockMinCPS;
    public final FloatProperty autoBlockMaxCPS;
    public final FloatProperty autoBlockRange;
    public final FloatProperty swingRange;
    public final FloatProperty attackRange;
    public final IntProperty fov;
    public final IntProperty minCPS;
    public final IntProperty maxCPS;
    public final IntProperty switchDelay;
    public final ModeProperty rotations;
    public final ModeProperty moveFix;
    public final PercentProperty smoothing;
    public final IntProperty angleStep;
    public final BooleanProperty throughWalls;
    public final BooleanProperty requirePress;
    public final BooleanProperty allowMining;
    public final BooleanProperty weaponsOnly;
    public final BooleanProperty allowTools;
    public final BooleanProperty inventoryCheck;
    public final BooleanProperty botCheck;
    public final BooleanProperty players;
    public final BooleanProperty bosses;
    public final BooleanProperty mobs;
    public final BooleanProperty animals;
    public final BooleanProperty golems;
    public final BooleanProperty silverfish;
    public final BooleanProperty teams;
    public final ModeProperty showTarget;
    public final ModeProperty debugLog;
    // NEW SETTINGS
    public final BooleanProperty sprintReset;
    public final BooleanProperty hurtTimeExploit;
    public final IntProperty predACMaxTargets;

    private long getAttackDelay() {
        if (this.mode.getValue() == 3) {
            return 50L;
        }
        if (this.mode.getValue() == 2) {
            return 50L;
        }
        if (this.mode.getValue() == 1) {
            Module oldBacktrack = Myau.moduleManager.modules.get(OldBacktrack.class);
            Module newBacktrack = Myau.moduleManager.modules.get(NewBacktrack.class);
            boolean backtrackActive = (oldBacktrack != null && oldBacktrack.isEnabled()) ||
                    (newBacktrack != null && newBacktrack.isEnabled());
            if (backtrackActive) {
                return this.isBlocking ?
                        (long) (1000.0F / RandomUtil.nextLong(
                                this.autoBlockMinCPS.getValue().longValue(),
                                this.autoBlockMaxCPS.getValue().longValue())) :
                        RandomUtil.nextLong(50L, 55L);
            }
        }

        // PredAC mode uses tighter timing for maximum aggression
        if (this.autoBlock.getValue() == 9) {
            long baseDelay = this.isBlocking ?
                    (long) (1000.0F / RandomUtil.nextLong(
                            this.autoBlockMinCPS.getValue().longValue(),
                            this.autoBlockMaxCPS.getValue().longValue())) :
                    1000L / RandomUtil.nextLong(this.minCPS.getValue(), this.maxCPS.getValue());
            // Tighten timing when combo is active (attacking multiple targets)
            if (!predACAttackQueue.isEmpty() && predACAttackQueue.size() > 1) {
                baseDelay = Math.max(50L, baseDelay - 15L);
            }
            return baseDelay;
        }

        return this.isBlocking ?
                (long) (1000.0F / RandomUtil.nextLong(
                        this.autoBlockMinCPS.getValue().longValue(),
                        this.autoBlockMaxCPS.getValue().longValue())) :
                1000L / RandomUtil.nextLong(this.minCPS.getValue(), this.maxCPS.getValue());
    }

    private boolean performAttack(float yaw, float pitch) {
        if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
            if (this.target != null) {
                double distance = mc.thePlayer.getDistanceToEntity(this.target.getEntity());
                if (distance > 3.0) {
                    return false;
                }
            }

            // PredAC handles blocking internally — allow attacks in all states
            if (this.autoBlock.getValue() != 9) {
                if (this.isPlayerBlocking() && this.autoBlock.getValue() != 1) {
                    return false;
                }
            }

            if (this.attackDelayMS > 0L && this.mode.getValue() != 2
                    && this.mode.getValue() != 3 && this.autoBlock.getValue() != 9) {
                return false;
            }

            double targetDistance = RotationUtil.distanceToEntity(this.target.getEntity());
            double maxReach = 2.90;

            if (targetDistance > maxReach) {
                return false;
            }

            if (this.mode.getValue() == 2 || this.mode.getValue() == 3) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastSecondReset > 1000) {
                    attacksThisSecond = 0;
                    lastSecondReset = currentTime;
                }
                if (attacksThisSecond >= 20) {
                    return false;
                }
                if (currentTime - lastMultiAttackTime < 50) {
                    return false;
                }
                attacksThisSecond++;
                lastMultiAttackTime = currentTime;
            } else if (this.autoBlock.getValue() != 9) {
                // Non-PredAC modes use normal delay
                long delay = this.getAttackDelay();
                this.attackDelayMS = this.attackDelayMS + delay;
            }

            // ═══════════════════════════════════════════════════
            //  SPRINT MANIPULATION — Extra knockback per hit
            //  Ensure we're sprinting when the attack lands for
            //  maximum knockback. ACs allow sprint+attack as it's
            //  vanilla behavior (S_sprint → attack → knockback).
            // ═══════════════════════════════════════════════════
            if (this.sprintReset.getValue() && this.autoBlock.getValue() == 9) {
                performSprintManipulation();
            }

            mc.thePlayer.swingItem();
            if ((this.rotations.getValue() != 0 || !this.isBoxInAttackRange(this.target.getBox()))
                    && RotationUtil.rayTrace(this.target.getBox(), yaw, pitch, this.attackRange.getValue()) == null) {
                return false;
            }

            AttackEvent event = new AttackEvent(this.target.getEntity());
            EventManager.call(event);
            ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
            PacketUtil.sendPacket(new C02PacketUseEntity(this.target.getEntity(), Action.ATTACK));
            if (mc.playerController.getCurrentGameType() != GameType.SPECTATOR) {
                PlayerUtil.attackEntity(this.target.getEntity());
            }

            // Track hits per target for combo optimization
            int entityId = this.target.getEntity().getEntityId();
            predACTargetHits.merge(entityId, 1, Integer::sum);
            predACTargetLastHit.put(entityId, System.currentTimeMillis());
            predACTargetLastHealth.put(entityId, this.target.getEntity().getHealth());

            this.hitRegistered = true;
            return true;
        } else {
            return false;
        }
    }

    /**
     * Sprint manipulation for extra knockback.
     * The server calculates knockback based on sprint state AT THE TIME
     * of the attack packet. We ensure sprinting is active.
     *
     * For consecutive attacks (multi-target), we do micro sprint resets:
     *   [C0B stop sprint] → [C0B start sprint] → [C02 attack]
     * This resets the sprint flag so each hit gets full sprint knockback.
     */
    private void performSprintManipulation() {
        if (mc.thePlayer.isSprinting()) {
            if (predACAttacksInWindow > 0) {
                // Micro sprint reset between multi-target attacks
                // Each target gets full sprint knockback
                PacketUtil.sendPacket(new C0BPacketEntityAction(
                        mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
                PacketUtil.sendPacket(new C0BPacketEntityAction(
                        mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
            }
        } else {
            // Not sprinting — start sprint for KB bonus
            if (mc.thePlayer.moveForward > 0) {
                PacketUtil.sendPacket(new C0BPacketEntityAction(
                        mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
                mc.thePlayer.setSprinting(true);
                sprintManipActive = true;
            }
        }
    }

    private void sendUseItem() {
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        this.startBlock(mc.thePlayer.getHeldItem());
    }

    private void startBlock(ItemStack itemStack) {
        PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(itemStack));
        mc.thePlayer.setItemInUse(itemStack, itemStack.getMaxItemUseDuration());
        this.blockingState = true;
    }

    private void stopBlock() {
        PacketUtil.sendPacket(new C07PacketPlayerDigging(
                C07PacketPlayerDigging.Action.RELEASE_USE_ITEM,
                BlockPos.ORIGIN, EnumFacing.DOWN));
        mc.thePlayer.stopUsingItem();
        this.blockingState = false;
    }

    private void interactAttack(float yaw, float pitch) {
        if (this.target != null) {
            MovingObjectPosition mop = RotationUtil.rayTrace(
                    this.target.getBox(), yaw, pitch, 8.0);
            if (mop != null) {
                ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
                PacketUtil.sendPacket(
                        new C02PacketUseEntity(
                                this.target.getEntity(),
                                new Vec3(
                                        mop.hitVec.xCoord - this.target.getX(),
                                        mop.hitVec.yCoord - this.target.getY(),
                                        mop.hitVec.zCoord - this.target.getZ()
                                )
                        )
                );
                PacketUtil.sendPacket(new C02PacketUseEntity(this.target.getEntity(), Action.INTERACT));
                PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
                mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(),
                        mc.thePlayer.getHeldItem().getMaxItemUseDuration());
                this.blockingState = true;
            }
        }
    }

    private boolean canAttack() {
        if (this.inventoryCheck.getValue() && mc.currentScreen instanceof GuiContainer) {
            return false;
        } else if (!(Boolean) this.weaponsOnly.getValue()
                || ItemUtil.hasRawUnbreakingEnchant()
                || this.allowTools.getValue() && ItemUtil.isHoldingTool()) {
            if (((IAccessorPlayerControllerMP) mc.playerController).getIsHittingBlock()) {
                return false;
            } else if ((ItemUtil.isEating() || ItemUtil.isUsingBow()) && PlayerUtil.isUsingItem()) {
                return false;
            } else {
                AutoHeal autoHeal = (AutoHeal) Myau.moduleManager.modules.get(AutoHeal.class);
                if (autoHeal.isEnabled() && autoHeal.isSwitching()) {
                    return false;
                } else {
                    BedNuker bedNuker = (BedNuker) Myau.moduleManager.modules.get(BedNuker.class);
                    if (bedNuker.isEnabled() && bedNuker.isReady()) {
                        return false;
                    } else if (Myau.moduleManager.modules.get(Scaffold.class).isEnabled()) {
                        return false;
                    } else if (this.requirePress.getValue()) {
                        return PlayerUtil.isAttacking();
                    } else {
                        return !this.allowMining.getValue()
                                || !mc.objectMouseOver.typeOfHit.equals(MovingObjectType.BLOCK)
                                || !PlayerUtil.isAttacking();
                    }
                }
            }
        } else {
            return false;
        }
    }

    private boolean canAutoBlock() {
        if (!ItemUtil.isHoldingSword()) {
            return false;
        } else {
            return !this.autoBlockRequirePress.getValue() || PlayerUtil.isUsingItem();
        }
    }

    private boolean hasValidTarget() {
        return mc.theWorld
                .loadedEntityList
                .stream()
                .anyMatch(
                        entity -> entity instanceof EntityLivingBase
                                && this.isValidTarget((EntityLivingBase) entity)
                                && this.isInBlockRange((EntityLivingBase) entity)
                );
    }

    private boolean isValidTarget(EntityLivingBase entityLivingBase) {
        if (!mc.theWorld.loadedEntityList.contains(entityLivingBase)) {
            return false;
        } else if (entityLivingBase != mc.thePlayer
                && entityLivingBase != mc.thePlayer.ridingEntity) {
            if (entityLivingBase == mc.getRenderViewEntity()
                    || entityLivingBase == mc.getRenderViewEntity().ridingEntity) {
                return false;
            } else if (entityLivingBase.deathTime > 0) {
                return false;
            } else if (RotationUtil.angleToEntity(entityLivingBase) > this.fov.getValue().floatValue()) {
                return false;
            } else if (!this.throughWalls.getValue() && RotationUtil.rayTrace(entityLivingBase) != null) {
                return false;
            } else if (entityLivingBase instanceof EntityOtherPlayerMP) {
                if (!this.players.getValue()) {
                    return false;
                } else if (TeamUtil.isFriend((EntityPlayer) entityLivingBase)) {
                    return false;
                } else {
                    return (!this.teams.getValue()
                            || !TeamUtil.isSameTeam((EntityPlayer) entityLivingBase))
                            && (!this.botCheck.getValue()
                            || !TeamUtil.isBot((EntityPlayer) entityLivingBase));
                }
            } else if (entityLivingBase instanceof EntityDragon
                    || entityLivingBase instanceof EntityWither) {
                return this.bosses.getValue();
            } else if (!(entityLivingBase instanceof EntityMob)
                    && !(entityLivingBase instanceof EntitySlime)) {
                if (entityLivingBase instanceof EntityAnimal
                        || entityLivingBase instanceof EntityBat
                        || entityLivingBase instanceof EntitySquid
                        || entityLivingBase instanceof EntityVillager) {
                    return this.animals.getValue();
                } else if (!(entityLivingBase instanceof EntityIronGolem)) {
                    return false;
                } else {
                    return this.golems.getValue()
                            && (!this.teams.getValue()
                            || !TeamUtil.hasTeamColor(entityLivingBase));
                }
            } else if (!(entityLivingBase instanceof EntitySilverfish)) {
                return this.mobs.getValue();
            } else {
                return this.silverfish.getValue()
                        && (!this.teams.getValue()
                        || !TeamUtil.hasTeamColor(entityLivingBase));
            }
        } else {
            return false;
        }
    }

    private boolean isInRange(EntityLivingBase entityLivingBase) {
        return this.isInBlockRange(entityLivingBase)
                || this.isInSwingRange(entityLivingBase)
                || this.isInAttackRange(entityLivingBase);
    }

    private boolean isInBlockRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.autoBlockRange.getValue();
    }

    private boolean isInSwingRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.swingRange.getValue();
    }

    private boolean isBoxInSwingRange(AxisAlignedBB axisAlignedBB) {
        return RotationUtil.distanceToBox(axisAlignedBB) <= (double) this.swingRange.getValue();
    }

    private boolean isInAttackRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.attackRange.getValue();
    }

    private boolean isBoxInAttackRange(AxisAlignedBB axisAlignedBB) {
        return RotationUtil.distanceToBox(axisAlignedBB) <= (double) this.attackRange.getValue();
    }

    private boolean isPlayerTarget(EntityLivingBase entityLivingBase) {
        return entityLivingBase instanceof EntityPlayer
                && TeamUtil.isTarget((EntityPlayer) entityLivingBase);
    }

    private boolean willKnockIntoVoid(EntityLivingBase target) {
        if (target == null || !(target instanceof EntityPlayer)) {
            return false;
        }
        double targetX = target.posX;
        double targetY = target.posY;
        double targetZ = target.posZ;
        if (targetY > 10.0) {
            return false;
        }
        Vec3 playerPos = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        Vec3 targetPos = new Vec3(targetX, targetY, targetZ);
        Vec3 knockbackDirection = targetPos.subtract(playerPos).normalize();
        double knockbackStrength = 0.4;
        if (mc.thePlayer.isSprinting()) {
            knockbackStrength += 0.5;
        }
        ItemStack heldItem = mc.thePlayer.getHeldItem();
        if (heldItem != null && heldItem.getItem() instanceof ItemSword) {
            int knockbackLevel = net.minecraft.enchantment.EnchantmentHelper
                    .getKnockbackModifier(mc.thePlayer);
            knockbackStrength += knockbackLevel * 0.4;
        }
        double predictedX = targetX + knockbackDirection.xCoord * knockbackStrength;
        double predictedZ = targetZ + knockbackDirection.zCoord * knockbackStrength;
        for (int checkDist = 0; checkDist <= 3; checkDist++) {
            double checkX = predictedX + knockbackDirection.xCoord * checkDist;
            double checkZ = predictedZ + knockbackDirection.zCoord * checkDist;
            if (!hasGroundBelow(checkX, targetY, checkZ, 5)) {
                for (int safetyCheck = 0; safetyCheck < 2; safetyCheck++) {
                    double safeX = checkX + (Math.random() - 0.5) * 2.0;
                    double safeZ = checkZ + (Math.random() - 0.5) * 2.0;
                    if (hasGroundBelow(safeX, targetY, safeZ, 5)) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    private boolean hasGroundBelow(double x, double y, double z, int maxDistance) {
        BlockPos startPos = new BlockPos(x, y, z);
        for (int i = 0; i < maxDistance; i++) {
            BlockPos checkPos = startPos.down(i);
            if (checkPos.getY() < 0) {
                return false;
            }
            net.minecraft.block.Block block = mc.theWorld.getBlockState(checkPos).getBlock();
            if (block != null
                    && block.getMaterial() != net.minecraft.block.material.Material.air) {
                if (block.isFullBlock() || block.getMaterial().isSolid()) {
                    return true;
                }
            }
        }
        return false;
    }

    private int findEmptySlot(int currentSlot) {
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot && mc.thePlayer.inventory.getStackInSlot(i) == null) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot) {
                ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
                if (stack != null && !stack.hasDisplayName()) {
                    return i;
                }
            }
        }
        return Math.floorMod(currentSlot - 1, 9);
    }

    private int findSwordSlot(int currentSlot) {
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot) {
                ItemStack item = mc.thePlayer.inventory.getStackInSlot(i);
                if (item != null && item.getItem() instanceof ItemSword) {
                    return i;
                }
            }
        }
        return -1;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ENHANCED PREDAC AUTOBLOCK — MULTI-TARGET COMBO MACHINE
    //
    //  Architecture:
    //
    //  ┌─────────────────────────────────────────────────────────┐
    //  │  IDLE → target acquired                                │
    //  │    ↓                                                    │
    //  │  BLOCKED → C08 sent, server sees us blocking           │
    //  │    ↓ (attack delay expired, targets available)          │
    //  │  PRE_UNBLOCK → prepare sprint state, build attack queue │
    //  │    ↓                                                    │
    //  │  UNBLOCKED → C07 sent, server sees us NOT blocking     │
    //  │    ↓                                                    │
    //  │  ATTACKING → C02 sent for each target in queue         │
    //  │    │  ├─ target1: sprint-reset → C02 → swing           │
    //  │    │  ├─ target2: sprint-reset → C02 → swing           │
    //  │    │  ├─ target3: sprint-reset → C02 → swing           │
    //  │    │  └─ target4: sprint-reset → C02 → swing           │
    //  │    ↓                                                    │
    //  │  POST_ATTACK → all attacks sent, cleanup sprint state   │
    //  │    ↓                                                    │
    //  │  REBLOCKING → C08 sent, back to blocking               │
    //  │    ↓                                                    │
    //  │  BLOCKED → cycle repeats                               │
    //  └─────────────────────────────────────────────────────────┘
    //
    //  Multi-target attack ordering:
    //  1. Sort targets by hurtTime (attack hurtTime==0 first)
    //  2. Then by health (lowest first for kill pressure)
    //  3. Then by distance (closest first for reliability)
    //
    //  Each target gets sprint-reset between attacks for full KB.
    //  All attacks happen in the SAME unblock window (1 tick).
    //  This is indistinguishable from a player spam-clicking while
    //  releasing right-click for 1 tick.
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Enhanced PredAC tick handler — manages the complete
     * block/unblock/multi-attack/reblock cycle.
     */
    private void predACTick(UpdateEvent event, boolean canAttackNow) {
        predACTickCounter++;
        predACTicksSinceLastAttack++;

        boolean hasTarget = this.target != null && this.isValidTarget(this.target.getEntity());
        boolean inBlockRange = hasTarget && this.isInBlockRange(this.target.getEntity());
        boolean holdingSword = ItemUtil.isHoldingSword();

        if (!holdingSword || !hasTarget) {
            predACCleanup();
            return;
        }

        if (!inBlockRange) {
            if (predACServerBlocking) {
                predACSendUnblock();
            }
            predACState = PredACState.IDLE;
            this.isBlocking = false;
            this.fakeBlockState = false;
            return;
        }

        // Always show block animation to client
        this.fakeBlockState = true;

        switch (predACState) {
            case IDLE:
                // Enter blocking state
                predACSendBlock();
                predACState = PredACState.BLOCKED;
                predACTickCounter = 0;
                predACConsecutiveBlocks = 0;
                this.isBlocking = true;
                break;

            case BLOCKED:
                predACConsecutiveBlocks++;
                this.isBlocking = true;

                // Check if we should attack
                if (canAttackNow && this.attackDelayMS <= 0L) {
                    // Build multi-target attack queue
                    buildPredACAttackQueue();

                    if (!predACAttackQueue.isEmpty()) {
                        // Transition to pre-unblock
                        predACState = PredACState.PRE_UNBLOCK;
                        predACTickCounter = 0;
                    }
                }
                break;

            case PRE_UNBLOCK:
                // Prepare sprint state for maximum knockback
                wasSprintingBeforeAttack = mc.thePlayer.isSprinting();
                if (this.sprintReset.getValue() && mc.thePlayer.moveForward > 0) {
                    if (!mc.thePlayer.isSprinting()) {
                        PacketUtil.sendPacket(new C0BPacketEntityAction(
                                mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
                        mc.thePlayer.setSprinting(true);
                    }
                }

                // Unblock — opens the attack window
                predACSendUnblock();
                predACState = PredACState.UNBLOCKED;
                predACTickCounter = 0;
                predACAttacksInWindow = 0;
                predACLastUnblockTime = System.currentTimeMillis();
                break;

            case UNBLOCKED:
                // Attack window is OPEN — fire all queued attacks
                predACState = PredACState.ATTACKING;
                predACTickCounter = 0;
                // Fall through to ATTACKING immediately
                predACExecuteAttackQueue(event);
                break;

            case ATTACKING:
                // Continue attack queue if not finished
                // (normally done in single tick, but safety fallback)
                if (predACAttacksInWindow < predACAttackQueue.size()) {
                    predACExecuteAttackQueue(event);
                } else {
                    predACState = PredACState.POST_ATTACK;
                    predACTickCounter = 0;
                }
                break;

            case POST_ATTACK:
                // All attacks sent — reblock immediately
                predACState = PredACState.REBLOCKING;
                predACTickCounter = 0;

                // Set attack delay based on CPS settings
                long delay = this.getAttackDelay();
                this.attackDelayMS = this.attackDelayMS + delay;

                // Randomize slightly to avoid pattern detection
                if (predACConsecutiveBlocks > 3) {
                    this.attackDelayMS += RandomUtil.nextLong(0L, 15L);
                }

                // Fall through to reblock
                predACSendBlock();
                predACState = PredACState.BLOCKED;
                predACTickCounter = 0;
                predACTicksSinceLastAttack = 0;
                predACAttackQueue.clear();
                break;

            case REBLOCKING:
                // Safety — should not stay here
                predACSendBlock();
                predACState = PredACState.BLOCKED;
                predACTickCounter = 0;
                break;
        }
    }

    /**
     * Builds the multi-target attack queue for this attack cycle.
     * Targets are sorted by vulnerability (hurtTime), health, and distance
     * to maximize damage output and combo potential.
     */
    private void buildPredACAttackQueue() {
        predACAttackQueue.clear();
        int maxTargets = this.predACMaxTargets.getValue();

        ArrayList<AttackData> candidates = new ArrayList<>();

        // Collect all valid targets in attack range
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (entity instanceof EntityLivingBase) {
                EntityLivingBase living = (EntityLivingBase) entity;
                if (this.isValidTarget(living) && this.isInAttackRange(living)) {
                    candidates.add(new AttackData(living));
                }
            }
        }

        if (candidates.isEmpty()) return;

        // ═══════════════════════════════════════════════════
        //  HURT TIME EXPLOITATION
        //
        //  Minecraft has a 0.5 second (10 tick) invulnerability
        //  window after each hit. Attacking during this window
        //  deals 0 damage. We prioritize targets with hurtTime == 0
        //  (vulnerable) over those still in their invuln window.
        //
        //  For multi-target, this means we cycle through targets
        //  efficiently — never wasting attacks on invulnerable ones.
        // ═══════════════════════════════════════════════════
        candidates.sort((a, b) -> {
            EntityLivingBase ea = a.getEntity();
            EntityLivingBase eb = b.getEntity();

            // Priority 1: hurtTime == 0 (can take damage NOW)
            boolean aVulnerable = ea.hurtTime == 0;
            boolean bVulnerable = eb.hurtTime == 0;
            if (aVulnerable != bVulnerable) {
                return aVulnerable ? -1 : 1;
            }

            // Priority 2: Lower hurtTime = closer to being vulnerable again
            if (ea.hurtTime != eb.hurtTime) {
                return Integer.compare(ea.hurtTime, eb.hurtTime);
            }

            // Priority 3: Lower health = easier to kill
            float ha = ea.getHealth() / ea.getMaxHealth();
            float hb = eb.getHealth() / eb.getMaxHealth();
            if (Math.abs(ha - hb) > 0.1f) {
                return Float.compare(ha, hb);
            }

            // Priority 4: Targets we've been comboing (maintain combo)
            int hitsA = predACTargetHits.getOrDefault(ea.getEntityId(), 0);
            int hitsB = predACTargetHits.getOrDefault(eb.getEntityId(), 0);
            if (hitsA != hitsB) {
                return Integer.compare(hitsB, hitsA); // More hits = higher priority
            }

            // Priority 5: Closest first for reliability
            return Double.compare(
                    RotationUtil.distanceToEntity(ea),
                    RotationUtil.distanceToEntity(eb)
            );
        });

        // Take top N targets
        int count = Math.min(maxTargets, candidates.size());
        for (int i = 0; i < count; i++) {
            AttackData candidate = candidates.get(i);
            // Only queue if target can actually take damage
            // (skip if in invuln window AND we have better targets)
            if (this.hurtTimeExploit.getValue()) {
                if (candidate.getEntity().hurtTime > 0 && i > 0) {
                    // Skip invulnerable targets if we already have
                    // at least one vulnerable target queued
                    boolean hasVulnerable = predACAttackQueue.stream()
                            .anyMatch(ad -> ad.getEntity().hurtTime == 0);
                    if (hasVulnerable && candidate.getEntity().hurtTime > 3) {
                        continue;
                    }
                }
            }
            predACAttackQueue.add(candidate);
        }

        // Always ensure primary target is in queue
        if (this.target != null && this.isValidTarget(this.target.getEntity())
                && this.isInAttackRange(this.target.getEntity())) {
            boolean primaryInQueue = predACAttackQueue.stream()
                    .anyMatch(ad -> ad.getEntity().getEntityId()
                            == this.target.getEntity().getEntityId());
            if (!primaryInQueue && predACAttackQueue.size() < maxTargets) {
                predACAttackQueue.add(0, this.target);
            }
        }
    }

    /**
     * Executes attacks against all targets in the attack queue.
     * Each attack gets:
     *   1. Rotation calculation to the target
     *   2. Sprint reset for full knockback
     *   3. C02 attack packet
     *   4. Swing animation
     *
     * All attacks happen in the SAME TICK during the unblock window.
     * This is legitimate because vanilla allows multiple left-clicks
     * per tick (fast clicking / debounce).
     */
    private void predACExecuteAttackQueue(UpdateEvent event) {
        if (predACAttackQueue.isEmpty()) {
            predACState = PredACState.POST_ATTACK;
            return;
        }

        AttackData originalTarget = this.target;
        boolean anyAttacked = false;

        for (int i = predACAttacksInWindow; i < predACAttackQueue.size(); i++) {
            AttackData queuedTarget = predACAttackQueue.get(i);

            // Validate target is still alive and in range
            if (!this.isValidTarget(queuedTarget.getEntity())) continue;
            if (!this.isBoxInAttackRange(queuedTarget.getBox())) continue;

            // Calculate rotations for this target
            float[] rots = RotationUtil.getRotationsToBox(
                    queuedTarget.getBox(),
                    event.getYaw(),
                    event.getPitch(),
                    180.0F, // Max angle step — instant snap for multi-target
                    0.0F    // No smoothing — immediate aim
            );

            // Set rotation for the first target (the one the AC validates)
            if (i == 0) {
                if (this.rotations.getValue() == 2 || this.rotations.getValue() == 3) {
                    event.setRotation(rots[0], rots[1], 1);
                    if (this.rotations.getValue() == 3) {
                        Myau.rotationManager.setRotation(rots[0], rots[1], 1, true);
                    }
                    if (this.moveFix.getValue() != 0 || this.rotations.getValue() == 3) {
                        event.setPervRotation(rots[0], 1);
                    }
                }
            }

            // Sprint manipulation between targets
            if (this.sprintReset.getValue() && predACAttacksInWindow > 0
                    && mc.thePlayer.isSprinting()) {
                // Micro sprint reset — gives full KB to each target
                PacketUtil.sendPacket(new C0BPacketEntityAction(
                        mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
                PacketUtil.sendPacket(new C0BPacketEntityAction(
                        mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
            }

            // Set this target as active and perform attack
            this.target = queuedTarget;
            if (this.performAttack(rots[0], rots[1])) {
                predACAttacksInWindow++;
                anyAttacked = true;

                // Track hurt time for this target
                targetHurtTimeTracker.put(
                        queuedTarget.getEntity().getEntityId(),
                        queuedTarget.getEntity().hurtTime
                );
            }
        }

        // Restore original target
        this.target = originalTarget;

        // Transition to post-attack
        if (anyAttacked || predACAttacksInWindow >= predACAttackQueue.size()) {
            predACState = PredACState.POST_ATTACK;
            predACTickCounter = 0;
        }
    }

    /**
     * Sends block packet (C08) — server sees us blocking
     */
    private void predACSendBlock() {
        if (!predACServerBlocking) {
            ItemStack sword = mc.thePlayer.getHeldItem();
            if (sword != null && sword.getItem() instanceof ItemSword) {
                PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(sword));
                mc.thePlayer.setItemInUse(sword, sword.getMaxItemUseDuration());
                this.blockingState = true;
                predACServerBlocking = true;
            }
        }
    }

    /**
     * Sends unblock packet (C07 RELEASE_USE_ITEM) — server sees us NOT blocking
     */
    private void predACSendUnblock() {
        if (predACServerBlocking) {
            PacketUtil.sendPacket(new C07PacketPlayerDigging(
                    C07PacketPlayerDigging.Action.RELEASE_USE_ITEM,
                    BlockPos.ORIGIN,
                    EnumFacing.DOWN
            ));
            mc.thePlayer.stopUsingItem();
            this.blockingState = false;
            predACServerBlocking = false;
        }
    }

    /**
     * Cleans up all PredAC state
     */
    private void predACCleanup() {
        if (predACServerBlocking) {
            predACSendUnblock();
        }
        predACState = PredACState.IDLE;
        predACTickCounter = 0;
        predACAttacksInWindow = 0;
        predACServerBlocking = false;
        predACConsecutiveBlocks = 0;
        predACSprintResetPending = false;
        predACTicksSinceLastAttack = 0;
        predACAttackQueue.clear();
        this.isBlocking = false;
        this.fakeBlockState = false;
        this.blockingState = false;

        // Restore sprint state if we manipulated it
        if (sprintManipActive) {
            sprintManipActive = false;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ENHANCED SWITCH MODE — PREDICTION AC BYPASS
    //
    //  Switch mode cycles through targets intelligently to
    //  maintain combos on multiple players simultaneously.
    //
    //  Anti-cheat bypass principles for switch:
    //  1. Only attack when our rotation matches the target
    //  2. Respect hurt time windows — don't waste hits
    //  3. Smooth rotation transitions between targets
    //  4. Consistent timing patterns that match vanilla
    //
    //  Combo strategy:
    //  - Hit target A 2-3 times → switch to B when A has hurtTime
    //  - Hit target B 2-3 times → switch to C when B has hurtTime
    //  - By the time we cycle back to A, their hurtTime is 0 again
    //  - This maximizes DPS across all targets
    // ═══════════════════════════════════════════════════════════

    /**
     * Enhanced switch target selection — considers hurt time windows
     * and combo potential for prediction AC bypass.
     */
    private EntityLivingBase selectSwitchTarget(ArrayList<EntityLivingBase> targets) {
        if (targets.isEmpty()) return null;
        if (targets.size() == 1) return targets.get(0);

        long currentTime = System.currentTimeMillis();

        // Find the best target to attack RIGHT NOW
        EntityLivingBase bestTarget = null;
        double bestScore = Double.MIN_VALUE;

        for (EntityLivingBase candidate : targets) {
            int entityId = candidate.getEntityId();
            double score = 0.0;

            // Massive bonus for vulnerable targets (hurtTime == 0)
            if (candidate.hurtTime == 0) {
                score += 100.0;
            } else {
                // Penalize based on remaining invulnerability
                score -= candidate.hurtTime * 8.0;
            }

            // Bonus for low health targets (kill pressure)
            float healthPercent = candidate.getHealth() / candidate.getMaxHealth();
            score += (1.0 - healthPercent) * 50.0;

            // Bonus for targets we've been comboing (maintain combo)
            int comboHits = targetComboCount.getOrDefault(entityId, 0);
            if (comboHits > 0 && comboHits < 3) {
                score += 30.0; // Keep hitting for combo
            } else if (comboHits >= 3) {
                score -= 20.0; // Time to switch
            }

            // Bonus for closer targets (more reliable hits)
            double dist = RotationUtil.distanceToEntity(candidate);
            score += (3.0 - dist) * 15.0;

            // Penalty for targets we recently attacked (let hurtTime clear)
            Long lastHit = predACTargetLastHit.getOrDefault(entityId, 0L);
            long timeSinceHit = currentTime - lastHit;
            if (timeSinceHit < 500 && candidate.hurtTime > 0) {
                score -= 40.0; // Recently hit AND still invulnerable
            }

            // Bonus for targets with lower FOV angle (less rotation needed)
            float angle = RotationUtil.angleToEntity(candidate);
            score += (180.0 - angle) * 0.5;

            if (score > bestScore) {
                bestScore = score;
                bestTarget = candidate;
            }
        }

        return bestTarget != null ? bestTarget : targets.get(0);
    }

    public KillAura() {
        super("KillAura", false);
        this.lastTickProcessed = 0;
        this.mode = new ModeProperty("mode", 0,
                new String[]{"SINGLE", "SWITCH", "MULTI", "COMBO"});
        this.sort = new ModeProperty("sort", 0,
                new String[]{"DISTANCE", "HEALTH", "HURT_TIME", "FOV"});
        this.autoBlock = new ModeProperty(
                "auto-block", 2,
                new String[]{"NONE", "VANILLA", "SPOOF", "HYPIXEL", "BLINK",
                        "INTERACT", "SWAP", "LEGIT", "FAKE", "PREDAC"}
        );
        this.autoBlockRequirePress = new BooleanProperty("auto-block-require-press", false);
        this.autoBlockMinCPS = new FloatProperty("auto-block-min-aps", 14.0F, 1.0F, 20.0F);
        this.autoBlockMaxCPS = new FloatProperty("auto-block-max-aps", 16.0F, 1.0F, 20.0F);
        this.autoBlockRange = new FloatProperty("auto-block-range", 5.5F, 3.0F, 6.0F);
        this.swingRange = new FloatProperty("swing-range", 3.5F, 3.0F, 6.0F);
        this.attackRange = new FloatProperty("attack-range", 3.0F, 3.0F, 6.0F);
        this.fov = new IntProperty("fov", 360, 30, 360);
        this.minCPS = new IntProperty("min-aps", 14, 1, 20);
        this.maxCPS = new IntProperty("max-aps", 14, 1, 20);
        this.switchDelay = new IntProperty("switch-delay", 8, 0, 1000);
        this.rotations = new ModeProperty("rotations", 2,
                new String[]{"NONE", "LEGIT", "SILENT", "LOCK_VIEW"});
        this.moveFix = new ModeProperty("move-fix", 1,
                new String[]{"NONE", "SILENT", "STRICT"});
        this.smoothing = new PercentProperty("smoothing", 0);
        this.angleStep = new IntProperty("angle-step", 90, 30, 180);
        this.throughWalls = new BooleanProperty("through-walls", true);
        this.requirePress = new BooleanProperty("require-press", false);
        this.allowMining = new BooleanProperty("allow-mining", true);
        this.weaponsOnly = new BooleanProperty("weapons-only", true);
        this.allowTools = new BooleanProperty("allow-tools", false, this.weaponsOnly::getValue);
        this.inventoryCheck = new BooleanProperty("inventory-check", true);
        this.botCheck = new BooleanProperty("bot-check", true);
        this.players = new BooleanProperty("players", true);
        this.bosses = new BooleanProperty("bosses", false);
        this.mobs = new BooleanProperty("mobs", false);
        this.animals = new BooleanProperty("animals", false);
        this.golems = new BooleanProperty("golems", false);
        this.silverfish = new BooleanProperty("silverfish", false);
        this.teams = new BooleanProperty("teams", true);
        this.showTarget = new ModeProperty("show-target", 0,
                new String[]{"NONE", "DEFAULT", "HUD"});
        this.debugLog = new ModeProperty("debug-log", 0,
                new String[]{"NONE", "HEALTH"});
        // NEW SETTINGS for enhanced PredAC
        this.sprintReset = new BooleanProperty("sprint-reset", true);
        this.hurtTimeExploit = new BooleanProperty("hurt-time-exploit", true);
        this.predACMaxTargets = new IntProperty("predac-max-targets", 4, 1, 4);
    }

    public EntityLivingBase getTarget() {
        return this.target != null ? this.target.getEntity() : null;
    }

    public boolean isAttackAllowed() {
        Scaffold scaffold = (Scaffold) Myau.moduleManager.modules.get(Scaffold.class);
        if (scaffold.isEnabled()) {
            return false;
        } else if (!this.weaponsOnly.getValue()
                || ItemUtil.hasRawUnbreakingEnchant()
                || this.allowTools.getValue() && ItemUtil.isHoldingTool()) {
            return !this.requirePress.getValue()
                    || KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode());
        } else {
            return false;
        }
    }

    public boolean shouldAutoBlock() {
        if (this.isPlayerBlocking() && this.isBlocking) {
            return !mc.thePlayer.isInWater() && !mc.thePlayer.isInLava()
                    && (this.autoBlock.getValue() == 3
                    || this.autoBlock.getValue() == 4
                    || this.autoBlock.getValue() == 5
                    || this.autoBlock.getValue() == 6
                    || this.autoBlock.getValue() == 7
                    || this.autoBlock.getValue() == 9);
        } else {
            return false;
        }
    }

    public boolean isBlocking() {
        return this.fakeBlockState && ItemUtil.isHoldingSword();
    }

    public boolean isPlayerBlocking() {
        return (mc.thePlayer.isUsingItem() || this.blockingState) && ItemUtil.isHoldingSword();
    }

    @EventTarget(Priority.LOW)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.POST && this.blinkReset) {
            this.blinkReset = false;
            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
            Myau.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK);
        }
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            AntiFireball antiFireball = (AntiFireball) Myau.moduleManager.modules.get(AntiFireball.class);
            if (antiFireball != null && antiFireball.isDeflecting()) {
                return;
            }

            if (this.attackDelayMS > 0L) {
                this.attackDelayMS -= 50L;
            }
            boolean attack = this.target != null && this.canAttack();
            boolean block = attack && this.canAutoBlock();
            if (!block) {
                if (this.autoBlock.getValue() == 9) {
                    predACCleanup();
                }
                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                this.isBlocking = false;
                this.fakeBlockState = false;
                this.blockTick = 0;
            }
            if (attack) {
                boolean swap = false;
                boolean blocked = false;
                if (block) {
                    switch (this.autoBlock.getValue()) {
                        case 0: // NONE
                            if (PlayerUtil.isUsingItem()) {
                                this.isBlocking = true;
                                if (!this.isPlayerBlocking()
                                        && !Myau.playerStateManager.digging
                                        && !Myau.playerStateManager.placing) {
                                    swap = true;
                                }
                            } else {
                                this.isBlocking = false;
                                if (this.isPlayerBlocking()
                                        && !Myau.playerStateManager.digging
                                        && !Myau.playerStateManager.placing) {
                                    this.stopBlock();
                                }
                            }
                            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                            this.fakeBlockState = false;
                            break;
                        case 1: // VANILLA
                            if (this.hasValidTarget()) {
                                if (!this.isPlayerBlocking()
                                        && !Myau.playerStateManager.digging
                                        && !Myau.playerStateManager.placing) {
                                    swap = true;
                                }
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = true;
                                this.fakeBlockState = false;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 2: // SPOOF
                            if (this.hasValidTarget()) {
                                int item = ((IAccessorPlayerControllerMP) mc.playerController)
                                        .getCurrentPlayerItem();
                                if (Myau.playerStateManager.digging
                                        || Myau.playerStateManager.placing
                                        || mc.thePlayer.inventory.currentItem != item
                                        || this.isPlayerBlocking() && this.blockTick != 0
                                        || this.attackDelayMS > 0L && this.attackDelayMS <= 50L) {
                                    this.blockTick = 0;
                                } else {
                                    int slot = this.findEmptySlot(item);
                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(item));
                                    swap = true;
                                    this.blockTick = 1;
                                }
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = true;
                                this.fakeBlockState = false;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 3: // HYPIXEL
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging
                                        && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            blocked = true;
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                if (Myau.moduleManager.modules.get(NoSlow.class)
                                                        .isEnabled()) {
                                                    int randomSlot = new Random().nextInt(9);
                                                    while (randomSlot
                                                            == mc.thePlayer.inventory.currentItem) {
                                                        randomSlot = new Random().nextInt(9);
                                                    }
                                                    PacketUtil.sendPacket(
                                                            new C09PacketHeldItemChange(randomSlot));
                                                    PacketUtil.sendPacket(
                                                            new C09PacketHeldItemChange(
                                                                    mc.thePlayer.inventory.currentItem));
                                                }
                                                this.stopBlock();
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                this.isBlocking = true;
                                this.fakeBlockState = true;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 4: // BLINK
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging
                                        && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            this.blinkReset = true;
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                this.stopBlock();
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                this.isBlocking = true;
                                this.fakeBlockState = true;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 5: // INTERACT
                            if (this.hasValidTarget()) {
                                int item = ((IAccessorPlayerControllerMP) mc.playerController)
                                        .getCurrentPlayerItem();
                                if (mc.thePlayer.inventory.currentItem == item
                                        && !Myau.playerStateManager.digging
                                        && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            this.blinkReset = true;
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                int slot = this.findEmptySlot(item);
                                                PacketUtil.sendPacket(
                                                        new C09PacketHeldItemChange(slot));
                                                ((IAccessorPlayerControllerMP) mc.playerController)
                                                        .setCurrentPlayerItem(slot);
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                this.isBlocking = true;
                                this.fakeBlockState = true;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 6: // SWAP
                            if (this.hasValidTarget()) {
                                int item = ((IAccessorPlayerControllerMP) mc.playerController)
                                        .getCurrentPlayerItem();
                                if (mc.thePlayer.inventory.currentItem == item
                                        && !Myau.playerStateManager.digging
                                        && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            int slot = this.findSwordSlot(item);
                                            if (slot != -1) {
                                                if (!this.isPlayerBlocking()) {
                                                    swap = true;
                                                }
                                                this.blockTick = 1;
                                            }
                                            break;
                                        case 1:
                                            int swordsSlot = this.findSwordSlot(item);
                                            if (swordsSlot == -1) {
                                                this.blockTick = 0;
                                            } else if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            } else if (this.attackDelayMS <= 50L) {
                                                PacketUtil.sendPacket(
                                                        new C09PacketHeldItemChange(swordsSlot));
                                                ((IAccessorPlayerControllerMP) mc.playerController)
                                                        .setCurrentPlayerItem(swordsSlot);
                                                this.startBlock(
                                                        mc.thePlayer.inventory
                                                                .getStackInSlot(swordsSlot));
                                                attack = false;
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                    Myau.blinkManager.setBlinkState(
                                            false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = true;
                                    this.fakeBlockState = true;
                                    break;
                                }
                            }
                            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                            this.isBlocking = false;
                            this.fakeBlockState = false;
                            break;
                        case 7: // LEGIT
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging
                                        && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                this.stopBlock();
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                Myau.blinkManager.setBlinkState(
                                        false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = true;
                                this.fakeBlockState = false;
                            } else {
                                Myau.blinkManager.setBlinkState(
                                        false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 8: // FAKE
                            Myau.blinkManager.setBlinkState(
                                    false, BlinkModules.AUTO_BLOCK);
                            this.isBlocking = false;
                            this.fakeBlockState = this.hasValidTarget();
                            if (PlayerUtil.isUsingItem()
                                    && !this.isPlayerBlocking()
                                    && !Myau.playerStateManager.digging
                                    && !Myau.playerStateManager.placing) {
                                swap = true;
                            }
                            break;
                        case 9: // PREDAC
                            // ═══════════════════════════════════════
                            //  PredAC handles the ENTIRE cycle:
                            //  block → unblock → multi-attack → reblock
                            //  Skip normal attack flow
                            // ═══════════════════════════════════════
                            Myau.blinkManager.setBlinkState(
                                    false, BlinkModules.AUTO_BLOCK);
                            predACTick(event, attack);
                            return; // ← Skip everything below
                    }
                }
                boolean attacked = false;

                if (this.mode.getValue() == 3 && !multiTargets.isEmpty()) {
                    // ═══════════════════════════════════════════════
                    //  ENHANCED COMBO MODE — attacks up to 4 targets
                    //  per cycle with prediction engine
                    // ═══════════════════════════════════════════════
                    comboPrediction.updateTargets(multiTargets);

                    // Try to attack multiple targets this tick
                    int maxAttacksThisTick = Math.min(4, multiTargets.size());
                    for (int attackNum = 0; attackNum < maxAttacksThisTick; attackNum++) {
                        ComboTarget comboTarget = comboPrediction.getNextTarget();
                        if (comboTarget != null && comboTarget.isValid()) {
                            float[] predictedRots = comboTarget.getPredictedRotations(
                                    event.getYaw(), event.getPitch());
                            if (attackNum == 0
                                    && (this.rotations.getValue() == 2
                                    || this.rotations.getValue() == 3)) {
                                event.setRotation(predictedRots[0], predictedRots[1], 1);
                                if (this.rotations.getValue() == 3) {
                                    Myau.rotationManager.setRotation(
                                            predictedRots[0], predictedRots[1], 1, true);
                                    event.setPervRotation(predictedRots[0], 1);
                                }
                            }

                            // Sprint reset between multi-target attacks
                            if (this.sprintReset.getValue() && attackNum > 0
                                    && mc.thePlayer.isSprinting()) {
                                PacketUtil.sendPacket(new C0BPacketEntityAction(
                                        mc.thePlayer,
                                        C0BPacketEntityAction.Action.STOP_SPRINTING));
                                PacketUtil.sendPacket(new C0BPacketEntityAction(
                                        mc.thePlayer,
                                        C0BPacketEntityAction.Action.START_SPRINTING));
                            }

                            AttackData originalTarget = this.target;
                            this.target = comboTarget.getAttackData();
                            if (this.performAttack(predictedRots[0], predictedRots[1])) {
                                attacked = true;
                            }
                            this.target = originalTarget;
                        }
                    }
                } else if (this.mode.getValue() == 2 && !multiTargets.isEmpty()) {
                    // ═══════════════════════════════════════════════
                    //  ENHANCED MULTI MODE — all targets per tick
                    // ═══════════════════════════════════════════════
                    // Sort by hurtTime for optimal damage
                    if (this.hurtTimeExploit.getValue()) {
                        multiTargets.sort((a, b) -> Integer.compare(
                                a.getEntity().hurtTime,
                                b.getEntity().hurtTime));
                    }

                    for (int i = 0; i < multiTargets.size(); i++) {
                        AttackData multiTarget = multiTargets.get(i);
                        if (multiTarget == null
                                || !this.isValidTarget(multiTarget.getEntity())) {
                            continue;
                        }
                        if (!this.isBoxInSwingRange(multiTarget.getBox())) {
                            continue;
                        }

                        // Skip targets still in invuln window if we have
                        // vulnerable ones
                        if (this.hurtTimeExploit.getValue()
                                && multiTarget.getEntity().hurtTime > 3 && i > 0) {
                            boolean hasVulnerable = multiTargets.stream()
                                    .limit(i)
                                    .anyMatch(ad -> ad.getEntity().hurtTime == 0);
                            if (hasVulnerable) continue;
                        }

                        float[] targetRotations = RotationUtil.getRotationsToBox(
                                multiTarget.getBox(),
                                event.getYaw(),
                                event.getPitch(),
                                (float) this.angleStep.getValue()
                                        + RandomUtil.nextFloat(-5.0F, 5.0F),
                                (float) this.smoothing.getValue() / 100.0F
                        );
                        if (i == 0 && (this.rotations.getValue() == 2
                                || this.rotations.getValue() == 3)) {
                            event.setRotation(targetRotations[0], targetRotations[1], 1);
                            if (this.rotations.getValue() == 3) {
                                event.setPervRotation(targetRotations[0], 1);
                            }
                        }

                        // Sprint reset between targets
                        if (this.sprintReset.getValue() && i > 0
                                && mc.thePlayer.isSprinting()) {
                            PacketUtil.sendPacket(new C0BPacketEntityAction(
                                    mc.thePlayer,
                                    C0BPacketEntityAction.Action.STOP_SPRINTING));
                            PacketUtil.sendPacket(new C0BPacketEntityAction(
                                    mc.thePlayer,
                                    C0BPacketEntityAction.Action.START_SPRINTING));
                        }

                        if (this.performAttack(targetRotations[0], targetRotations[1])) {
                            attacked = true;
                        }
                    }
                    multiTargetIndex++;
                    if (multiTargetIndex >= multiTargets.size()) {
                        multiTargetIndex = 0;
                    }
                } else if (this.isBoxInSwingRange(this.target.getBox())) {
                    if (attack && (this.rotations.getValue() == 2
                            || this.rotations.getValue() == 3)) {
                        float angleStepValue = (float) this.angleStep.getValue();
                        float smoothingValue = (float) this.smoothing.getValue() / 100.0F;

                        float[] rotationsArr = RotationUtil.getRotationsToBox(
                                this.target.getBox(),
                                event.getYaw(),
                                event.getPitch(),
                                angleStepValue + RandomUtil.nextFloat(-2.0F, 2.0F),
                                smoothingValue
                        );

                        event.setRotation(rotationsArr[0], rotationsArr[1], 1);
                        if (this.rotations.getValue() == 3) {
                            Myau.rotationManager.setRotation(
                                    rotationsArr[0], rotationsArr[1], 1, true);
                        }
                        if (this.moveFix.getValue() != 0
                                || this.rotations.getValue() == 3) {
                            event.setPervRotation(rotationsArr[0], 1);
                        }
                    }
                    if (attack) {
                        attacked = this.performAttack(
                                event.getNewYaw(), event.getNewPitch());
                    }
                }
                if (swap) {
                    if (attacked) {
                        this.interactAttack(event.getNewYaw(), event.getNewPitch());
                    } else {
                        this.sendUseItem();
                    }
                }
                if (blocked) {
                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                    Myau.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK);
                }
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (this.isEnabled()) {
            switch (event.getType()) {
                case PRE:
                    if (this.target == null
                            || !this.isValidTarget(this.target.getEntity())
                            || !this.isBoxInAttackRange(this.target.getBox())
                            || !this.isBoxInSwingRange(this.target.getBox())
                            || this.timer.hasTimeElapsed(
                            this.switchDelay.getValue().longValue())) {
                        this.timer.reset();
                        ArrayList<EntityLivingBase> targets = new ArrayList<>();
                        for (Entity entity : mc.theWorld.loadedEntityList) {
                            if (entity instanceof EntityLivingBase
                                    && this.isValidTarget((EntityLivingBase) entity)
                                    && this.isInRange((EntityLivingBase) entity)) {
                                targets.add((EntityLivingBase) entity);
                            }
                        }
                        if (targets.isEmpty()) {
                            this.target = null;
                        } else {
                            if (targets.stream().anyMatch(this::isInSwingRange)) {
                                targets.removeIf(e -> !this.isInSwingRange(e));
                            }
                            if (targets.stream().anyMatch(this::isInAttackRange)) {
                                targets.removeIf(e -> !this.isInAttackRange(e));
                            }
                            if (targets.stream().anyMatch(this::isPlayerTarget)) {
                                targets.removeIf(e -> !this.isPlayerTarget(e));
                            }
                            targets.removeIf(this::willKnockIntoVoid);
                            if (targets.isEmpty()) {
                                this.target = null;
                                return;
                            }
                            targets.sort(
                                    (e1, e2) -> {
                                        int sortBase = 0;
                                        switch (this.sort.getValue()) {
                                            case 1:
                                                sortBase = Float.compare(
                                                        TeamUtil.getHealthScore(e1),
                                                        TeamUtil.getHealthScore(e2));
                                                break;
                                            case 2:
                                                sortBase = Integer.compare(
                                                        e1.hurtResistantTime,
                                                        e2.hurtResistantTime);
                                                break;
                                            case 3:
                                                sortBase = Float.compare(
                                                        RotationUtil.angleToEntity(e1),
                                                        RotationUtil.angleToEntity(e2));
                                        }
                                        return sortBase != 0
                                                ? sortBase
                                                : Double.compare(
                                                RotationUtil.distanceToEntity(e1),
                                                RotationUtil.distanceToEntity(e2));
                                    }
                            );
                            if (this.mode.getValue() == 2) {
                                multiTargets.clear();
                                int maxTargets = Math.min(4, targets.size());
                                for (int i = 0; i < maxTargets; i++) {
                                    multiTargets.add(new AttackData(targets.get(i)));
                                }
                                if (!multiTargets.isEmpty()) {
                                    this.target = multiTargets.get(0);
                                }
                            } else {
                                if (this.mode.getValue() == 1) {
                                    // ═══════════════════════════════════
                                    //  ENHANCED SWITCH MODE
                                    //  Uses intelligent target selection
                                    //  based on vulnerability windows
                                    // ═══════════════════════════════════
                                    long currentTime = System.currentTimeMillis();

                                    // Use enhanced selection when PredAC is active
                                    if (this.autoBlock.getValue() == 9) {
                                        EntityLivingBase bestSwitch =
                                                selectSwitchTarget(targets);
                                        if (bestSwitch != null) {
                                            this.target = new AttackData(bestSwitch);

                                            // Track for backtrack integration
                                            Module oldBacktrack = Myau.moduleManager.modules
                                                    .get(OldBacktrack.class);
                                            Module newBacktrack = Myau.moduleManager.modules
                                                    .get(NewBacktrack.class);
                                            boolean backtrackActive =
                                                    (oldBacktrack != null
                                                            && oldBacktrack.isEnabled())
                                                            || (newBacktrack != null
                                                            && newBacktrack.isEnabled());
                                            if (backtrackActive && this.target != null) {
                                                int targetId = this.target.getEntity()
                                                        .getEntityId();
                                                targetLastAttack.put(targetId, currentTime);
                                            }
                                        }
                                    } else {
                                        // Original switch logic for non-PredAC modes
                                        targets.sort((e1, e2) -> {
                                            float h1 = e1.getHealth() / e1.getMaxHealth();
                                            float h2 = e2.getHealth() / e2.getMaxHealth();
                                            return Float.compare(h1, h2);
                                        });
                                        boolean targetDead = this.target != null
                                                && (this.target.getEntity().isDead
                                                || this.target.getEntity().getHealth()
                                                <= 0.0f);
                                        if (targetDead && targets.size() > 1) {
                                            this.switchTick++;
                                            if (this.switchTick >= targets.size()) {
                                                this.switchTick = 0;
                                            }
                                            lastSwitchTime = currentTime;
                                            targetComboCount.clear();
                                        }
                                        Module oldBacktrack = Myau.moduleManager.modules
                                                .get(OldBacktrack.class);
                                        Module newBacktrack = Myau.moduleManager.modules
                                                .get(NewBacktrack.class);
                                        boolean backtrackActive =
                                                (oldBacktrack != null
                                                        && oldBacktrack.isEnabled())
                                                        || (newBacktrack != null
                                                        && newBacktrack.isEnabled());
                                        int baseSwitchDelay = this.switchDelay.getValue();
                                        if (backtrackActive) {
                                            baseSwitchDelay = Math.max(6,
                                                    baseSwitchDelay - 2);
                                        }
                                        boolean canSwitch = (currentTime - lastSwitchTime)
                                                >= baseSwitchDelay;
                                        if (this.hitRegistered || switchCooldown) {
                                            this.hitRegistered = false;
                                            if (switchCooldown
                                                    && currentTime >= switchCooldownEnd) {
                                                switchCooldown = false;
                                            }
                                            if (canSwitch && !switchCooldown) {
                                                this.switchTick++;
                                                lastSwitchTime = currentTime;
                                                if (this.target != null) {
                                                    int entityId = this.target.getEntity()
                                                            .getEntityId();
                                                    int comboHits = targetComboCount
                                                            .getOrDefault(entityId, 0);
                                                    targetComboCount.put(entityId,
                                                            comboHits + 1);
                                                    if (comboHits >= 2
                                                            && targets.size() > 1) {
                                                        switchCooldown = true;
                                                        switchCooldownEnd = currentTime + 15;
                                                        targetComboCount.put(entityId, 0);
                                                    }
                                                }
                                            }
                                        }
                                        if (this.switchTick >= targets.size()) {
                                            this.switchTick = 0;
                                            targetComboCount.clear();
                                        }
                                        this.target = new AttackData(
                                                targets.get(this.switchTick));
                                        if (backtrackActive && this.target != null) {
                                            int targetId = this.target.getEntity()
                                                    .getEntityId();
                                            targetLastAttack.put(targetId, currentTime);
                                        }
                                    }
                                } else if (this.mode.getValue() == 3) {
                                    // ═══════════════════════════════════
                                    //  ENHANCED COMBO MODE
                                    //  Maintains multi-target list and
                                    //  cycles intelligently
                                    // ═══════════════════════════════════
                                    long currentTime = System.currentTimeMillis();
                                    int maxComboTargets = Math.min(4, targets.size());

                                    // Sort by vulnerability then health
                                    targets.sort((e1, e2) -> {
                                        // Vulnerable targets first
                                        if (e1.hurtTime == 0 && e2.hurtTime > 0) return -1;
                                        if (e1.hurtTime > 0 && e2.hurtTime == 0) return 1;
                                        // Then by health
                                        float h1 = e1.getHealth() / e1.getMaxHealth();
                                        float h2 = e2.getHealth() / e2.getMaxHealth();
                                        return Float.compare(h1, h2);
                                    });

                                    // Build multi-target list for combo engine
                                    multiTargets.clear();
                                    for (int i = 0; i < maxComboTargets; i++) {
                                        multiTargets.add(new AttackData(targets.get(i)));
                                    }

                                    boolean targetDead = this.target != null
                                            && (this.target.getEntity().isDead
                                            || this.target.getEntity().getHealth()
                                            <= 0.0f);
                                    if (targetDead) {
                                        this.switchTick++;
                                        lastSwitchTime = currentTime;
                                    }
                                    if (this.hitRegistered) {
                                        this.hitRegistered = false;
                                        this.switchTick++;
                                        lastSwitchTime = currentTime;
                                        if (this.target != null) {
                                            int entityId = this.target.getEntity()
                                                    .getEntityId();
                                            int hits = targetHitCount
                                                    .getOrDefault(entityId, 0);
                                            targetHitCount.put(entityId, hits + 1);
                                        }
                                    }
                                    if (this.switchTick >= maxComboTargets) {
                                        this.switchTick = 0;
                                    }
                                    this.target = new AttackData(
                                            targets.get(this.switchTick));
                                } else {
                                    if (this.mode.getValue() == 0
                                            || this.switchTick >= targets.size()) {
                                        this.switchTick = 0;
                                    }
                                    this.target = new AttackData(
                                            targets.get(this.switchTick));
                                }
                            }
                        }
                    }
                    if (this.target != null) {
                        this.target = new AttackData(this.target.getEntity());
                    }

                    // Update hurt time tracking for all nearby targets
                    if (this.hurtTimeExploit.getValue()) {
                        for (Entity entity : mc.theWorld.loadedEntityList) {
                            if (entity instanceof EntityLivingBase
                                    && this.isValidTarget((EntityLivingBase) entity)) {
                                targetHurtTimeTracker.put(
                                        entity.getEntityId(),
                                        ((EntityLivingBase) entity).hurtTime
                                );
                            }
                        }
                    }
                    break;
                case POST:
                    if (this.isPlayerBlocking() && !mc.thePlayer.isBlocking()) {
                        mc.thePlayer.setItemInUse(
                                mc.thePlayer.getHeldItem(),
                                mc.thePlayer.getHeldItem().getMaxItemUseDuration());
                    }
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (this.isEnabled() && !event.isCancelled()
                && mc.thePlayer != null && mc.theWorld != null) {
            if (event.getPacket() instanceof C07PacketPlayerDigging) {
                C07PacketPlayerDigging packet = (C07PacketPlayerDigging) event.getPacket();
                if (packet.getStatus()
                        == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM) {
                    this.blockingState = false;
                }
            }
            if (event.getPacket() instanceof C09PacketHeldItemChange) {
                this.blockingState = false;
                if (this.isBlocking) {
                    mc.thePlayer.stopUsingItem();
                }
                if (this.autoBlock.getValue() == 9 && predACServerBlocking) {
                    predACServerBlocking = false;
                    predACState = PredACState.IDLE;
                }
            }

            // ═══════════════════════════════════════════════════
            //  TRANSACTION TRACKING — for PredAC AC bypass
            //  Track S32 confirmations to ensure our packets
            //  are processed in the correct order by the server.
            //  Prediction ACs bracket actions with transactions
            //  to verify client processing order.
            // ═══════════════════════════════════════════════════
            if (event.getPacket() instanceof S32PacketConfirmTransaction) {
                S32PacketConfirmTransaction packet =
                        (S32PacketConfirmTransaction) event.getPacket();
                lastConfirmedTransaction = packet.getActionNumber();
                if (pendingTransactions > 0) {
                    pendingTransactions--;
                }
                transactionSafe = pendingTransactions <= 1;
            }

            // ═══════════════════════════════════════════════════
            //  VELOCITY TRACKING — for combo optimization
            //  When we get hit, track the velocity to determine
            //  if we're being comboed. Adjust aggression accordingly.
            // ═══════════════════════════════════════════════════
            if (event.getPacket() instanceof S12PacketEntityVelocity) {
                S12PacketEntityVelocity packet =
                        (S12PacketEntityVelocity) event.getPacket();
                if (packet.getEntityID() == mc.thePlayer.getEntityId()) {
                    // We got hit — increase aggression if PredAC is active
                    if (this.autoBlock.getValue() == 9
                            && predACState == PredACState.BLOCKED) {
                        // Getting comboed while blocking — the AC sees
                        // us taking reduced damage. We should attack back
                        // ASAP. Reduce attack delay.
                        if (this.attackDelayMS > 50L) {
                            this.attackDelayMS = 50L;
                        }
                    }
                }
            }

            if (this.debugLog.getValue() == 1 && this.isAttackAllowed()) {
                if (event.getPacket() instanceof S06PacketUpdateHealth) {
                    float packet = ((S06PacketUpdateHealth) event.getPacket())
                            .getHealth() - mc.thePlayer.getHealth();
                    if (packet != 0.0F
                            && this.lastTickProcessed != mc.thePlayer.ticksExisted) {
                        this.lastTickProcessed = mc.thePlayer.ticksExisted;
                        ChatUtil.sendFormatted(
                                String.format(
                                        "%sHealth: %s&l%s&r (&otick: %d&r)&r",
                                        Myau.clientName,
                                        packet > 0.0F ? "&a" : "&c",
                                        df.format(packet),
                                        mc.thePlayer.ticksExisted
                                )
                        );
                    }
                }
                if (event.getPacket() instanceof S1CPacketEntityMetadata) {
                    S1CPacketEntityMetadata packet =
                            (S1CPacketEntityMetadata) event.getPacket();
                    if (packet.getEntityId() == mc.thePlayer.getEntityId()) {
                        for (WatchableObject watchableObject
                                : packet.func_149376_c()) {
                            if (watchableObject.getDataValueId() == 6) {
                                float diff = (Float) watchableObject.getObject()
                                        - mc.thePlayer.getHealth();
                                if (diff != 0.0F
                                        && this.lastTickProcessed
                                        != mc.thePlayer.ticksExisted) {
                                    this.lastTickProcessed =
                                            mc.thePlayer.ticksExisted;
                                    ChatUtil.sendFormatted(
                                            String.format(
                                                    "%sHealth: %s&l%s&r (&otick: %d&r)&r",
                                                    Myau.clientName,
                                                    diff > 0.0F ? "&a" : "&c",
                                                    df.format(diff),
                                                    mc.thePlayer.ticksExisted
                                            )
                                    );
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (this.isEnabled()) {
            if (this.moveFix.getValue() == 1
                    && this.rotations.getValue() != 3
                    && RotationState.isActived()
                    && RotationState.getPriority() == 1.0F
                    && MoveUtil.isForwardPressed()) {
                MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
            }
            if (this.shouldAutoBlock()) {
                mc.thePlayer.movementInput.jump = false;
            }
        }
    }

    @EventTarget
    public void onRender(Render3DEvent event) {
        if (this.isEnabled() && target != null) {
            if (this.showTarget.getValue() != 0
                    && TeamUtil.isEntityLoaded(this.target.getEntity())
                    && this.isAttackAllowed()) {
                Color color = new Color(-1);
                switch (this.showTarget.getValue()) {
                    case 1:
                        if (this.target.getEntity().hurtTime > 0) {
                            color = new Color(16733525);
                        } else {
                            color = new Color(5635925);
                        }
                        break;
                    case 2:
                        color = ((HUD) Myau.moduleManager.modules.get(HUD.class))
                                .getColor(System.currentTimeMillis());
                }
                RenderUtil.enableRenderState();
                RenderUtil.drawEntityBox(this.target.getEntity(),
                        color.getRed(), color.getGreen(), color.getBlue());

                // Draw boxes around ALL targets when in PredAC multi-target mode
                if (this.autoBlock.getValue() == 9 && !predACAttackQueue.isEmpty()) {
                    for (AttackData queuedTarget : predACAttackQueue) {
                        if (queuedTarget.getEntity() != this.target.getEntity()
                                && TeamUtil.isEntityLoaded(queuedTarget.getEntity())) {
                            Color multiColor = queuedTarget.getEntity().hurtTime > 0
                                    ? new Color(255, 170, 0, 120)
                                    : new Color(0, 255, 170, 120);
                            RenderUtil.drawEntityBox(queuedTarget.getEntity(),
                                    multiColor.getRed(),
                                    multiColor.getGreen(),
                                    multiColor.getBlue());
                        }
                    }
                }

                RenderUtil.disableRenderState();
            }
        }
    }

    @EventTarget
    public void onLeftClick(LeftClickMouseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        } else {
            if (this.isEnabled() && this.target != null && this.canAttack()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        } else {
            if (this.isEnabled() && this.target != null && this.canAttack()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onHitBlock(HitBlockEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        } else {
            if (this.isEnabled() && this.target != null && this.canAttack()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onCancelUse(CancelUseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        }
    }

    @Override
    public void onEnabled() {
        this.target = null;
        this.switchTick = 0;
        this.hitRegistered = false;
        this.attackDelayMS = 0L;
        this.blockTick = 0;
        // Reset PredAC state
        predACState = PredACState.IDLE;
        predACTickCounter = 0;
        predACServerBlocking = false;
        predACAttacksInWindow = 0;
        predACConsecutiveBlocks = 0;
        predACSprintResetPending = false;
        predACTicksSinceLastAttack = 0;
        predACAttackQueue.clear();
        predACTargetHits.clear();
        predACTargetLastHit.clear();
        predACTargetLastHealth.clear();
        // Reset sprint state
        sprintManipActive = false;
        sprintResetTick = 0;
        // Reset transaction tracking
        pendingTransactions = 0;
        transactionSafe = true;
        // Reset hurt time tracking
        targetHurtTimeTracker.clear();
        targetVulnerableWindow.clear();
    }

    @Override
    public void onDisabled() {
        if (this.autoBlock.getValue() == 9) {
            predACCleanup();
        }
        Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
        this.blockingState = false;
        this.isBlocking = false;
        this.fakeBlockState = false;
        // Clean up sprint manipulation
        if (sprintManipActive) {
            sprintManipActive = false;
        }
    }

    @Override
    public void verifyValue(String value) {
        boolean badCps = this.autoBlock.getValue() == 2
                || this.autoBlock.getValue() == 3
                || this.autoBlock.getValue() == 4
                || this.autoBlock.getValue() == 5
                || this.autoBlock.getValue() == 6
                || this.autoBlock.getValue() == 7;
        if (!this.autoBlock.getName().equals(value)) {
            if (this.swingRange.getName().equals(value)) {
                if (this.swingRange.getValue() < this.attackRange.getValue()) {
                    this.attackRange.setValue(this.swingRange.getValue());
                }
            } else if (this.attackRange.getName().equals(value)) {
                if (this.swingRange.getValue() < this.attackRange.getValue()) {
                    this.swingRange.setValue(this.attackRange.getValue());
                }
            } else if (this.minCPS.getName().equals(value)) {
                if (this.minCPS.getValue() > this.maxCPS.getValue()) {
                    this.maxCPS.setValue(this.minCPS.getValue());
                }
            } else if (this.autoBlockMinCPS.getName().equals(value)) {
                if (this.autoBlockMinCPS.getValue() > this.autoBlockMaxCPS.getValue()) {
                    this.autoBlockMaxCPS.setValue(this.autoBlockMinCPS.getValue());
                }
                if (autoBlockMinCPS.getValue() > 18.0F && badCps) {
                    autoBlockMinCPS.setValue(16.0F);
                }
            } else if (this.autoBlockMaxCPS.getName().equals(value)) {
                if (this.autoBlockMinCPS.getValue() > this.autoBlockMaxCPS.getValue()) {
                    this.autoBlockMinCPS.setValue(this.autoBlockMaxCPS.getValue());
                }
                if (autoBlockMaxCPS.getValue() > 18.0F && badCps) {
                    autoBlockMaxCPS.setValue(16.0F);
                }
            } else {
                if (this.maxCPS.getName().equals(value)
                        && this.minCPS.getValue() > this.maxCPS.getValue()) {
                    this.minCPS.setValue(this.maxCPS.getValue());
                }
            }
        } else {
            if (badCps && (this.autoBlockMinCPS.getValue() > 18.0F
                    || this.autoBlockMaxCPS.getValue() > 18.0F)) {
                this.autoBlockMinCPS.setValue(14.0F);
                this.autoBlockMaxCPS.setValue(16.0F);
            }
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(
                CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ENHANCED COMBO PREDICTION ENGINE
    //
    //  Improvements over original:
    //  1. Hurt time awareness — never attack invulnerable targets
    //  2. Velocity-based prediction — more accurate aim
    //  3. Sprint KB calculation — factor into target priority
    //  4. Combo chain optimization — maximize damage across targets
    //  5. Attack timing coordination — stagger attacks across targets
    //     so each target's hurtTime clears when we cycle back to them
    // ═══════════════════════════════════════════════════════════════════

    public static class ComboPredictionEngine {
        private static final int MAX_COMBO_TARGETS = 4;
        private static final long ATTACK_INTERVAL = 50L;
        private static final double MAX_PREDICTION_DISTANCE = 3.5;

        private final ComboTarget[] comboTargets = new ComboTarget[MAX_COMBO_TARGETS];
        private int currentTargetIndex = 0;
        private long lastAttackTime = 0L;
        private long lastCycleTime = 0L;
        private int cycleCount = 0;

        // Track which targets were attacked this cycle for stagger logic
        private final boolean[] attackedThisCycle = new boolean[MAX_COMBO_TARGETS];

        public ComboPredictionEngine() {
            for (int i = 0; i < MAX_COMBO_TARGETS; i++) {
                comboTargets[i] = new ComboTarget();
            }
        }

        public void updateTargets(ArrayList<AttackData> targets) {
            for (int i = 0; i < MAX_COMBO_TARGETS; i++) {
                comboTargets[i].reset();
            }
            int count = Math.min(targets.size(), MAX_COMBO_TARGETS);

            // Sort targets by vulnerability before assigning slots
            targets.sort((a, b) -> {
                EntityLivingBase ea = a.getEntity();
                EntityLivingBase eb = b.getEntity();

                // Vulnerable targets get lower indices (attacked first)
                if (ea.hurtTime == 0 && eb.hurtTime > 0) return -1;
                if (ea.hurtTime > 0 && eb.hurtTime == 0) return 1;

                // Lower health = higher priority
                float ha = ea.getHealth() / ea.getMaxHealth();
                float hb = eb.getHealth() / eb.getMaxHealth();
                return Float.compare(ha, hb);
            });

            for (int i = 0; i < count; i++) {
                comboTargets[i].setTarget(targets.get(i));
                comboTargets[i].updatePrediction();
            }
        }

        public ComboTarget getNextTarget() {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastAttackTime < ATTACK_INTERVAL) {
                return null;
            }

            // New cycle detection
            if (currentTime - lastCycleTime > 500) {
                lastCycleTime = currentTime;
                cycleCount++;
                for (int i = 0; i < MAX_COMBO_TARGETS; i++) {
                    attackedThisCycle[i] = false;
                }
            }

            // Find the best target to attack RIGHT NOW
            ComboTarget bestTarget = null;
            double bestScore = Double.MIN_VALUE;
            int bestIndex = -1;

            for (int i = 0; i < MAX_COMBO_TARGETS; i++) {
                ComboTarget target = comboTargets[i];
                if (!target.isValid() || !target.canAttack(currentTime)) {
                    continue;
                }

                double score = target.getPriorityScore();

                // Bonus for targets not yet attacked this cycle
                if (!attackedThisCycle[i]) {
                    score += 25.0;
                }

                // Bonus for vulnerable targets (hurtTime == 0)
                if (target.getEntity() != null && target.getEntity().hurtTime == 0) {
                    score += 50.0;
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestTarget = target;
                    bestIndex = i;
                }
            }

            if (bestTarget != null && bestIndex >= 0) {
                lastAttackTime = currentTime;
                attackedThisCycle[bestIndex] = true;
                bestTarget.recordAttack(currentTime);
                return bestTarget;
            }

            // Fallback: cycle through targets sequentially
            int attempts = 0;
            while (attempts < MAX_COMBO_TARGETS) {
                currentTargetIndex = (currentTargetIndex + 1) % MAX_COMBO_TARGETS;
                ComboTarget target = comboTargets[currentTargetIndex];
                if (target.isValid() && target.canAttack(currentTime)) {
                    lastAttackTime = currentTime;
                    attackedThisCycle[currentTargetIndex] = true;
                    target.recordAttack(currentTime);
                    return target;
                }
                attempts++;
            }

            return null;
        }

        public ArrayList<ComboTarget> getAllValidTargets() {
            ArrayList<ComboTarget> valid = new ArrayList<>();
            for (ComboTarget target : comboTargets) {
                if (target.isValid()) {
                    valid.add(target);
                }
            }
            return valid;
        }

        /**
         * Get ALL targets that can be attacked RIGHT NOW.
         * Used by PredAC for multi-target burst attacks.
         */
        public ArrayList<ComboTarget> getAllAttackableTargets() {
            long currentTime = System.currentTimeMillis();
            ArrayList<ComboTarget> attackable = new ArrayList<>();
            for (ComboTarget target : comboTargets) {
                if (target.isValid() && target.canAttack(currentTime)) {
                    attackable.add(target);
                }
            }
            // Sort by priority — most valuable targets first
            attackable.sort((a, b) -> Double.compare(
                    b.getPriorityScore(), a.getPriorityScore()));
            return attackable;
        }

        public ComboTarget getBestTarget() {
            ComboTarget best = null;
            double bestScore = -1.0;
            for (ComboTarget target : comboTargets) {
                if (target.isValid()) {
                    double score = target.getPriorityScore();
                    if (score > bestScore) {
                        bestScore = score;
                        best = target;
                    }
                }
            }
            return best;
        }

        public boolean canAttackAny() {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastAttackTime < ATTACK_INTERVAL) {
                return false;
            }
            for (ComboTarget target : comboTargets) {
                if (target.isValid() && target.canAttack(currentTime)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static class ComboTarget {
        private AttackData attackData;
        private Vec3 predictedPosition;
        private long lastAttackTime;
        private int comboCount;
        private double distanceToPlayer;
        private boolean valid;
        private Vec3 lastPosition;
        private Vec3 velocity;
        private long lastUpdateTime;
        // NEW: Enhanced prediction state
        private Vec3 acceleration;
        private Vec3 prevVelocity;
        private int consecutiveHits;
        private float lastKnownHealth;

        public ComboTarget() {
            reset();
        }

        public void reset() {
            this.attackData = null;
            this.predictedPosition = null;
            this.lastAttackTime = 0L;
            this.comboCount = 0;
            this.distanceToPlayer = Double.MAX_VALUE;
            this.valid = false;
            this.lastPosition = null;
            this.velocity = new Vec3(0, 0, 0);
            this.acceleration = new Vec3(0, 0, 0);
            this.prevVelocity = new Vec3(0, 0, 0);
            this.lastUpdateTime = 0L;
            this.consecutiveHits = 0;
            this.lastKnownHealth = 0.0F;
        }

        public void setTarget(AttackData data) {
            this.attackData = data;
            this.valid = true;
            this.distanceToPlayer = mc.thePlayer.getDistanceToEntity(data.getEntity());
            this.lastPosition = new Vec3(data.getX(), data.getY(), data.getZ());
            this.lastUpdateTime = System.currentTimeMillis();
            this.lastKnownHealth = data.getEntity().getHealth();
        }

        public void updatePrediction() {
            if (!valid || attackData == null) return;
            EntityLivingBase entity = attackData.getEntity();
            long currentTime = System.currentTimeMillis();
            Vec3 currentPos = new Vec3(entity.posX, entity.posY, entity.posZ);

            if (lastPosition != null && lastUpdateTime > 0) {
                double timeDelta = (currentTime - lastUpdateTime) / 1000.0;
                if (timeDelta > 0 && timeDelta < 1.0) {
                    double dx = (currentPos.xCoord - lastPosition.xCoord) / timeDelta;
                    double dy = (currentPos.yCoord - lastPosition.yCoord) / timeDelta;
                    double dz = (currentPos.zCoord - lastPosition.zCoord) / timeDelta;

                    prevVelocity = velocity;
                    this.velocity = new Vec3(dx, dy, dz);

                    // Calculate acceleration for better prediction
                    this.acceleration = new Vec3(
                            (velocity.xCoord - prevVelocity.xCoord) / timeDelta,
                            (velocity.yCoord - prevVelocity.yCoord) / timeDelta,
                            (velocity.zCoord - prevVelocity.zCoord) / timeDelta
                    );
                }
            }

            // Enhanced prediction using velocity + acceleration
            double predictionTime = 0.05; // 1 tick ahead
            double predX = entity.posX
                    + (entity.motionX * predictionTime)
                    + (0.5 * acceleration.xCoord * predictionTime * predictionTime);
            double predY = entity.posY
                    + (entity.motionY * predictionTime)
                    + (0.5 * acceleration.yCoord * predictionTime * predictionTime);
            double predZ = entity.posZ
                    + (entity.motionZ * predictionTime)
                    + (0.5 * acceleration.zCoord * predictionTime * predictionTime);

            this.predictedPosition = new Vec3(predX, predY, predZ);
            this.lastPosition = currentPos;
            this.lastUpdateTime = currentTime;
            this.distanceToPlayer = mc.thePlayer.getDistanceToEntity(entity);

            // Track health changes for combo detection
            float currentHealth = entity.getHealth();
            if (currentHealth < lastKnownHealth) {
                consecutiveHits++;
            }
            lastKnownHealth = currentHealth;
        }

        public boolean canAttack(long currentTime) {
            if (!valid || attackData == null) return false;
            EntityLivingBase entity = attackData.getEntity();
            if (entity.isDead || entity.deathTime > 0) {
                valid = false;
                return false;
            }
            if (distanceToPlayer > 3.0) {
                return false;
            }
            // Respect hurt time — don't waste attacks on invulnerable targets
            // Unless they're about to become vulnerable (hurtTime <= 2)
            if (entity.hurtTime > 2) {
                return false;
            }
            return (currentTime - lastAttackTime) >= 50L;
        }

        public void recordAttack(long time) {
            this.lastAttackTime = time;
            this.comboCount++;
        }

        public double getPriorityScore() {
            if (!valid || attackData == null) return -1.0;
            EntityLivingBase entity = attackData.getEntity();
            double score = 100.0;

            // Distance — closer is better
            score -= distanceToPlayer * 10.0;

            // Health — lower = more kill pressure
            float healthPercent = entity.getHealth() / entity.getMaxHealth();
            score += (1.0 - healthPercent) * 40.0;

            // Hurt time — vulnerable targets get massive bonus
            if (entity.hurtTime == 0) {
                score += 30.0;
            } else if (entity.hurtTime <= 2) {
                score += 15.0; // About to become vulnerable
            } else {
                score -= entity.hurtTime * 5.0; // Still invulnerable
            }

            // Combo maintenance — keep hitting targets we've been comboing
            if (comboCount > 0 && comboCount < 4) {
                score += 20.0;
            }

            // Consecutive hits bonus — momentum
            score += Math.min(consecutiveHits, 5) * 8.0;

            // Freshness — targets not attacked recently get bonus
            score += (10.0 - Math.min(comboCount, 10)) * 2.0;

            return score;
        }

        public Vec3 getPredictedPosition() {
            return predictedPosition != null ? predictedPosition :
                    new Vec3(attackData.getX(), attackData.getY(), attackData.getZ());
        }

        public float[] getPredictedRotations(float currentYaw, float currentPitch) {
            if (!valid || attackData == null) {
                return new float[]{currentYaw, currentPitch};
            }

            Vec3 predicted = getPredictedPosition();
            Vec3 playerEyes = mc.thePlayer.getPositionEyes(1.0F);

            // Aim at center mass (slightly above feet for reliability)
            double targetHeight = attackData.getEntity().height * 0.7;
            double deltaX = predicted.xCoord - playerEyes.xCoord;
            double deltaY = (predicted.yCoord + targetHeight) - playerEyes.yCoord;
            double deltaZ = predicted.zCoord - playerEyes.zCoord;
            double dist = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

            float yaw = (float) (Math.atan2(deltaZ, deltaX) * 180.0 / Math.PI) - 90.0F;
            float pitch = (float) -(Math.atan2(deltaY, dist) * 180.0 / Math.PI);

            // Clamp pitch to valid range
            pitch = MathHelper.clamp_float(pitch, -90.0F, 90.0F);

            // Normalize yaw
            yaw = MathHelper.wrapAngleTo180_float(yaw);

            return new float[]{yaw, pitch};
        }

        public AttackData getAttackData() {
            return attackData;
        }

        public EntityLivingBase getEntity() {
            return attackData != null ? attackData.getEntity() : null;
        }

        public boolean isValid() {
            return valid && attackData != null;
        }

        public int getComboCount() {
            return comboCount;
        }

        public double getDistance() {
            return distanceToPlayer;
        }
    }

    public static class AttackData {
        private final EntityLivingBase entity;
        private final AxisAlignedBB box;
        private final double x;
        private final double y;
        private final double z;

        public AttackData(EntityLivingBase entityLivingBase) {
            this.entity = entityLivingBase;
            double collisionBorderSize = entityLivingBase.getCollisionBorderSize();
            this.box = entityLivingBase.getEntityBoundingBox().expand(
                    collisionBorderSize, collisionBorderSize, collisionBorderSize);
            this.x = entityLivingBase.posX;
            this.y = entityLivingBase.posY;
            this.z = entityLivingBase.posZ;
        }

        public EntityLivingBase getEntity() {
            return this.entity;
        }

        public AxisAlignedBB getBox() {
            return this.box;
        }

        public double getX() {
            return this.x;
        }

        public double getY() {
            return this.y;
        }

        public double getZ() {
            return this.z;
        }
    }
}