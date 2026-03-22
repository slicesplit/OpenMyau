package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class JumpReset extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final IntProperty chance = new IntProperty("Chance", 100, 1, 100);
    public final BooleanProperty waterCheck = new BooleanProperty("WaterCheck", true);

    private boolean pendingJump = false;
    private boolean wasOnGround = false;
    // How many ticks after landing to jump (0 = same tick as landing, 1-2 = slight delay)
    // Pro players react within 0-2 ticks of landing, randomized
    private int jumpDelayTicks = 0;
    private int landedTick = 0;

    public JumpReset() {
        super("JumpReset", false);
    }

    @Override
    public void onEnabled() {
        this.pendingJump = false;
        this.wasOnGround = false;
        this.jumpDelayTicks = 0;
        this.landedTick = 0;
    }

    @Override
    public void onDisabled() {
        this.pendingJump = false;
        this.wasOnGround = false;
        this.jumpDelayTicks = 0;
        this.landedTick = 0;
        if (mc.thePlayer != null) {
            mc.thePlayer.movementInput.jump = false;
        }
    }

    @EventTarget(Priority.HIGH)
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.RECEIVE) return;
        if (mc.thePlayer == null) return;
        if (this.pendingJump) return;

        boolean isKB = event.getPacket() instanceof S12PacketEntityVelocity
                || event.getPacket() instanceof S27PacketExplosion;
        if (!isKB) return;

        if (event.getPacket() instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity pkt = (S12PacketEntityVelocity) event.getPacket();
            if (pkt.getEntityID() != mc.thePlayer.getEntityId()) return;

            // Only react to actual horizontal KB, not just vertical hits
            double velX = pkt.getMotionX() / 8000.0;
            double velZ = pkt.getMotionZ() / 8000.0;
            if (Math.sqrt(velX * velX + velZ * velZ) < 0.08) return;
        }

        if (this.waterCheck.getValue()
                && (mc.thePlayer.isInWater() || mc.thePlayer.isInLava())) return;

        if (Math.random() * 100 >= this.chance.getValue()) return;

        this.pendingJump = true;
        this.wasOnGround = mc.thePlayer.onGround;
        this.landedTick = 0;
        // Pro player reaction: 0-2 tick delay after landing
        // 0 ticks = frame perfect (rare but happens)
        // 1 tick = most common
        // 2 ticks = slightly slow but still good
        this.jumpDelayTicks = (int) (Math.random() * 3); // 0, 1, or 2
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null) return;
        if (!this.pendingJump) return;

        boolean onGround = mc.thePlayer.onGround;

        // Detect landing: was in air, now on ground
        boolean justLanded = !this.wasOnGround && onGround;

        if (justLanded) {
            this.landedTick = 1;
        } else if (this.landedTick > 0) {
            this.landedTick++;
        }

        this.wasOnGround = onGround;

        // Safety: give up after 20 ticks (1 second) - KB wore off naturally
        if (this.landedTick > 20) {
            this.pendingJump = false;
            this.landedTick = 0;
            return;
        }

        // Not landed yet
        if (this.landedTick == 0) return;

        // Wait for the pro-reaction delay after landing
        // landedTick starts at 1, so landedTick == 1 means same tick as landing (0 delay)
        // landedTick == 2 means 1 tick after landing, etc.
        if (this.landedTick < this.jumpDelayTicks + 1) return;

        // Execute jump via vanilla input - server sees normal jump keypress
        mc.thePlayer.movementInput.jump = true;
        this.pendingJump = false;
        this.landedTick = 0;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.chance.getValue() + "%"};
    }
}