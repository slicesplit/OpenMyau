package myau.mixin;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.GenericFutureListener;
import myau.Myau;
import myau.event.EventManager;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.*;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Future;

@SideOnly(Side.CLIENT)
@Mixin(value = {NetworkManager.class}, priority = 9999)
public abstract class MixinNetworkManager {

    // ────────────────────────────────────────────────────────────────────────
    //  INCOMING PACKET CLASSIFICATION
    // ────────────────────────────────────────────────────────────────────────

    @Unique
    private static boolean myau$isCriticalIncoming(Packet<?> packet) {
        return packet instanceof S08PacketPlayerPosLook
            || packet instanceof S00PacketKeepAlive
            || packet instanceof S01PacketJoinGame
            || packet instanceof S07PacketRespawn
            || packet instanceof S40PacketDisconnect;
    }

    @Unique
    private static boolean myau$isProtectedIncoming(Packet<?> packet) {
        return packet instanceof S12PacketEntityVelocity
            || packet instanceof S27PacketExplosion;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  OUTGOING PACKET CLASSIFICATION
    //
    //  CRITICAL: Bypass event system AND managers. No module ever sees these.
    //            Only packets where interception would cause kicks/timeouts
    //            and no module should ever need to touch them.
    //
    //  MANAGER-PROTECTED: Goes through event system (modules can see/cancel)
    //            but never touched by blink/lag/playerState managers.
    //            - C01 Chat: command system intercepts via PacketEvent
    //            - C0D CloseWindow: RemoteShop cancels to keep windows cached
    //            - C0E ClickWindow: inventory modules may need to intercept
    //
    //  NORMAL: Everything else flows through events then managers.
    // ────────────────────────────────────────────────────────────────────────

    @Unique
    private static boolean myau$isCriticalOutgoing(Packet<?> packet) {
        return packet instanceof C00PacketKeepAlive
            || packet instanceof C16PacketClientStatus
            || packet instanceof C15PacketClientSettings
            || packet instanceof C19PacketResourcePackStatus
            || packet instanceof C17PacketCustomPayload
            || packet instanceof C14PacketTabComplete;
    }

    @Unique
    private static boolean myau$isManagerProtectedOutgoing(Packet<?> packet) {
        return packet instanceof C01PacketChatMessage
            || packet instanceof C0DPacketCloseWindow
            || packet instanceof C0EPacketClickWindow;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  INCOMING HANDLER
    // ────────────────────────────────────────────────────────────────────────

    @Inject(
            method = {"channelRead0*"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void channelRead0(ChannelHandlerContext channelHandlerContext, Packet<?> packet, CallbackInfo callbackInfo) {
        if (packet.getClass().getName().startsWith("net.minecraft.network.play.client")) {
            return;
        }

        if (myau$isCriticalIncoming(packet)) {
            try {
                PacketEvent event = new PacketEvent(EventType.RECEIVE, packet);
                EventManager.call(event);
            } catch (Exception ignored) {}
            return;
        }

        if (myau$isProtectedIncoming(packet)) {
            try {
                PacketEvent event = new PacketEvent(EventType.RECEIVE, packet);
                EventManager.call(event);
                if (event.isCancelled()) {
                    callbackInfo.cancel();
                }
            } catch (Exception ignored) {}
            return;
        }

        if (Myau.delayManager != null) {
            try {
                if (Myau.delayManager.shouldDelay((Packet<INetHandlerPlayClient>) packet)) {
                    callbackInfo.cancel();
                    return;
                }
            } catch (Exception ignored) {}
        }

        try {
            PacketEvent event = new PacketEvent(EventType.RECEIVE, packet);
            EventManager.call(event);
            if (event.isCancelled()) {
                callbackInfo.cancel();
            }
        } catch (Exception ignored) {}
    }

    // ────────────────────────────────────────────────────────────────────────
    //  OUTGOING HANDLER (primary)
    // ────────────────────────────────────────────────────────────────────────

    @Inject(
            method = {"sendPacket(Lnet/minecraft/network/Packet;)V"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void sendPacket(Packet<?> packet, CallbackInfo callbackInfo) {
        if (packet.getClass().getName().startsWith("net.minecraft.network.play.server")) {
            return;
        }

        if (myau$isCriticalOutgoing(packet)) {
            return;
        }

        try {
            PacketEvent event = new PacketEvent(EventType.SEND, packet);
            EventManager.call(event);
            if (event.isCancelled()) {
                callbackInfo.cancel();
                return;
            }
        } catch (Exception ignored) {}

        if (myau$isManagerProtectedOutgoing(packet)) {
            return;
        }

        if (Myau.playerStateManager != null && Myau.blinkManager != null && Myau.lagManager != null) {
            try {
                if (!Myau.lagManager.isFlushing()) {
                    Myau.playerStateManager.handlePacket(packet);

                    if (Myau.blinkManager.isBlinking()) {
                        if (Myau.blinkManager.offerPacket(packet)) {
                            callbackInfo.cancel();
                            return;
                        }
                    }

                    if (Myau.lagManager.handlePacket(packet)) {
                        callbackInfo.cancel();
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  OUTGOING HANDLER (with future listener)
    // ────────────────────────────────────────────────────────────────────────

    @Inject(
            method = {"sendPacket(Lnet/minecraft/network/Packet;Lio/netty/util/concurrent/GenericFutureListener;[Lio/netty/util/concurrent/GenericFutureListener;)V"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void sendPacket2(
            Packet<?> packet,
            GenericFutureListener<? extends Future<? super Void>> genericFutureListener,
            GenericFutureListener<? extends Future<? super Void>>[] arr,
            CallbackInfo callbackInfo
    ) {
        if (packet.getClass().getName().startsWith("net.minecraft.network.play.server")) {
            return;
        }

        if (myau$isCriticalOutgoing(packet)) {
            return;
        }

        if (myau$isManagerProtectedOutgoing(packet)) {
            return;
        }

        if (Myau.playerStateManager != null && Myau.blinkManager != null && Myau.lagManager != null) {
            try {
                if (!Myau.lagManager.isFlushing()) {
                    Myau.playerStateManager.handlePacket(packet);

                    if (Myau.blinkManager.isBlinking()) {
                        if (Myau.blinkManager.offerPacket(packet)) {
                            callbackInfo.cancel();
                            return;
                        }
                    }

                    if (Myau.lagManager.handlePacket(packet)) {
                        callbackInfo.cancel();
                    }
                }
            } catch (Exception ignored) {}
        }
    }
}