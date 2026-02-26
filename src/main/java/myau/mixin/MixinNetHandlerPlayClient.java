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
     * Wraps the entire handler in a try/catch to swallow NPEs from broken server
     * packets (e.g. Scoreboard.func_96511_d NPE at line 229 on certain servers).
     * Action 0 = create team — if team already exists, remove it first so vanilla
     * doesn't crash trying to add a duplicate.
     * Action 1 = remove team — guard against missing team to avoid NPE.
     */
    @Inject(method = "handleTeams", at = @At("HEAD"), cancellable = true)
    private void onHandleTeams(S3EPacketTeams packet, CallbackInfo ci) {
        if (packet == null) {
            ci.cancel();
            return;
        }

        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) {
                ci.cancel();
                return;
            }

            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            if (scoreboard == null) {
                ci.cancel();
                return;
            }

            int action = packet.getAction();
            String teamName = packet.getName();

            if (teamName == null || teamName.isEmpty()) {
                ci.cancel();
                return;
            }

            if (action == 0) {
                // Create team — remove existing first to prevent duplicate crash
                ScorePlayerTeam existing = scoreboard.getTeam(teamName);
                if (existing != null) {
                    scoreboard.removeTeam(existing);
                }
            } else if (action == 1) {
                // Remove team — if it doesn't exist, nothing to do, cancel to avoid NPE
                ScorePlayerTeam existing = scoreboard.getTeam(teamName);
                if (existing == null) {
                    ci.cancel();
                    return;
                }
            }
            // Actions 2 (update), 3 (add players), 4 (remove players) — let vanilla handle
        } catch (Exception e) {
            // Swallow any NPE or other exception from malformed packets
            ci.cancel();
        }
    }
}
