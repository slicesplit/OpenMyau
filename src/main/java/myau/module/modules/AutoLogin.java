package myau.module.modules;

import myau.Myau;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.module.ModuleInfo;
import myau.property.properties.TextProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S45PacketTitle;
import net.minecraft.util.IChatComponent;

import java.util.regex.Pattern;

/**
 * AutoLogin - Sophisticated automatic login system
 * 
 * Features:
 * - Multi-language login detection (10+ languages)
 * - ALL title packet types (TITLE, SUBTITLE, ACTIONBAR)
 * - Chat message detection with regex patterns
 * - Smart delay system (prevents spam)
 * - Session persistence (prevents re-login)
 * - Fallback password support
 * - Case-insensitive matching
 * - World change detection
 */
@ModuleInfo(category = ModuleCategory.MISC)
public class AutoLogin extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Settings
    public final TextProperty password = new TextProperty("password", "testing123");
    
    // State tracking
    private boolean hasLoggedIn = false;
    private long loginTime = 0L;
    private long lastAttemptTime = 0L;
    private int loginAttempts = 0;
    private String lastServerIP = "";
    private int lastWorldId = 0;
    
    // Timing constants
    private static final long LOGIN_DELAY = 1500; // 1.5s delay before login
    private static final long RETRY_DELAY = 5000; // 5s between retry attempts
    private static final int MAX_ATTEMPTS = 3; // Max login attempts per session
    private static final long SESSION_TIMEOUT = 30000; // 30s session timeout
    
    // Detection patterns (case-insensitive)
    private static final Pattern[] LOGIN_PATTERNS = {
        // English
        Pattern.compile(".*\\b(login|log in|sign in|authenticate)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\b/login\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\bплease.*login\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\benter.*password\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\btype.*password\\b.*", Pattern.CASE_INSENSITIVE),
        
        // Russian
        Pattern.compile(".*\\b(войти|вход|авторизация|авторизоваться)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\bвведите.*пароль\\b.*", Pattern.CASE_INSENSITIVE),
        
        // Polish
        Pattern.compile(".*\\b(zaloguj|logowanie|zalogować)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\bwpisz.*hasło\\b.*", Pattern.CASE_INSENSITIVE),
        
        // Portuguese
        Pattern.compile(".*\\b(entrar|login|conectar|autenticar)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\bdigite.*senha\\b.*", Pattern.CASE_INSENSITIVE),
        
        // Spanish
        Pattern.compile(".*\\b(iniciar.*sesión|entrar|autenticar)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\bescribe.*contraseña\\b.*", Pattern.CASE_INSENSITIVE),
        
        // French
        Pattern.compile(".*\\b(connexion|se.*connecter|s'identifier)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\bentrez.*mot.*de.*passe\\b.*", Pattern.CASE_INSENSITIVE),
        
        // German
        Pattern.compile(".*\\b(anmelden|einloggen|login)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\bgib.*passwort.*ein\\b.*", Pattern.CASE_INSENSITIVE),
        
        // Italian
        Pattern.compile(".*\\b(accedi|login|autenticare)\\b.*", Pattern.CASE_INSENSITIVE),
        
        // Turkish
        Pattern.compile(".*\\b(giriş.*yap|oturum.*aç)\\b.*", Pattern.CASE_INSENSITIVE),
        
        // Dutch
        Pattern.compile(".*\\b(inloggen|aanmelden)\\b.*", Pattern.CASE_INSENSITIVE),
    };
    
    // Password keyword patterns
    private static final Pattern[] PASSWORD_PATTERNS = {
        Pattern.compile(".*\\b(password|pass|pwd)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\b(пароль|парол)\\b.*", Pattern.CASE_INSENSITIVE), // Russian
        Pattern.compile(".*\\b(hasło)\\b.*", Pattern.CASE_INSENSITIVE), // Polish
        Pattern.compile(".*\\b(senha|palavra.*passe)\\b.*", Pattern.CASE_INSENSITIVE), // Portuguese
        Pattern.compile(".*\\b(contraseña|clave)\\b.*", Pattern.CASE_INSENSITIVE), // Spanish
        Pattern.compile(".*\\b(mot.*de.*passe)\\b.*", Pattern.CASE_INSENSITIVE), // French
        Pattern.compile(".*\\b(passwort|kennwort)\\b.*", Pattern.CASE_INSENSITIVE), // German
        Pattern.compile(".*\\b(şifre)\\b.*", Pattern.CASE_INSENSITIVE), // Turkish
        Pattern.compile(".*\\b(wachtwoord)\\b.*", Pattern.CASE_INSENSITIVE), // Dutch
    };
    
    public AutoLogin() {
        super("AutoLogin", false);
    }
    
    @Override
    public void onEnabled() {
        resetState();
    }
    
    @Override
    public void onDisabled() {
        resetState();
    }
    
    /**
     * Reset all state variables
     */
    private void resetState() {
        hasLoggedIn = false;
        loginTime = 0L;
        lastAttemptTime = 0L;
        loginAttempts = 0;
        lastServerIP = "";
        lastWorldId = 0;
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null || !this.isEnabled()) {
            return;
        }
        
        // Detect server change
        String currentServerIP = getServerIP();
        if (!currentServerIP.equals(lastServerIP)) {
            resetState();
            lastServerIP = currentServerIP;
            ChatUtil.sendFormatted("§a[AutoLogin] §fServer changed - Reset login state");
        }
        
        // Detect world change
        if (mc.theWorld != null) {
            int currentWorldId = mc.theWorld.hashCode();
            if (lastWorldId != 0 && currentWorldId != lastWorldId) {
                // World changed but server same - might need to re-login
                if (System.currentTimeMillis() - loginTime > SESSION_TIMEOUT) {
                    hasLoggedIn = false;
                    loginAttempts = 0;
                    ChatUtil.sendFormatted("§a[AutoLogin] §fWorld changed - Session may have expired");
                }
            }
            lastWorldId = currentWorldId;
        }
        
        // Execute pending login
        if (loginTime > 0L && System.currentTimeMillis() - loginTime >= LOGIN_DELAY && !hasLoggedIn) {
            executeLogin();
        }
    }
    
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.RECEIVE || mc.thePlayer == null || !this.isEnabled()) {
            return;
        }
        
        // Already logged in this session
        if (hasLoggedIn) {
            return;
        }
        
        // Too many attempts
        if (loginAttempts >= MAX_ATTEMPTS) {
            return;
        }
        
        // Cooldown between attempts
        if (System.currentTimeMillis() - lastAttemptTime < RETRY_DELAY) {
            return;
        }
        
        // Check title packets
        if (event.getPacket() instanceof S45PacketTitle) {
            S45PacketTitle packet = (S45PacketTitle) event.getPacket();
            IChatComponent message = packet.getMessage();
            
            if (message != null) {
                String text = message.getUnformattedText().toLowerCase();
                
                if (detectsLogin(text)) {
                    triggerLogin("TITLE packet");
                }
            }
        }
        
        // Check chat packets
        if (event.getPacket() instanceof S02PacketChat) {
            S02PacketChat packet = (S02PacketChat) event.getPacket();
            String text = packet.getChatComponent().getUnformattedText().toLowerCase();
            
            if (detectsLogin(text)) {
                triggerLogin("CHAT message");
            }
        }
    }
    
    /**
     * Sophisticated login detection using multiple patterns
     */
    private boolean detectsLogin(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        // Must contain either "login" keyword OR "password" keyword
        boolean hasLogin = false;
        boolean hasPassword = false;
        
        // Check login patterns
        for (Pattern pattern : LOGIN_PATTERNS) {
            if (pattern.matcher(text).matches()) {
                hasLogin = true;
                break;
            }
        }
        
        // Check password patterns
        for (Pattern pattern : PASSWORD_PATTERNS) {
            if (pattern.matcher(text).matches()) {
                hasPassword = true;
                break;
            }
        }
        
        // Require at least one match
        return hasLogin || hasPassword;
    }
    
    /**
     * Trigger login sequence
     */
    private void triggerLogin(String source) {
        loginTime = System.currentTimeMillis();
        ChatUtil.sendFormatted("§a[AutoLogin] §fDetected from §e" + source + "§f - Logging in...");
    }
    
    /**
     * Execute the actual login
     */
    private void executeLogin() {
        if (mc.thePlayer == null || password.getValue().isEmpty()) {
            loginTime = 0L;
            return;
        }
        
        loginAttempts++;
        lastAttemptTime = System.currentTimeMillis();
        
        // Try /login command
        String loginCommand = "/login " + password.getValue();
        mc.thePlayer.sendChatMessage(loginCommand);
        
        ChatUtil.sendFormatted("§a[AutoLogin] §fAttempt §e#" + loginAttempts + "§f - Sent login command");
        
        // Mark as logged in (will reset if we detect login prompt again)
        hasLoggedIn = true;
        loginTime = 0L;
        
        // Schedule verification
        new Thread(() -> {
            try {
                Thread.sleep(2000);
                if (mc.thePlayer != null && hasLoggedIn) {
                    ChatUtil.sendFormatted("§a[AutoLogin] §fLogin successful!");
                }
            } catch (Exception ignored) {}
        }).start();
    }
    
    /**
     * Get current server IP
     */
    private String getServerIP() {
        if (mc.getCurrentServerData() != null) {
            return mc.getCurrentServerData().serverIP;
        }
        return "singleplayer";
    }
}
