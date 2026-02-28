package myau.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;

public class PacketUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public static void sendPacket(Packet<?> packet) {
        mc.getNetHandler().getNetworkManager().sendPacket(packet);
    }

    public static void sendPacketNoEvent(Packet<?> packet) {
        mc.getNetHandler().getNetworkManager().sendPacket(packet, null);
    }

    /**
     * Send an outgoing packet bypassing ALL event hooks and managers (blink, lag, playerState).
     * Writes directly to the Netty channel, bypassing NetworkManager.sendPacket entirely.
     */
    public static void sendPacketSafe(Packet<?> packet) {
        try {
            NetworkManager nm = mc.getNetHandler().getNetworkManager();
            if (nm == null || !nm.isChannelOpen()) return;
            nm.channel().writeAndFlush(packet);
        } catch (Exception ignored) {}
    }

    /**
     * Process an incoming server packet on the client — correct direction for flushing
     * Backtrack's queued received packets. Calls packet.processPacket(netHandler) directly,
     * bypassing the network stack entirely so it doesn't fire events or go back to the server.
     */
    @SuppressWarnings("unchecked")
    public static void processIncomingPacket(Packet<?> packet) {
        try {
            if (mc.getNetHandler() == null) return;
            ((Packet<INetHandlerPlayClient>) packet).processPacket(mc.getNetHandler());
        } catch (Exception ignored) {}
    }
}
