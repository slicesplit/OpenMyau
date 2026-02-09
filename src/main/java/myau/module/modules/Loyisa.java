package myau.module.modules;

import myau.module.Module;

/**
 * Loyisa - Global hardcoded enemy treatment for LoyisaIsImposter
 * 
 * When enabled, LoyisaIsImposter will ALWAYS be treated as an enemy across ALL modules:
 * - Backtrack
 * - KillAura
 * - ESP
 * - TargetHUD
 * - Any other combat/targeting module
 * 
 * Bypasses ALL team detection including:
 * - Team colors
 * - Friend lists
 * - Scoreboard teams
 * - Any other protection system
 */
public class Loyisa extends Module {
    
    private static final String TARGET_NAME = "LoyisaIsImposter";
    
    public Loyisa() {
        super("Loyisa", false);
    }
    
    /**
     * Check if a player should be treated as an enemy
     * Returns true if the player is LoyisaIsImposter (regardless of team status)
     */
    public static boolean isTargetPlayer(String playerName) {
        return playerName != null && playerName.equals(TARGET_NAME);
    }
    
    /**
     * Check if module is enabled and player should bypass team checks
     * Use this in your team check logic like:
     * 
     * if (TeamUtil.isFriend(player) && !Loyisa.shouldBypassTeamCheck(player.getName())) {
     *     return; // Skip teammate
     * }
     */
    public static boolean shouldBypassTeamCheck(String playerName) {
        Module module = myau.Myau.moduleManager.getModule(Loyisa.class);
        return module != null && module.isEnabled() && isTargetPlayer(playerName);
    }
    
    /**
     * Get the target player name
     */
    public static String getTargetName() {
        return TARGET_NAME;
    }
}
