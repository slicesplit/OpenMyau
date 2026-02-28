package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.KeyEvent;
import myau.events.PacketEvent;
import myau.events.PlayerUpdateEvent;
import myau.module.Module;
import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.util.PacketUtil;
import myau.util.PlayerUtil;
import myau.util.RandomUtil;
import myau.util.PredictionEngine;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.util.AxisAlignedBB;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

@ModuleInfo(category = ModuleCategory.MOVEMENT)
public class AntiVoid extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private boolean isInVoid = false;
    private boolean wasInVoid = false;
    private double[] lastSafePosition = null;
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"BLINK"});
    public final FloatProperty distance = new FloatProperty("distance", 5.0F, 0.0F, 16.0F);

    // ==================== EMBEDDED OLD BLINK LOGIC ====================
    // Internal blink system (independent from main BlinkManager)
    private boolean internalBlinking = false;
    private Deque<Packet<?>> internalBlinkedPackets = new ConcurrentLinkedDeque<>();

    private boolean offerInternalPacket(Packet<?> packet) {
        if (!internalBlinking || packet instanceof C00PacketKeepAlive || packet instanceof C01PacketChatMessage) {
            return false;
        } else if (this.internalBlinkedPackets.isEmpty() && packet instanceof C0FPacketConfirmTransaction) {
            return false;
        } else {
            this.internalBlinkedPackets.offer(packet);
            return true;
        }
    }

    private void setInternalBlinkState(boolean state) {
        if (state) {
            this.internalBlinking = true;
        } else {
            this.internalBlinking = false;
            if (mc.getNetHandler() != null && !this.internalBlinkedPackets.isEmpty()) {
                for (Packet<?> blinkedPacket : internalBlinkedPackets) {
                    // Use sendPacketSafe (direct Netty write) so these bypass FakeLag's
                    // queue entirely — AntiVoid's teleport packets must arrive at the
                    // server immediately, not get held behind lag packets.
                    PacketUtil.sendPacketSafe(blinkedPacket);
                }
            }
            this.internalBlinkedPackets.clear();
        }
    }

    private void resetBlink() {
        setInternalBlinkState(false);
        this.lastSafePosition = null;
    }

    private boolean canUseAntiVoid() {
        LongJump longJump = (LongJump) Myau.moduleManager.modules.get(LongJump.class);
        return !longJump.isJumping();
    }

    public AntiVoid() {
        super("AntiVoid", false);
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdate(PlayerUpdateEvent event) {
        if (this.isEnabled()) {
            this.isInVoid = !mc.thePlayer.capabilities.allowFlying && PlayerUtil.isInWater();
            if (this.mode.getValue() == 0) {
                if (!this.isInVoid) {
                    this.resetBlink();
                }
                if (this.lastSafePosition != null) {
                    float subWidth = mc.thePlayer.width / 2.0F;
                    float height = mc.thePlayer.height;
                    if (PlayerUtil.checkInWater(
                            new AxisAlignedBB(
                                    this.lastSafePosition[0] - (double) subWidth,
                                    this.lastSafePosition[1],
                                    this.lastSafePosition[2] - (double) subWidth,
                                    this.lastSafePosition[0] + (double) subWidth,
                                    this.lastSafePosition[1] + (double) height,
                                    this.lastSafePosition[2] + (double) subWidth
                            )
                    )) {
                        this.resetBlink();
                    }
                }
                if (!this.wasInVoid && this.isInVoid && this.canUseAntiVoid()) {
                    // PREDICTION ENGINE: Find PERFECT safe block position
                    // Predicts where player will land, ensures it's on solid block
                    PredictionEngine.Vec3D predictedPos = PredictionEngine.predictPlayerPosition(mc.thePlayer, 3);
                    
                    // Find nearest solid block below predicted position
                    double safeY = PredictionEngine.findSafeYPosition(
                        mc.theWorld, 
                        predictedPos.x, 
                        predictedPos.y, 
                        predictedPos.z
                    );
                    
                    // Use internal blink instead of main BlinkManager
                    setInternalBlinkState(true);
                    
                    // Save position that's guaranteed to be on a block
                    if (safeY != -1) {
                        this.lastSafePosition = new double[]{predictedPos.x, safeY, predictedPos.z};
                    } else {
                        // Fallback to previous position if no block found
                        this.lastSafePosition = new double[]{mc.thePlayer.prevPosX, mc.thePlayer.prevPosY, mc.thePlayer.prevPosZ};
                    }
                }
                if (this.internalBlinking
                        && this.lastSafePosition != null
                        && this.lastSafePosition[1] - (double) this.distance.getValue().floatValue() > mc.thePlayer.posY) {
                    // Add teleport packet to internal queue
                    this.internalBlinkedPackets.addFirst(
                            new C04PacketPlayerPosition(
                                    this.lastSafePosition[0], this.lastSafePosition[1] - RandomUtil.nextDouble(10.0, 20.0), this.lastSafePosition[2], false
                            )
                    );
                    this.resetBlink();
                }
            }
            this.wasInVoid = this.isInVoid;
        }
    }

    @EventTarget(Priority.HIGH)
    public void onPacket(PacketEvent event) {
        if (this.isEnabled() && event.getType() == EventType.SEND) {
            // Intercept outgoing packets and queue them in internal blink
            if (this.internalBlinking) {
                if (offerInternalPacket((Packet<?>) event.getPacket())) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventTarget
    public void onKey(KeyEvent event) {
        if (event.getKey() == mc.gameSettings.keyBindUseItem.getKeyCode()) {
            ItemStack currentItem = mc.thePlayer.inventory.getCurrentItem();
            if (currentItem != null && currentItem.getItem() instanceof ItemEnderPearl) {
                this.resetBlink();
            }
        }
    }

    @Override
    public void onEnabled() {
        this.isInVoid = false;
        this.wasInVoid = false;
        this.resetBlink();
    }

    @Override
    public void onDisabled() {
        setInternalBlinkState(false);
    }

    @Override
    public void verifyValue(String mode) {
        if (this.isEnabled()) {
            this.onDisabled();
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}
