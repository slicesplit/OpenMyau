package myau.module.modules;

import myau.Myau;
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

    // ── Settings ──────────────────────────────────────────────────────────────
    // Mode 0 = Vanilla (burst), Mode 1 = Spartan, Mode 2 = Grim
    public final ModeProperty mode = new ModeProperty("Mode", 2, new String[]{"Vanilla", "Spartan", "Grim"});
    public final IntProperty speed = new IntProperty("Speed", 3, 1, 20, () -> mode.getValue() == 0);
    public final IntProperty delay = new IntProperty("Delay", 80, 0, 1000, () -> mode.getValue() != 2);

    // Grim mode — single packet per transaction window, real-time gated
    // Delay: stay inside Grim's 120ms timer drift with human-like variance.
    // GroundSpoof: send C03 with onGround=true to cancel fall damage server-side
    //   (bypasses Grim's NoFall simulation check — Grim sees a legal ground touch
    //   because the position Y is already close enough from the transaction window).
    public final IntProperty grimDelay = new IntProperty("Grim-Delay", 60, 20, 300, () -> mode.getValue() == 2);
    public final BooleanProperty groundSpoof = new BooleanProperty("GroundSpoof", true, () -> mode.getValue() == 2);

    public final FloatProperty health = new FloatProperty("Health", 15f, 0f, 20f);
    public final FloatProperty food   = new FloatProperty("Food", 14f, 0f, 20f);
    public final BooleanProperty noAir        = new BooleanProperty("NoAir", false);
    public final BooleanProperty potionEffect = new BooleanProperty("PotionEffect", true);

    // ── Internal state ────────────────────────────────────────────────────────
    private long lastPacketTime = 0L;
    private boolean transactionReceived = false;
    // Tracks our last sent groundSpoof state to avoid double-sends
    private boolean lastGroundSpoofSent = false;

    public Regen() {
        super("Regen", false);
    }

    @Override
    public void onEnabled() {
        lastPacketTime = 0L;
        transactionReceived = false;
        lastGroundSpoofSent = false;
    }

    @Override
    public void onDisabled() {
        transactionReceived = false;
        lastGroundSpoofSent = false;
    }

    // ── Listen for Grim's transaction/ping packets (S32 = 0x32) ──────────────
    // Grim anchors its TimerA check to these. We fire our extra C03 immediately
    // after receiving one — this is the safest window because Grim just updated
    // its internal clock reference.
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mode.getValue() != 2) return;
        if (event.getType() != EventType.RECEIVE) return;
        Packet<?> pkt = event.getPacket();
        if (pkt instanceof S32PacketConfirmTransaction) {
            transactionReceived = true;
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        if (mc.thePlayer.getHealth() >= health.getValue()) return;
        if (mc.thePlayer.getFoodStats().getFoodLevel() < food.getValue()) return;
        if (noAir.getValue() && !mc.thePlayer.onGround) return;
        if (potionEffect.getValue() && mc.thePlayer.isPotionActive(Potion.regeneration)) return;

        int m = mode.getValue();

        if (m == 0) {
            if (System.currentTimeMillis() - lastPacketTime < delay.getValue()) return;
            doVanilla();
            lastPacketTime = System.currentTimeMillis();

        } else if (m == 1) {
            if (System.currentTimeMillis() - lastPacketTime < delay.getValue()) return;
            doSpartan();
            lastPacketTime = System.currentTimeMillis();

        } else {
            // ── Grim mode ─────────────────────────────────────────────────────
            // Gate on real-time delay so we never exceed Grim's 120ms timer budget.
            if (System.currentTimeMillis() - lastPacketTime < grimDelay.getValue()) return;

            // Skip during FakeLag — the extra C03 inside a held-burst creates an
            // impossible position jump in Grim's simulation, causing AntiKB flag.
            FakeLag fakeLag = (FakeLag) Myau.moduleManager.modules.get(FakeLag.class);
            if (fakeLag != null && fakeLag.isActive()) return;

            if (transactionReceived) {
                // Determine ground state for the extra C03:
                //   GroundSpoof=true  → always send onGround=true.
                //     Grim's NoFall check uses the onGround flag in the C03 to know
                //     if the player landed. Sending true inside a transaction window
                //     tells Grim we touched ground legitimately. Grim's simulation
                //     only rejects this if our Y position is impossibly far from a
                //     solid block — but since we just moved normally, it passes.
                //   GroundSpoof=false → mirror actual server ground state (safe for
                //     servers with strict simulation like Vulcan).
                boolean groundFlag = groundSpoof.getValue() || ServerGroundTracker.serverOnGround;

                // Use sendPacketSafe (direct Netty write) — bypasses FakeLag queue
                // and BungeeCord packet hooks, arrives as its own network frame.
                PacketUtil.sendPacketSafe(new C03PacketPlayer(groundFlag));

                lastGroundSpoofSent = groundFlag;
                transactionReceived = false;
                lastPacketTime = System.currentTimeMillis();
            }
        }
    }

    // ── Vanilla: burst of N extra C03s — trips Grim TimerA ───────────────────
    private void doVanilla() {
        boolean ground = ServerGroundTracker.serverOnGround;
        for (int i = 0; i < speed.getValue(); i++) {
            PacketUtil.sendPacketNoEvent(new C03PacketPlayer(ground));
        }
    }

    // ── Spartan: 9 alternating ground packets — Spartan/NCP only ─────────────
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
