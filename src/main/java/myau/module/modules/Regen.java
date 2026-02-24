package myau.module.modules;

import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.management.ServerGroundTracker;
import myau.module.Module;
import myau.module.ModuleInfo;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.potion.Potion;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class Regen extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // Settings
    public final ModeProperty mode   = new ModeProperty("Mode", 2, new String[]{"Vanilla", "Spartan", "Grim"});
    public final IntProperty   speed = new IntProperty("Speed", 3, 1, 20,  () -> mode.getValue() == 0);
    public final IntProperty   delay = new IntProperty("Delay", 80, 0, 1000, () -> mode.getValue() != 2);

    // Grim mode settings — exposed so advanced users can tune
    public final IntProperty   grimDelay       = new IntProperty("Grim-Delay", 80, 40, 500, () -> mode.getValue() == 2);
    public final IntProperty   grimPacketsPerWindow = new IntProperty("Grim-Packets", 1, 1, 2, () -> mode.getValue() == 2);

    public final FloatProperty health = new FloatProperty("Health", 15f, 0f, 20f);
    public final FloatProperty food   = new FloatProperty("Food", 14f, 0f, 20f);
    public final BooleanProperty noAir        = new BooleanProperty("NoAir", true);
    public final BooleanProperty potionEffect = new BooleanProperty("PotionEffect", true);

    // Internal state
    private long lastPacketTime  = 0L;
    // Grim mode: track transaction windows
    private boolean transactionReceived = false;
    private int pendingGrimPackets = 0;

    public Regen() {
        super("Regen", false);
    }

    @Override
    public void onEnabled() {
        lastPacketTime = 0L;
        transactionReceived = false;
        pendingGrimPackets = 0;
    }

    @Override
    public void onDisabled() {
        transactionReceived = false;
        pendingGrimPackets = 0;
    }

    // ── Grim mode: listen for incoming transaction packets to know when we're
    //    inside a safe window. S32PacketConfirmTransaction is the 1.8 confirm-
    //    transaction packet Grim uses as its ping anchor for the timer check.
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mode.getValue() != 2) return;
        if (event.getType() != EventType.RECEIVE) return;

        Packet<?> pkt = event.getPacket();
        // S32PacketConfirmTransaction = Grim's transaction / ping packet (0x32 in 1.8)
        if (pkt instanceof S32PacketConfirmTransaction) {
            transactionReceived = true;
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        // Common guards
        if (mc.thePlayer.getHealth() >= health.getValue()) return;
        if (mc.thePlayer.getFoodStats().getFoodLevel() < food.getValue()) return;
        if (noAir.getValue() && !mc.thePlayer.onGround) return;
        if (potionEffect.getValue() && mc.thePlayer.isPotionActive(Potion.regeneration)) return;

        int m = mode.getValue();

        if (m == 0) {
            // ── Vanilla: burst of extra C03s — only use on non-Grim servers
            if (System.currentTimeMillis() - lastPacketTime < delay.getValue()) return;
            doVanilla();
            lastPacketTime = System.currentTimeMillis();

        } else if (m == 1) {
            // ── Spartan: classic alternating-ground burst
            if (System.currentTimeMillis() - lastPacketTime < delay.getValue()) return;
            doSpartan();
            lastPacketTime = System.currentTimeMillis();

        } else {
            // ── Grim: one packet per transaction window, real-time gated
            //    Grim's TimerA anchors every C03 to real time (+50ms each).
            //    We must not exceed one extra C03 per ~50ms real-time budget.
            //    We gate additionally on grimDelay ms to add human-like variance
            //    and to stay comfortably inside the timer drift (default 120ms).
            if (System.currentTimeMillis() - lastPacketTime < grimDelay.getValue()) return;

            if (transactionReceived) {
                // Fire exactly grimPacketsPerWindow extra C03s inside this window.
                // Default is 1 — safe on any ping. Set to 2 only on <30ms ping.
                int count = grimPacketsPerWindow.getValue();
                boolean ground = ServerGroundTracker.serverOnGround;
                for (int i = 0; i < count; i++) {
                    PacketUtil.sendPacketNoEvent(new C03PacketPlayer(ground));
                }
                transactionReceived = false;
                lastPacketTime = System.currentTimeMillis();
            }
        }
    }

    /**
     * Vanilla mode: send <speed> extra C03s per activation.
     * Trips Grim TimerA — use only on non-Grim servers.
     */
    private void doVanilla() {
        boolean ground = ServerGroundTracker.serverOnGround;
        int count = speed.getValue();
        for (int i = 0; i < count; i++) {
            PacketUtil.sendPacketNoEvent(new C03PacketPlayer(ground));
        }
    }

    /**
     * Spartan mode: 9 alternating ground-state packets.
     * Trips Grim TimerA — use on Spartan/NCP servers only.
     */
    private void doSpartan() {
        for (int i = 0; i < 9; i++) {
            PacketUtil.sendPacketNoEvent(new C03PacketPlayer(i % 2 == 0));
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString()};
    }
}
