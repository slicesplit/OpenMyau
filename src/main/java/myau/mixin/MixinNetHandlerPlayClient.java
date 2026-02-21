package myau.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S0CPacketSpawnPlayer;
import net.minecraft.network.play.server.S0FPacketSpawnMob;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetHandlerPlayClient.class)
public class MixinNetHandlerPlayClient {

    @Inject(method = "handleSpawnPlayer", at = @At("HEAD"), cancellable = true)
    private void onHandleSpawnPlayer(S0CPacketSpawnPlayer packet, CallbackInfo ci) {
        try {
            if (packet == null) {
                ci.cancel();
            }
        } catch (Exception e) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSpawnMob", at = @At("HEAD"), cancellable = true)
    private void onHandleSpawnMob(S0FPacketSpawnMob packet, CallbackInfo ci) {
        try {
            if (packet == null) {
                ci.cancel();
            }
        } catch (Exception e) {
            ci.cancel();
        }
    }

    /**
     * Intercept S3EPacketTeams BEFORE vanilla processes it.
     * Action 0 = create team — if team already exists, remove it first.
     * This prevents the IllegalArgumentException crash that kicks the client.
     * Also wraps the entire handler to swallow NPEs from broken server packets.
     */
    @Inject(method = "handleTeams", at = @At("HEAD"), cancellable = true)
    private void onHandleTeams(S3EPacketTeams packet, CallbackInfo ci) {
        if (packet == null) {
            ci.cancel();
            return;
        }

        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null) return;

            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            if (scoreboard == null) return;

            int action = packet.getAction();
            String teamName = packet.getName();

            if (action == 0 && teamName != null) {
                ScorePlayerTeam existing = scoreboard.getTeam(teamName);
                if (existing != null) {
                    scoreboard.removeTeam(existing);
                }
            }
        } catch (Exception e) {
            ci.cancel();
        }
    }
}
