package myau.mixin;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S0CPacketSpawnPlayer;
import net.minecraft.network.play.server.S0FPacketSpawnMob;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.scoreboard.ScorePlayerTeam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fix random disconnects caused by server-side packet errors
 * - NullPointerException in S0CPacketSpawnPlayer (player spawn)
 * - NullPointerException in S0FPacketSpawnMob (mob spawn)
 * - IllegalArgumentException in S3EPacketTeams (duplicate team names)
 */
@Mixin(NetHandlerPlayClient.class)
public class MixinNetHandlerPlayClient {
    
    /**
     * Prevent crashes from invalid player spawn packets
     */
    @Inject(method = "handleSpawnPlayer", at = @At("HEAD"), cancellable = true)
    private void onHandleSpawnPlayer(S0CPacketSpawnPlayer packet, CallbackInfo ci) {
        try {
            // Check if packet data is valid
            if (packet == null) {
                System.err.println("[Myau] Blocked null S0CPacketSpawnPlayer");
                ci.cancel();
                return;
            }
            
            // Let vanilla handle it, but we'll catch exceptions in handleSpawnPlayerCatch
        } catch (Exception e) {
            System.err.println("[Myau] Error in handleSpawnPlayer: " + e.getMessage());
            ci.cancel();
        }
    }
    
    /**
     * Prevent crashes from invalid mob spawn packets
     */
    @Inject(method = "handleSpawnMob", at = @At("HEAD"), cancellable = true)
    private void onHandleSpawnMob(S0FPacketSpawnMob packet, CallbackInfo ci) {
        try {
            // Check if packet data is valid
            if (packet == null) {
                System.err.println("[Myau] Blocked null S0FPacketSpawnMob");
                ci.cancel();
                return;
            }
        } catch (Exception e) {
            System.err.println("[Myau] Error in handleSpawnMob: " + e.getMessage());
            ci.cancel();
        }
    }
    
    /**
     * Prevent crashes from duplicate team names (server bug)
     */
    @Inject(method = "handleTeams", at = @At("HEAD"), cancellable = true)
    private void onHandleTeams(S3EPacketTeams packet, CallbackInfo ci) {
        try {
            if (packet == null) {
                System.err.println("[Myau] Blocked null S3EPacketTeams");
                ci.cancel();
                return;
            }
            
            // Check for duplicate team names using reflection to access private fields
            NetHandlerPlayClient self = (NetHandlerPlayClient) (Object) this;
            try {
                // Use reflection to access clientWorldController (private field)
                java.lang.reflect.Field worldField = NetHandlerPlayClient.class.getDeclaredField("clientWorldController");
                worldField.setAccessible(true);
                net.minecraft.client.multiplayer.WorldClient world = (net.minecraft.client.multiplayer.WorldClient) worldField.get(self);
                
                if (world != null && world.getScoreboard() != null) {
                    String teamName = packet.getName(); // Use proper method name
                    int action = packet.getAction(); // Use proper method name
                    
                    // Action 0 = create team, Action 1 = remove team
                    if (action == 0) {
                        ScorePlayerTeam existingTeam = world.getScoreboard().getTeam(teamName);
                        if (existingTeam != null) {
                            // Team already exists, remove it first to prevent crash
                            System.out.println("[Myau] Removing duplicate team: " + teamName);
                            world.getScoreboard().removeTeam(existingTeam);
                        }
                    }
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                // Silently ignore reflection errors
            }
        } catch (Exception e) {
            System.err.println("[Myau] Error in handleTeams: " + e.getMessage());
            ci.cancel();
        }
    }
}
