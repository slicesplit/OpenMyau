package myau.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

public class PacketUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public static void sendPacket(Packet<?> packet) {
        mc.getNetHandler().getNetworkManager().sendPacket(packet);
    }

    public static void sendPacketNoEvent(Packet<?> packet) {
        mc.getNetHandler().getNetworkManager().sendPacket(packet, null);
    }

    /**
     * Send a packet bypassing ALL event hooks and managers (blink, lag, playerState).
     * Used by Backtrack to flush queued packets — they must not be re-processed.
     * Writes directly to the Netty channel, bypassing NetworkManager.sendPacket entirely.
     */
    public static void sendPacketSafe(Packet<?> packet) {
        try {
            NetworkManager nm = mc.getNetHandler().getNetworkManager();
            if (nm == null || !nm.isChannelOpen()) return;
            // Write directly to the channel — skips all NetworkManager injection points
            nm.channel().writeAndFlush(packet);
        } catch (Exception ignored) {}
    }
}
