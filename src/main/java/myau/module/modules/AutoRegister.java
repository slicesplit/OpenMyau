package myau.module.modules;

import myau.Myau;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.module.ModuleInfo;
import myau.property.properties.BooleanProperty;
import myau.property.properties.TextProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S45PacketTitle;
import net.minecraft.util.IChatComponent;

import java.util.regex.Pattern;

/**
 * AutoRegister - Sophisticated automatic registration system
 * 
 * Features:
 * - Multi-language register detection (10+ languages)
 * - ALL title packet types (TITLE, SUBTITLE, ACTIONBAR)
 * - Chat message detection with regex patterns
 * - Smart delay system (prevents spam)
 * - Duplicate registration prevention
 * - Password confirmation support
 * - Auto-login after registration
 * - Case-insensitive matching
 * - Server tracking (prevents cross-server conflicts)
 */
@ModuleInfo(category = ModuleCategory.MISC)
public class AutoRegister extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Settings
    public final TextProperty password = new TextProperty("password", "testing123");
    public final BooleanProperty autoLoginAfter = new BooleanProperty("Auto-Login After", true);
    
    // State tracking
    private boolean hasRegistered = false;
    private long registerTime = 0L;
    private long lastAttemptTime = 0L;
    private int registerAttempts = 0;
    private String lastServerIP = "";
    private int lastWorldId = 0;
    private boolean pendingLogin = false;
    
    // Timing constants
    private static final long REGISTER_DELAY = 1500; // 1.5s delay before register
    private static final long LOGIN_DELAY = 500; // 0.5s delay after register for login
    private static final long RETRY_DELAY = 5000; // 5s between retry attempts
    private static final int MAX_ATTEMPTS = 3; // Max register attempts per session
    
    // Detection patterns (case-insensitive)
    private static final Pattern[] REGISTER_PATTERNS = {
        // English
        Pattern.compile(".*\\b(register|sign.*up|create.*account)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\b/register\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\bплease.*register\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\bnot.*registered\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\bmust.*register\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\bneed.*to.*register\\b.*", Pattern.CASE_INSENSITIVE),
        
        // Russian
        Pattern.compile(".*\\b(регистрация|зарегистрироваться|зарегистрируйтесь)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\bвы.*не.*зарегистрированы\\b.*", Pattern.CASE_INSENSITIVE),
        
        // Polish
        Pattern.compile(".*\\b(rejestracja|zarejestruj|załóż.*konto)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\bnie.*jesteś.*zarejestrowany\\b.*", Pattern.CASE_INSENSITIVE),
        
        // Portuguese
        Pattern.compile(".*\\b(registrar|cadastrar|criar.*conta)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\bnão.*está.*registrado\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\bvocê.*não.*está.*cadastrado\\b.*", Pattern.CASE_INSENSITIVE),
        
        // Spanish
        Pattern.compile(".*\\b(registrarse|registrar|crear.*cuenta)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\bno.*estás.*registrado\\b.*", Pattern.CASE_INSENSITIVE),
        
        // French
        Pattern.compile(".*\\b(inscription|s'inscrire|créer.*compte)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\bvous.*n'êtes.*pas.*inscrit\\b.*", Pattern.CASE_INSENSITIVE),
        
        // German
        Pattern.compile(".*\\b(registrierung|registrieren|konto.*erstellen)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\bdu.*bist.*nicht.*registriert\\b.*", Pattern.CASE_INSENSITIVE),
        
        // Italian
        Pattern.compile(".*\\b(registrazione|registrati|crea.*account)\\b.*", Pattern.CASE_INSENSITIVE),
        
        // Turkish
        Pattern.compile(".*\\b(kayıt.*ol|kayıt.*olun)\\b.*", Pattern.CASE_INSENSITIVE),
        
        // Dutch
        Pattern.compile(".*\\b(registreren|aanmelden|account.*maken)\\b.*", Pattern.CASE_INSENSITIVE),
    };
    
    // Password/confirmation keyword patterns
    private static final Pattern[] PASSWORD_CONFIRM_PATTERNS = {
        Pattern.compile(".*\\b(confirm|confirmation|repeat)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\b(подтвердите|повторите)\\b.*", Pattern.CASE_INSENSITIVE), // Russian
        Pattern.compile(".*\\b(potwierdź|powtórz)\\b.*", Pattern.CASE_INSENSITIVE), // Polish
        Pattern.compile(".*\\b(confirme|repita)\\b.*", Pattern.CASE_INSENSITIVE), // Portuguese
        Pattern.compile(".*\\b(confirmar|repetir)\\b.*", Pattern.CASE_INSENSITIVE), // Spanish
        Pattern.compile(".*\\b(confirmez|répétez)\\b.*", Pattern.CASE_INSENSITIVE), // French
        Pattern.compile(".*\\b(bestätigen|wiederholen)\\b.*", Pattern.CASE_INSENSITIVE), // German
    };
    
    // Success detection patterns
    private static final Pattern[] SUCCESS_PATTERNS = {
        Pattern.compile(".*\\b(successfully.*registered|registration.*successful)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\b(успешно.*зарегистрирован|регистрация.*успешна)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\b(pomyślnie.*zarejestrowano)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\b(registrado.*com.*sucesso|cadastro.*realizado)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\b(registrado.*exitosamente)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\b(inscription.*réussie)\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\b(erfolgreich.*registriert)\\b.*", Pattern.CASE_INSENSITIVE),
    };
    
    public AutoRegister() {
        super("AutoRegister", false);
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
        hasRegistered = false;
        registerTime = 0L;
        lastAttemptTime = 0L;
        registerAttempts = 0;
        lastServerIP = "";
        lastWorldId = 0;
        pendingLogin = false;
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
            ChatUtil.sendFormatted("§a[AutoRegister] §fServer changed - Reset registration state");
        }
        
        // Detect world change
        if (mc.theWorld != null) {
            int currentWorldId = mc.theWorld.hashCode();
            if (lastWorldId != 0 && currentWorldId != lastWorldId) {
                // Don't reset hasRegistered flag - server remembers registration
                ChatUtil.sendFormatted("§a[AutoRegister] §fWorld changed - Registration state preserved");
            }
            lastWorldId = currentWorldId;
        }
        
        // Execute pending registration
        if (registerTime > 0L && System.currentTimeMillis() - registerTime >= REGISTER_DELAY && !hasRegistered) {
            executeRegister();
        }
        
        // Execute pending login after registration
        if (pendingLogin && System.currentTimeMillis() - lastAttemptTime >= LOGIN_DELAY) {
            executeLoginAfterRegister();
        }
    }
    
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.RECEIVE || mc.thePlayer == null || !this.isEnabled()) {
            return;
        }
        
        // Check for success messages
        String text = null;
        
        if (event.getPacket() instanceof S45PacketTitle) {
            S45PacketTitle packet = (S45PacketTitle) event.getPacket();
            if (packet.getMessage() != null) {
                text = packet.getMessage().getUnformattedText().toLowerCase();
            }
        } else if (event.getPacket() instanceof S02PacketChat) {
            S02PacketChat packet = (S02PacketChat) event.getPacket();
            text = packet.getChatComponent().getUnformattedText().toLowerCase();
        }
        
        if (text != null) {
            // Check for success
            if (detectsSuccess(text)) {
                hasRegistered = true;
                registerTime = 0L;
                ChatUtil.sendFormatted("§a[AutoRegister] §fRegistration confirmed successful!");
                
                // Trigger auto-login
                if (autoLoginAfter.getValue()) {
                    pendingLogin = true;
                    lastAttemptTime = System.currentTimeMillis();
                }
                return;
            }
            
            // Already registered this session
            if (hasRegistered) {
                return;
            }
            
            // Too many attempts
            if (registerAttempts >= MAX_ATTEMPTS) {
                return;
            }
            
            // Cooldown between attempts
            if (System.currentTimeMillis() - lastAttemptTime < RETRY_DELAY) {
                return;
            }
            
            // Check for registration prompt
            if (detectsRegister(text)) {
                triggerRegister(event.getPacket() instanceof S45PacketTitle ? "TITLE packet" : "CHAT message");
            }
        }
    }
    
    /**
     * Sophisticated registration detection using multiple patterns
     */
    private boolean detectsRegister(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        // Check register patterns
        for (Pattern pattern : REGISTER_PATTERNS) {
            if (pattern.matcher(text).matches()) {
                return true;
            }
        }
        
        // Also detect password confirmation prompts
        for (Pattern pattern : PASSWORD_CONFIRM_PATTERNS) {
            if (pattern.matcher(text).matches()) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Detect successful registration
     */
    private boolean detectsSuccess(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        for (Pattern pattern : SUCCESS_PATTERNS) {
            if (pattern.matcher(text).matches()) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Trigger registration sequence
     */
    private void triggerRegister(String source) {
        registerTime = System.currentTimeMillis();
        ChatUtil.sendFormatted("§a[AutoRegister] §fDetected from §e" + source + "§f - Registering...");
    }
    
    /**
     * Execute the actual registration
     */
    private void executeRegister() {
        if (mc.thePlayer == null || password.getValue().isEmpty()) {
            registerTime = 0L;
            return;
        }
        
        registerAttempts++;
        lastAttemptTime = System.currentTimeMillis();
        
        // Most servers use: /register <password> <password>
        String registerCommand = "/register " + password.getValue() + " " + password.getValue();
        mc.thePlayer.sendChatMessage(registerCommand);
        
        ChatUtil.sendFormatted("§a[AutoRegister] §fAttempt §e#" + registerAttempts + "§f - Sent register command");
        
        // Don't mark as registered yet - wait for success confirmation
        registerTime = 0L;
    }
    
    /**
     * Execute login after successful registration
     */
    private void executeLoginAfterRegister() {
        if (mc.thePlayer == null || password.getValue().isEmpty()) {
            pendingLogin = false;
            return;
        }
        
        String loginCommand = "/login " + password.getValue();
        mc.thePlayer.sendChatMessage(loginCommand);
        
        ChatUtil.sendFormatted("§a[AutoRegister] §fAuto-login sent after registration");
        pendingLogin = false;
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
