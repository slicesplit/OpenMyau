package myau.module.modules;

import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.events.PacketEvent;
import myau.module.Module;
import myau.module.ModuleInfo;
import myau.property.properties.IntProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.IChatComponent;

import static net.minecraft.client.Minecraft.getMinecraft;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ModuleInfo(category = ModuleCategory.MISC)
public class AutoPartyAccept extends Module {
    
    public final IntProperty delay = new IntProperty("delay-ms", 50, 0, 1000);
    
    private static final Pattern[] PARTY_PATTERNS = {
        Pattern.compile("(\\w+) has invited you to.*party", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(\\w+) has sent (?:an?|you a) party invite", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(\\w+) invited you", Pattern.CASE_INSENSITIVE)
    };
    
    public AutoPartyAccept() {
        super("AutoPartyAccept", false);
    }
    
    @EventTarget
    public void onPacket(PacketEvent event) {
        Minecraft mc = getMinecraft();
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        
        if (event.getPacket() instanceof S02PacketChat) {
            S02PacketChat packet = (S02PacketChat) event.getPacket();
            IChatComponent chatComponent = packet.getChatComponent();
            if (chatComponent == null) {
                return;
            }
            
            String unformattedText = chatComponent.getUnformattedText();
            String stripped = stripColorCodes(unformattedText);
            
            for (Pattern pattern : PARTY_PATTERNS) {
                Matcher matcher = pattern.matcher(stripped);
                if (matcher.find()) {
                    String playerName = matcher.group(1);
                    int delayMs = delay.getValue();
                    if (delayMs > 0) {
                        new Thread(() -> {
                            try {
                                Thread.sleep(delayMs);
                                acceptParty(playerName);
                            } catch (InterruptedException ignored) {}
                        }).start();
                    } else {
                        acceptParty(playerName);
                    }
                    break;
                }
            }
        }
    }
    
    private void acceptParty(String playerName) {
        Minecraft mc = getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.sendChatMessage("/party accept");
            ChatUtil.sendFormatted("§a[AutoParty] §fAccepted party from §b" + playerName);
        }
    }
    
    private String stripColorCodes(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("§.", "");
    }
}
