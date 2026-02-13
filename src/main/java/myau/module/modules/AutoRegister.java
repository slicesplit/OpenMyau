package myau.module.modules;

import myau.Myau;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.module.ModuleInfo;
import myau.property.properties.TextProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import static net.minecraft.client.Minecraft.getMinecraft;

/**
 * AutoRegister - Automatically registers/logins on cracked servers
 * Detects "in" title or /register /login prompts and auto-sends password
 */
@ModuleInfo(category = ModuleCategory.MISC)
public class AutoRegister extends Module {
    
    public final TextProperty password = new TextProperty("password", "testing123");
    
    private boolean hasRegistered = false;
    private long detectionTime = 0L;
    private static final long DELAY = 100; // ms delay before sending command
    
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
    public void onTick(TickEvent event) {
        Minecraft mc = getMinecraft();
        if (event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        
        // Don't run if already registered this session
        if (hasRegistered) {
            return;
        }
        
        // Check if we're on a screen with "in" text (register/login screen indicator)
        GuiScreen currentScreen = mc.currentScreen;
        if (currentScreen != null) {
            String screenTitle = "";
            
            // Try to get screen title (works with most auth plugins)
            try {
                // Check if there's a title field
                if (currentScreen.getClass().getSimpleName().toLowerCase().contains("login") ||
                    currentScreen.getClass().getSimpleName().toLowerCase().contains("register")) {
                    
                    // Detected auth screen
                    if (detectionTime == 0L) {
                        detectionTime = System.currentTimeMillis();
                    }
                }
            } catch (Exception ignored) {}
        }
        
        // Also check chat for register/login prompts
        // This is handled passively - when player sees the message, they know to enable
        
        // If we detected and delay passed, send command
        if (detectionTime > 0L && System.currentTimeMillis() - detectionTime >= DELAY) {
            sendRegisterCommand();
            hasRegistered = true;
            detectionTime = 0L;
        }
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
