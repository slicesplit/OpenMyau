package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketCheck;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;

@CheckData(name = "BadPacketsA", description = "Sent duplicate slot id")
public class BadPacketsA extends Check implements PacketCheck {
    private int lastSlot = -1;

    public BadPacketsA(final GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            final int slot = new WrapperPlayClientHeldItemChange(event).getSlot();

            if (slot == lastSlot && flagAndAlert("slot=" + slot) && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }

            lastSlot = slot;
        }
    }
} , package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketCheck;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;

@CheckData(name = "BadPacketsB", description = "Ignored set rotation packet")
public class BadPacketsB extends Check implements PacketCheck {

    public BadPacketsB(final GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (isTransaction(event.getPacketType())) {
            player.pendingRotations.removeIf(data -> {
                if (player.getLastTransactionReceived() > data.getTransaction()) {
                    if (!data.isAccepted()) {
                        flagAndAlert();
                    }

                    return true;
                }

                return false;
            });
        }
    }
} , package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketCheck;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;

@CheckData(name = "BadPacketsC", description = "Interacted with self")
public class BadPacketsC extends Check implements PacketCheck {
    public BadPacketsC(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY
                && player.cameraEntity.isSelf()
                && new WrapperPlayClientInteractEntity(event).getEntityId() == player.entityID
                && flagAndAlert() && shouldModifyPackets() // Instant ban
        ) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
} , package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketCheck;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

@CheckData(name = "BadPacketsD", description = "Impossible pitch")
public class BadPacketsD extends Check implements PacketCheck {
    public BadPacketsD(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (player.packetStateData.lastPacketWasTeleport) return;

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            final float pitch = new WrapperPlayClientPlayerFlying(event).getLocation().getPitch();
            if (pitch > 90 || pitch < -90) {
                // Ban.
                if (flagAndAlert("pitch=" + pitch) && shouldModifyPackets()) {
                    // prevent other checks from using an invalid pitch
                    if (player.pitch > 90) player.pitch = 90;
                    if (player.pitch < -90) player.pitch = -90;

                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            }
        }
    }
} , package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketCheck;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

@CheckData(name = "BadPacketsE")
public class BadPacketsE extends Check implements PacketCheck {
    private int noReminderTicks;
    private final int maxNoReminderTicks = player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8) ? 20 : 19;
    private final boolean isViaPleaseStopUsingProtocolHacksOnYourServer = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2) || PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_21_2);

    public BadPacketsE(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION ||
                event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION) {
            noReminderTicks = 0;
        } else if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType()) && !player.packetStateData.lastPacketWasTeleport) {
            if (++noReminderTicks > maxNoReminderTicks) {
                flagAndAlert("ticks=" + noReminderTicks);
            }
        } else if (event.getPacketType() == PacketType.Play.Client.STEER_VEHICLE
                || (isViaPleaseStopUsingProtocolHacksOnYourServer && player.inVehicle())) {
            noReminderTicks = 0; // Exempt vehicles
        }
    }

    public void handleRespawn() {
        noReminderTicks = 0;
    }
} , package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketCheck;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;

@CheckData(name = "BadPacketsF", description = "Sent duplicate sprinting status")
public class BadPacketsF extends Check implements PacketCheck {
    public boolean lastSprinting;
    public boolean exemptNext = true; // Support 1.14+ clients starting on either true or false sprinting, we don't know

    public BadPacketsF(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) {
            WrapperPlayClientEntityAction packet = new WrapperPlayClientEntityAction(event);

            if (packet.getAction() == WrapperPlayClientEntityAction.Action.START_SPRINTING) {
                if (lastSprinting) {
                    if (exemptNext) {
                        exemptNext = false;
                        return;
                    }
                    if (flagAndAlert("state=true") && shouldModifyPackets()) {
                        event.setCancelled(true);
                        player.onPacketCancel();
                    }
                }

                lastSprinting = true;
            } else if (packet.getAction() == WrapperPlayClientEntityAction.Action.STOP_SPRINTING) {
                if (!lastSprinting) {
                    if (exemptNext) {
                        exemptNext = false;
                        return;
                    }
                    if (flagAndAlert("state=false") && shouldModifyPackets()) {
                        event.setCancelled(true);
                        player.onPacketCancel();
                    }
                }

                lastSprinting = false;
            }
        }
    }
} , package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketCheck;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;

@CheckData(name = "BadPacketsG", description = "Sent duplicate sneaking status")
public class BadPacketsG extends Check implements PacketCheck {
    private boolean lastSneaking, respawn;

    public BadPacketsG(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) {
            WrapperPlayClientEntityAction packet = new WrapperPlayClientEntityAction(event);

            if (packet.getAction() == WrapperPlayClientEntityAction.Action.START_SNEAKING) {
                // The player may send two START_SNEAKING packets if they respawned
                if (lastSneaking && !respawn) {
                    if (flagAndAlert("state=true") && shouldModifyPackets()) {
                        event.setCancelled(true);
                        player.onPacketCancel();
                    }
                } else {
                    lastSneaking = true;
                }
                respawn = false;
            } else if (packet.getAction() == WrapperPlayClientEntityAction.Action.STOP_SNEAKING) {
                if (!lastSneaking && !respawn) {
                    if (flagAndAlert("state=false") && shouldModifyPackets()) {
                        event.setCancelled(true);
                        player.onPacketCancel();
                    }
                } else {
                    lastSneaking = false;
                }
                respawn = false;
            }
        }
    }

    public void handleRespawn() {
        // Clients could potentially not send a STOP_SNEAKING packet when they die, so we need to track it
        respawn = true;
    }
}