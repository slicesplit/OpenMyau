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

import static net.minecraft.client.Minecraft.getMinecraft;

/**
 * AutoRegister - Automatically registers/logins on cracked servers
 * Detects register/login in titles, actionbar, and chat
 */
@ModuleInfo(category = ModuleCategory.MISC)
public class AutoRegister extends Module {
    
    public final TextProperty password = new TextProperty("password", "testing123");
    
    private boolean hasRegistered = false;
    private long detectionTime = 0L;
    private int lastWorldId = 0;
    private static final long DELAY = 1000; // 1 second delay before sending command
    
    public AutoRegister() {
        super("AutoRegister", false);
    }
    
    @Override
    public void onEnabled() {
        hasRegistered = false;
        detectionTime = 0L;
    }
    
    @Override
    public void onDisabled() {
        hasRegistered = false;
        detectionTime = 0L;
    }
    
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || hasRegistered || event.getType() != EventType.RECEIVE) {
            return;
        }
        
        String text = "";
        boolean detected = false;
        
        // Check S45PacketTitle (title/subtitle/actionbar) - ALL TYPES
        if (event.getPacket() instanceof S45PacketTitle) {
            S45PacketTitle packet = (S45PacketTitle) event.getPacket();
            
            // Get message from packet - works for ALL title types (TITLE, SUBTITLE, ACTIONBAR)
            IChatComponent message = packet.getMessage();
            if (message != null) {
                text = message.getUnformattedText().toLowerCase();
                
                // Enhanced detection - check for "register" AND "password" separately
                if (containsRegister(text) || containsPassword(text)) {
                    detected = true;
                    ChatUtil.sendFormatted("§a[AutoRegister] §fDetected in TITLE: §7" + text);
                }
            }
        }
        
        // Check S02PacketChat (chat messages)
        if (event.getPacket() instanceof S02PacketChat) {
            S02PacketChat packet = (S02PacketChat) event.getPacket();
            text = packet.getChatComponent().getUnformattedText().toLowerCase();
            
            // Enhanced detection
            if (containsRegister(text) || containsPassword(text)) {
                detected = true;
                ChatUtil.sendFormatted("§a[AutoRegister] §fDetected in CHAT: §7" + text);
            }
        }
        
        // Trigger registration if detected
        if (detected && detectionTime == 0L) {
            detectionTime = System.currentTimeMillis();
            ChatUtil.sendFormatted("§a[AutoRegister] §fAuth prompt detected! Sending credentials in 1 second...");
        }
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        Minecraft mc = getMinecraft();
        if (event.getType() != EventType.PRE || mc.thePlayer == null || !this.isEnabled()) {
            return;
        }
        
        // Reset on world change/respawn detection
        if (lastWorldId != (mc.theWorld != null ? mc.theWorld.hashCode() : 0)) {
            lastWorldId = mc.theWorld != null ? mc.theWorld.hashCode() : 0;
            hasRegistered = false;
            detectionTime = 0L;
            ChatUtil.sendFormatted("§a[AutoRegister] §fReset - Ready to detect auth prompts");
        }
        
        // If we detected and delay passed, send command
        if (detectionTime > 0L && System.currentTimeMillis() - detectionTime >= DELAY && !hasRegistered) {
            sendRegisterCommand();
            hasRegistered = true;
            detectionTime = 0L;
        }
    }
    
    /**
     * Check if text contains "register" or related words
     */
    private boolean containsRegister(String text) {
        String[] registerWords = {
            "register",
            "/register",
            "registration",
            "регистр", // Russian
            "rejestr", // Polish
            "cadastr", // Portuguese
            "s'inscrire" // French
        };
        
        for (String word : registerWords) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if text contains "password" or related words
     */
    private boolean containsPassword(String text) {
        String[] passwordWords = {
            "password",
            "pass",
            "senha", // Portuguese
            "пароль", // Russian
            "hasło", // Polish
            "mot de passe", // French
            "contraseña", // Spanish
            "passwort" // German
        };
        
        for (String word : passwordWords) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Legacy method for backwards compatibility
     */
    private boolean containsAuthKeywords(String text) {
        return containsRegister(text) || containsPassword(text) || 
               text.contains("login") || text.contains("auth");
    }
    
    private void sendRegisterCommand() {
        Minecraft mc = getMinecraft();
        String pass = password.getValue();
        
        // Try all common register/login formats
        // Most auth plugins use one of these
        
        // Try AuthMe style first: /register <pass> <pass>
        mc.thePlayer.sendChatMessage("/register " + pass + " " + pass);
        
        // Schedule login attempt after register (in case already registered)
        new Thread(() -> {
            try {
                Thread.sleep(500); // Wait for register to process
                Minecraft.getMinecraft().addScheduledTask(() -> {
                    Minecraft mc2 = getMinecraft();
                    if (mc2.thePlayer != null) {
                        mc2.thePlayer.sendChatMessage("/login " + pass);
                    }
                });
            } catch (InterruptedException ignored) {}
        }).start();
    }
}
