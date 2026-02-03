package me.ksyz.accountmanager.gui;

import me.ksyz.accountmanager.auth.SessionManager;
import me.ksyz.accountmanager.utils.UsernameGenerator;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.Session;
import org.lwjgl.input.Keyboard;

import java.awt.*;
import java.io.IOException;
import java.util.UUID;

public class GuiCrackedLogin extends GuiScreen {
    private GuiScreen previousScreen;
    private String status = "Cracked Login";
    private GuiTextField usernameField;
    private ScaledResolution sr;

    public GuiCrackedLogin(GuiScreen previousScreen) {
        this.previousScreen = previousScreen;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        sr = new ScaledResolution(mc);

        usernameField = new GuiTextField(1, mc.fontRendererObj, sr.getScaledWidth() / 2 - 100, sr.getScaledHeight() / 2 - 10, 200, 20);
        usernameField.setMaxStringLength(16);
        usernameField.setFocused(true);

        buttonList.add(new GuiButton(1, sr.getScaledWidth() / 2 - 100, sr.getScaledHeight() / 2 + 20, 200, 20, "Login"));
        buttonList.add(new GuiButton(2, sr.getScaledWidth() / 2 - 100, sr.getScaledHeight() / 2 + 45, 200, 20, "Random Username"));
        buttonList.add(new GuiButton(3, sr.getScaledWidth() / 2 - 100, sr.getScaledHeight() / 2 + 70, 95, 20, "Generate"));
        buttonList.add(new GuiButton(4, sr.getScaledWidth() / 2 + 5, sr.getScaledHeight() / 2 + 70, 95, 20, "Back"));

        super.initGui();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        mc.fontRendererObj.drawString(status, sr.getScaledWidth() / 2 - mc.fontRendererObj.getStringWidth(status) / 2, sr.getScaledHeight() / 2 - 40, Color.WHITE.getRGB());
        mc.fontRendererObj.drawString("Username:", sr.getScaledWidth() / 2 - 100, sr.getScaledHeight() / 2 - 25, Color.WHITE.getRGB());
        usernameField.drawTextBox();

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case 1:
                loginCracked(usernameField.getText());
                break;
            case 2:
                String randomUsername = UsernameGenerator.generateUnique();
                loginCracked(randomUsername);
                break;
            case 3:
                usernameField.setText(UsernameGenerator.generateUnique());
                break;
            case 4:
                mc.displayGuiScreen(previousScreen);
                break;
        }

        super.actionPerformed(button);
    }

    private void loginCracked(String username) {
        if (username == null || username.trim().isEmpty()) {
            status = "§cError: Username cannot be empty";
            return;
        }

        username = username.trim();

        if (username.length() < 3 || username.length() > 16) {
            status = "§cError: Username must be 3-16 characters";
            return;
        }

        if (!username.matches("[a-zA-Z0-9_]+")) {
            status = "§cError: Invalid characters in username";
            return;
        }

        try {
            String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes()).toString();
            Session crackedSession = new Session(username, uuid, "0", "legacy");
            SessionManager.set(crackedSession);
            status = "§aSuccessfully logged in as " + username;
            
            new Thread(() -> {
                try {
                    Thread.sleep(1000);
                    mc.addScheduledTask(() -> mc.displayGuiScreen(previousScreen));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }).start();
        } catch (Exception e) {
            status = "§cError: Failed to set session";
            e.printStackTrace();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        usernameField.textboxKeyTyped(typedChar, keyCode);

        if (keyCode == Keyboard.KEY_RETURN) {
            loginCracked(usernameField.getText());
        } else if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(previousScreen);
        } else {
            super.keyTyped(typedChar, keyCode);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        usernameField.mouseClicked(mouseX, mouseY, mouseButton);
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }
}
