package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.Priority;
import myau.events.KeyEvent;
import myau.events.PlayerUpdateEvent;
import myau.module.Module;
import myau.util.PlayerUtil;
import myau.util.RandomUtil;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition;
import net.minecraft.util.AxisAlignedBB;

@ModuleInfo(category = ModuleCategory.MOVEMENT)
public class AntiVoid extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private boolean isInVoid = false;
    private boolean wasInVoid = false;
    private double[] lastSafePosition = null;
    
    // Core settings
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"BLINK"});
    public final FloatProperty distance = new FloatProperty("distance", 5.0F, 0.0F, 16.0F);
    
    // Force teleport feature (fixes hanging in air issue)
    public final BooleanProperty forceTeleport = new BooleanProperty("force-teleport", true);
    public final IntProperty teleportDelay = new IntProperty("teleport-delay", 500, 100, 2000, () -> this.forceTeleport.getValue());
    
    // Tracking for force teleport
    private long voidEntryTime = 0L;
    private boolean hasTeleported = false;

    private void resetBlink() {
        Myau.blinkManager.setBlinkState(false, BlinkModules.ANTI_VOID);
        this.lastSafePosition = null;
        this.voidEntryTime = 0L;
        this.hasTeleported = false;
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
                // Reset when no longer in void
                if (!this.isInVoid) {
                    this.resetBlink();
                }
                
                // Check if last safe position is now in void (avoid teleporting to void)
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
                
                // Entering void for the first time
                if (!this.wasInVoid && this.isInVoid && this.canUseAntiVoid()) {
                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                    if (Myau.blinkManager.setBlinkState(true, BlinkModules.ANTI_VOID)) {
                        this.lastSafePosition = new double[]{mc.thePlayer.prevPosX, mc.thePlayer.prevPosY, mc.thePlayer.prevPosZ};
                        this.voidEntryTime = System.currentTimeMillis();
                        this.hasTeleported = false;
                    }
                }
                
                // Normal teleport when distance threshold reached
                if (Myau.blinkManager.getBlinkingModule() == BlinkModules.ANTI_VOID
                        && this.lastSafePosition != null
                        && this.lastSafePosition[1] - (double) this.distance.getValue().floatValue() > mc.thePlayer.posY) {
                    
                    // Add teleport packet to queue
                    Myau.blinkManager
                            .blinkedPackets
                            .offer(
                                    new C04PacketPlayerPosition(
                                            this.lastSafePosition[0], 
                                            this.lastSafePosition[1] - RandomUtil.nextDouble(10.0, 20.0), 
                                            this.lastSafePosition[2], 
                                            false
                                    )
                            );
                    this.resetBlink();
                    this.hasTeleported = true;
                }
                
                // FORCE TELEPORT: If enabled and still in void after delay, force teleport client-side
                if (forceTeleport.getValue() 
                        && this.isInVoid 
                        && !hasTeleported 
                        && voidEntryTime > 0 
                        && this.lastSafePosition != null) {
                    
                    long timeInVoid = System.currentTimeMillis() - voidEntryTime;
                    
                    // Force teleport after delay to prevent hanging in air
                    if (timeInVoid >= teleportDelay.getValue()) {
                        // Client-side teleport to safe position
                        mc.thePlayer.setPosition(
                            this.lastSafePosition[0],
                            this.lastSafePosition[1],
                            this.lastSafePosition[2]
                        );
                        
                        // Also send the packet
                        Myau.blinkManager
                                .blinkedPackets
                                .offer(
                                        new C04PacketPlayerPosition(
                                                this.lastSafePosition[0], 
                                                this.lastSafePosition[1] - RandomUtil.nextDouble(10.0, 20.0), 
                                                this.lastSafePosition[2], 
                                                false
                                        )
                                );
                        
                        this.resetBlink();
                        this.hasTeleported = true;
                    }
                }
            }
            
            this.wasInVoid = this.isInVoid;
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
        Myau.blinkManager.setBlinkState(false, BlinkModules.ANTI_VOID);
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
