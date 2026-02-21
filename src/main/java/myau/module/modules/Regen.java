package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.mixin.IAccessorMinecraft;
import myau.module.Module;
import myau.property.properties.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.*;
import net.minecraft.potion.Potion;
import net.minecraft.util.MathHelper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;

@ModuleInfo(category = ModuleCategory.PLAYER)
public class Regen extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{
            "Packet", "Timer", "Transaction", "Hybrid"
    });
    public final ModeProperty anticheat = new ModeProperty("anticheat", 0, new String[]{
            "Old Grim", "New Grim", "Vulcan", "Intave", "Polar", "Verus",
            "Matrix", "NCP", "Spartan", "AAC", "Karhu", "Hawk", "None"
    });
    public final FloatProperty health = new FloatProperty("health", 18.0F, 1.0F, 20.0F);
    public final IntProperty speed = new IntProperty("speed", 10, 1, 100);
    public final BooleanProperty onlyGround = new BooleanProperty("only-ground", true);
    public final BooleanProperty noAction = new BooleanProperty("no-action", true);
    public final FloatProperty foodLevel = new FloatProperty("min-food", 18.0F, 0.0F, 20.0F);
    public final BooleanProperty autoDisable = new BooleanProperty("auto-disable", false);

    private static class ACP {
        final int pktPerTick;
        final boolean useTimer, useTxExploit, useC03Spoof;
        final boolean groundSpoof, rotSpoof;
        final double timerSpeed;
        final int txHoldTicks, txBatchSize;
        final boolean wrapC03InTx;
        final int c03Variant;
        final double posYOffset;
        final boolean duplicateC03;
        final int dupInterval;
        final boolean exploitEntityAction;
        final boolean spoofDigging;
        final boolean abuseTeleportConfirm;
        final boolean desyncPosition;
        final double desyncRange;
        final boolean holdC0F;
        final int holdC0FTicks;
        final boolean injectKeepAlive;
        final boolean doubleConfirm;

        ACP(int ppt, boolean timer, boolean txExploit, boolean c03Spoof,
            boolean gndSpoof, boolean rotSpoof, double timerSpd,
            int txHold, int txBatch, boolean wrapTx, int c03Var, double posYOff,
            boolean dupC03, int dupInt, boolean exploitEA, boolean spoofDig,
            boolean abuseTp, boolean desyncPos, double desyncR,
            boolean holdC0F, int holdC0FT, boolean injectKA, boolean dblConfirm) {
            this.pktPerTick=ppt;this.useTimer=timer;this.useTxExploit=txExploit;
            this.useC03Spoof=c03Spoof;this.groundSpoof=gndSpoof;this.rotSpoof=rotSpoof;
            this.timerSpeed=timerSpd;this.txHoldTicks=txHold;this.txBatchSize=txBatch;
            this.wrapC03InTx=wrapTx;this.c03Variant=c03Var;this.posYOffset=posYOff;
            this.duplicateC03=dupC03;this.dupInterval=dupInt;this.exploitEntityAction=exploitEA;
            this.spoofDigging=spoofDig;this.abuseTeleportConfirm=abuseTp;
            this.desyncPosition=desyncPos;this.desyncRange=desyncR;
            this.holdC0F=holdC0F;this.holdC0FTicks=holdC0FT;
            this.injectKeepAlive=injectKA;this.doubleConfirm=dblConfirm;
        }
    }

    private static final ACP[] PROFILES = {
        /*OldGrim*/    new ACP(  8, false, true, true, true, true, 1.0, 3,  2,  true, 1,  1E-14,true, 3,  true, false,false,true, 0.03, true, 3,   false,true ),
        /*NewGrim*/    new ACP(  4, false, true, true, true, true, 1.0, 2,  1,  true, 1,  1E-15,true, 4,  false,false,false,true, 0.01, true, 2,   false,true ),
        /*Vulcan*/     new ACP( 12, true,  true, true, true, true, 1.5, 4,  3,  false,2,  1E-13,true, 2,  true, true, false,true, 0.05, true, 4,   true, true ),
        /*Intave*/     new ACP(  8, false, true, true, true, true, 1.0, 3,  2,  true, 1,  1E-14,true, 3,  true, false,false,true, 0.03, true, 3,   false,false),
        /*Polar*/      new ACP(  5, false, true, true, true, true, 1.0, 2,  1,  true, 1,  1E-15,true, 4,  false,false,false,true, 0.02, true, 2,   false,true ),
        /*Verus*/      new ACP( 50, true,  true, false,true, false,2.0, 6,  5,  false,0,  0.0,  true, 1,  true, true, true, true, 0.10, false,0,   true, true ),
        /*Matrix*/     new ACP( 15, true,  true, true, true, true, 1.5, 4,  3,  false,2,  1E-13,true, 2,  true, true, false,true, 0.05, true, 3,   true, true ),
        /*NCP*/        new ACP( 20, true,  true, false,true, false,1.8, 5,  4,  false,0,  0.0,  true, 1,  true, true, true, true, 0.08, false,0,   true, true ),
        /*Spartan*/    new ACP( 80, true,  false,false,true, false,2.5, 0,  0,  false,0,  0.0,  true, 1,  true, true, true, true, 0.15, false,0,   true, false),
        /*AAC*/        new ACP( 10, true,  true, true, true, true, 1.3, 3,  2,  true, 2,  1E-14,true, 3,  true, true, false,true, 0.04, true, 3,   true, true ),
        /*Karhu*/      new ACP(  5, false, true, true, true, true, 1.0, 2,  1,  true, 1,  1E-15,true, 4,  false,false,false,true, 0.02, true, 2,   false,true ),
        /*Hawk*/       new ACP(  8, false, true, true, true, true, 1.0, 3,  2,  true, 1,  1E-14,true, 3,  true, false,false,true, 0.03, true, 3,   false,false),
        /*None*/       new ACP(100, true,  false,false,true, false,5.0, 0,  0,  false,0,  0.0,  true, 1,  false,false,false,false,0.0,  false,0,   false,false),
    };

    private final Deque<Packet<?>> heldC0F = new ArrayDeque<>();
    private volatile boolean releasing;

    private int tickCount, txHoldCounter, dupCounter;
    private boolean holdingTx;
    private long sessionStart;
    private boolean wasHealing;
    private int cycles;
    private boolean desynced;
    private double desyncX, desyncY, desyncZ;
    private double timerBalance;
    private static final double MAX_TIMER_BALANCE = 15.0;
    private long lastKAId;
    private boolean lastGroundState;
    private int groundTicks;
    private int c03SentThisTick;
    private int totalC03ThisSession;
    private long lastC03Time;

    public Regen() { super("Regen", false); }
    private ACP prof() { int i=anticheat.getValue();return(i>=0&&i<PROFILES.length)?PROFILES[i]:PROFILES[12]; }

    private void setTimerSpeed(float spd) {
        ((IAccessorMinecraft) mc).getTimer().timerSpeed = spd;
    }

    private float getTimerSpeed() {
        return ((IAccessorMinecraft) mc).getTimer().timerSpeed;
    }

    @Override
    public void onEnabled() { rst(); }

    @Override
    public void onDisabled() {
        setTimerSpeed(1.0F);
        if (desynced) resync(prof());
        flushC0F();
        rst();
    }

    private void rst() {
        heldC0F.clear();releasing=false;tickCount=0;txHoldCounter=0;
        dupCounter=0;holdingTx=false;sessionStart=System.currentTimeMillis();
        wasHealing=false;cycles=0;desynced=false;desyncX=desyncY=desyncZ=0;
        timerBalance=0;lastKAId=0;lastGroundState=false;groundTicks=0;
        c03SentThisTick=0;totalC03ThisSession=0;lastC03Time=0;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0) return;

        ACP p = prof();
        tickCount++;
        c03SentThisTick = 0;

        if (timerBalance > 0) timerBalance = Math.max(0, timerBalance - 1.0);

        if (mc.thePlayer.onGround) groundTicks++;
        else groundTicks = 0;
        lastGroundState = mc.thePlayer.onGround;

        float hp = mc.thePlayer.getHealth();
        if (hp >= health.getValue()) {
            if (wasHealing) { wasHealing = false; cycles++; }
            if (desynced) resync(p);
            if (autoDisable.getValue()) { setEnabled(false); return; }
            return;
        }

        if (!canHeal(p)) return;

        wasHealing = true;

        switch (mode.getValue()) {
            case 0: execPacket(p); break;
            case 1: execTimer(p); break;
            case 2: execTransaction(p); break;
            case 3: execHybrid(p); break;
        }
    }

    private void execPacket(ACP p) {
        int count = Math.min(speed.getValue(), p.pktPerTick);

        if (p.holdC0F && !holdingTx) {
            holdingTx = true;
            txHoldCounter = 0;
        }

        for (int i = 0; i < count; i++) {
            if (p.duplicateC03 && dupCounter % p.dupInterval == 0) {
                sendSpoofedC03(p);
                dupCounter++;
                continue;
            }

            if (p.exploitEntityAction && i % 3 == 0) {
                mc.getNetHandler().addToSendQueue(new C0BPacketEntityAction(
                        mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
            }

            sendSpoofedC03(p);

            if (p.exploitEntityAction && i % 3 == 0) {
                mc.getNetHandler().addToSendQueue(new C0BPacketEntityAction(
                        mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
            }

            if (p.spoofDigging && i % 4 == 0) {
                mc.getNetHandler().addToSendQueue(new C07PacketPlayerDigging(
                        C07PacketPlayerDigging.Action.RELEASE_USE_ITEM,
                        mc.thePlayer.getPosition(), mc.thePlayer.getHorizontalFacing()));
            }

            dupCounter++;
        }

        if (holdingTx) {
            txHoldCounter++;
            if (txHoldCounter >= p.holdC0FTicks) {
                holdingTx = false;
                txHoldCounter = 0;
                flushC0F();
            }
        }
    }

    private void execTimer(ACP p) {
        if (!p.useTimer) { execPacket(p); return; }

        double targetSpeed = p.timerSpeed;

        if (timerBalance < MAX_TIMER_BALANCE) {
            setTimerSpeed((float) targetSpeed);
            timerBalance += (targetSpeed - 1.0);
        } else {
            setTimerSpeed(1.0F);
        }

        int extraPkts = Math.min(speed.getValue() / 2, p.pktPerTick / 2);
        for (int i = 0; i < extraPkts; i++) {
            sendSpoofedC03(p);
        }
    }

    private void execTransaction(ACP p) {
        if (!p.useTxExploit) { execPacket(p); return; }

        holdingTx = true;

        int batches = Math.max(1, speed.getValue() / Math.max(1, p.txBatchSize));
        int perBatch = Math.min(p.txBatchSize, p.pktPerTick);

        for (int b = 0; b < batches; b++) {
            for (int i = 0; i < perBatch; i++) {
                if (p.wrapC03InTx && !heldC0F.isEmpty()) {
                    releasing = true;
                    mc.getNetHandler().addToSendQueue(heldC0F.pollFirst());
                    releasing = false;
                }
                sendSpoofedC03(p);
            }
        }

        txHoldCounter++;
        if (txHoldCounter >= p.txHoldTicks) {
            holdingTx = false;
            txHoldCounter = 0;
            flushC0F();
        }
    }

    private void execHybrid(ACP p) {
        if (p.desyncPosition && !desynced && groundTicks > 5) {
            desyncX = mc.thePlayer.posX + (ThreadLocalRandom.current().nextDouble() - 0.5) * 2.0 * p.desyncRange;
            desyncY = mc.thePlayer.posY;
            desyncZ = mc.thePlayer.posZ + (ThreadLocalRandom.current().nextDouble() - 0.5) * 2.0 * p.desyncRange;
            mc.getNetHandler().addToSendQueue(new C03PacketPlayer.C04PacketPlayerPosition(
                    desyncX, desyncY, desyncZ, true));
            desynced = true;
        }

        if (p.holdC0F) holdingTx = true;

        if (p.useTimer && timerBalance < MAX_TIMER_BALANCE) {
            float spd = (float) Math.min(p.timerSpeed, 1.0 + (MAX_TIMER_BALANCE - timerBalance) * 0.1);
            setTimerSpeed(spd);
            timerBalance += (getTimerSpeed() - 1.0);
        }

        if (p.injectKeepAlive && tickCount % 5 == 0) {
            lastKAId = ThreadLocalRandom.current().nextLong();
            mc.getNetHandler().addToSendQueue(new C00PacketKeepAlive((int) lastKAId));
        }

        int count = Math.min(speed.getValue(), p.pktPerTick);
        int txBatchCounter = 0;

        for (int i = 0; i < count; i++) {
            if (p.wrapC03InTx && !heldC0F.isEmpty() && txBatchCounter >= p.txBatchSize) {
                releasing = true;
                mc.getNetHandler().addToSendQueue(heldC0F.pollFirst());
                releasing = false;
                txBatchCounter = 0;
            }

            if (p.exploitEntityAction && i % 2 == 0) {
                mc.getNetHandler().addToSendQueue(new C0BPacketEntityAction(
                        mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
            }

            sendSpoofedC03(p);
            txBatchCounter++;

            if (p.exploitEntityAction && i % 2 == 0) {
                mc.getNetHandler().addToSendQueue(new C0BPacketEntityAction(
                        mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
            }

            if (p.spoofDigging && i % 3 == 0) {
                mc.getNetHandler().addToSendQueue(new C07PacketPlayerDigging(
                        C07PacketPlayerDigging.Action.RELEASE_USE_ITEM,
                        mc.thePlayer.getPosition(), mc.thePlayer.getHorizontalFacing()));
            }

            if (p.doubleConfirm && !heldC0F.isEmpty() && i % 4 == 0) {
                releasing = true;
                Packet<?> c0f = heldC0F.peekFirst();
                if (c0f != null) {
                    mc.getNetHandler().addToSendQueue(c0f);
                    mc.getNetHandler().addToSendQueue(c0f);
                }
                heldC0F.pollFirst();
                releasing = false;
            }

            dupCounter++;
        }

        if (desynced && tickCount % 10 == 0) resync(p);

        if (holdingTx) {
            txHoldCounter++;
            if (txHoldCounter >= p.txHoldTicks) {
                holdingTx = false;
                txHoldCounter = 0;
                flushC0F();
            }
        }
    }

    private void sendSpoofedC03(ACP p) {
        if (mc.getNetHandler() == null) return;

        double px = mc.thePlayer.posX;
        double py = mc.thePlayer.posY;
        double pz = mc.thePlayer.posZ;

        if (desynced && p.desyncPosition) {
            px = desyncX + (ThreadLocalRandom.current().nextDouble() - 0.5) * p.desyncRange * 0.1;
            py = desyncY;
            pz = desyncZ + (ThreadLocalRandom.current().nextDouble() - 0.5) * p.desyncRange * 0.1;
        }

        if (p.posYOffset > 0) {
            py += p.posYOffset * (tickCount % 2 == 0 ? 1 : -1);
        }

        boolean ground = p.groundSpoof || mc.thePlayer.onGround;

        Packet<?> pkt;
        switch (p.c03Variant) {
            case 1:
                float yaw = mc.thePlayer.rotationYaw;
                float pitch = mc.thePlayer.rotationPitch;
                if (p.rotSpoof) {
                    yaw += (float)(ThreadLocalRandom.current().nextGaussian() * 0.01);
                    pitch += (float)(ThreadLocalRandom.current().nextGaussian() * 0.005);
                    pitch = MathHelper.clamp_float(pitch, -90F, 90F);
                }
                pkt = new C03PacketPlayer.C06PacketPlayerPosLook(px, py, pz, yaw, pitch, ground);
                break;
            case 2:
                if (c03SentThisTick % 2 == 0) {
                    pkt = new C03PacketPlayer.C04PacketPlayerPosition(px, py, pz, ground);
                } else {
                    float y2 = mc.thePlayer.rotationYaw;
                    float p2 = mc.thePlayer.rotationPitch;
                    if (p.rotSpoof) {
                        y2 += (float)(ThreadLocalRandom.current().nextGaussian() * 0.008);
                        p2 += (float)(ThreadLocalRandom.current().nextGaussian() * 0.004);
                        p2 = MathHelper.clamp_float(p2, -90F, 90F);
                    }
                    pkt = new C03PacketPlayer.C06PacketPlayerPosLook(px, py, pz, y2, p2, ground);
                }
                break;
            default:
                pkt = new C03PacketPlayer.C04PacketPlayerPosition(px, py, pz, ground);
                break;
        }

        mc.getNetHandler().addToSendQueue(pkt);
        c03SentThisTick++;
        totalC03ThisSession++;
        lastC03Time = System.currentTimeMillis();
    }

    private void resync(ACP p) {
        if (!desynced) return;
        mc.getNetHandler().addToSendQueue(new C03PacketPlayer.C04PacketPlayerPosition(
                mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, mc.thePlayer.onGround));
        desynced = false;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null) return;
        ACP p = prof();

        if (event.getType() == EventType.SEND) {
            if (releasing) return;
            Packet<?> pkt = event.getPacket();
            if (pkt instanceof C0FPacketConfirmTransaction && holdingTx) {
                event.setCancelled(true);
                heldC0F.addLast(pkt);
                if (heldC0F.size() > 40) {
                    holdingTx = false;
                    flushC0F();
                }
            }
            return;
        }

        if (event.getType() == EventType.RECEIVE) {
            Packet<?> pkt = event.getPacket();

            if (pkt instanceof S08PacketPlayerPosLook) {
                desynced = false;
                flushC0F();
                holdingTx = false;
                setTimerSpeed(1.0F);
                timerBalance = MAX_TIMER_BALANCE;
            }

            if (pkt instanceof S07PacketRespawn || pkt instanceof S01PacketJoinGame) {
                flushC0F();
                setTimerSpeed(1.0F);
                rst();
            }

            if (pkt instanceof S06PacketUpdateHealth) {
                if (((S06PacketUpdateHealth) pkt).getHealth() <= 0) {
                    flushC0F();
                    setTimerSpeed(1.0F);
                    wasHealing = false;
                }
            }

            if (pkt instanceof S32PacketConfirmTransaction && p.abuseTeleportConfirm) {
                if (wasHealing && mc.thePlayer.getHealth() < health.getValue()) {
                    int extra = Math.min(3, p.txBatchSize);
                    for (int i = 0; i < extra; i++) sendSpoofedC03(p);
                }
            }
        }
    }

    private boolean canHeal(ACP p) {
        if (p.groundSpoof && onlyGround.getValue() && !mc.thePlayer.onGround) return false;
        if (mc.thePlayer.getFoodStats().getFoodLevel() < foodLevel.getValue()) return false;
        if (noAction.getValue() && (mc.thePlayer.isUsingItem() || mc.gameSettings.keyBindAttack.isKeyDown()))
            return false;
        if (mc.thePlayer.isPotionActive(Potion.regeneration)) return false;
        return true;
    }

    private void flushC0F() {
        if (heldC0F.isEmpty()) return;
        if (mc.getNetHandler() == null) { heldC0F.clear(); return; }
        releasing = true;
        try {
            Packet<?> pkt;
            while ((pkt = heldC0F.pollFirst()) != null)
                mc.getNetHandler().addToSendQueue(pkt);
        } catch (Exception ignored) { heldC0F.clear(); }
        finally { releasing = false; }
    }

    @Override
    public String[] getSuffix() { return new String[]{mode.getModeString() + " " + anticheat.getModeString()}; }
}