package myau;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.ksyz.accountmanager.AccountManager;
import myau.command.CommandManager;
import myau.command.commands.*;
import myau.config.Config;
import myau.event.EventManager;
import myau.management.*;
import myau.management.TransactionManager;
import myau.module.Module;
import myau.module.ModuleInfo;
import myau.module.ModuleManager;
import myau.module.modules.*;
import myau.property.Property;
import myau.property.PropertyManager;

import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Objects;

public class Myau {
    public static String clientName = "&7[&cM&6y&ea&au&7]&r ";
    public static String version;
    public static RotationManager rotationManager;
    public static FloatManager floatManager;
    public static BlinkManager blinkManager;
    public static DelayManager delayManager;
    public static LagManager lagManager;
    public static PlayerStateManager playerStateManager;
    public static FriendManager friendManager;
    public static TargetManager targetManager;
    public static PropertyManager propertyManager;
    public static ModuleManager moduleManager;
    public static CommandManager commandManager;

    public Myau() {
        this.init();
    }

    public void init() {
        rotationManager = new RotationManager();
        floatManager = new FloatManager();
        blinkManager = new BlinkManager();
        delayManager = new DelayManager();
        lagManager = new LagManager();
        playerStateManager = new PlayerStateManager();
        friendManager = new FriendManager();
        targetManager = new TargetManager();
        propertyManager = new PropertyManager();
        moduleManager = new ModuleManager();
        commandManager = new CommandManager();
        EventManager.register(rotationManager);
        EventManager.register(floatManager);
        EventManager.register(blinkManager);
        EventManager.register(delayManager);
        EventManager.register(lagManager);
        EventManager.register(moduleManager);
        EventManager.register(commandManager);
        
        // Initialize Global Transaction Manager for Grim bypasses
        TransactionManager.getInstance();
        
        // AUTO-REGISTRATION: Automatically registers all module classes
        autoRegisterModules();
        commandManager.commands.add(new BindCommand());
        commandManager.commands.add(new ConfigCommand());
        commandManager.commands.add(new DenickCommand());
        commandManager.commands.add(new FriendCommand());
        commandManager.commands.add(new HelpCommand());
        commandManager.commands.add(new HideCommand());
        commandManager.commands.add(new IgnCommand());
        commandManager.commands.add(new ItemCommand());
        commandManager.commands.add(new ListCommand());
        commandManager.commands.add(new ModuleCommand());
        commandManager.commands.add(new PlayerCommand());
        commandManager.commands.add(new ShowCommand());
        commandManager.commands.add(new TargetCommand());
        commandManager.commands.add(new ToggleCommand());
        commandManager.commands.add(new VclipCommand());
        for (Module module : moduleManager.modules.values()) {
            ArrayList<Property<?>> properties = new ArrayList<>();
            for (final Field field : module.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                final Object obj;
                try {
                    obj = field.get(module);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
                if (obj instanceof Property<?>) {
                    ((Property<?>) obj).setOwner(module);
                    properties.add((Property<?>) obj);
                }
            }
            propertyManager.properties.put(module.getClass(), properties);
            EventManager.register(module);
        }
        Config config = new Config("default", true);
        if (config.file.exists()) {
            config.load();
        }
        if (friendManager.file.exists()) {
            friendManager.load();
        }
        if (targetManager.file.exists()) {
            targetManager.load();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(config::save));

        try (InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(Myau.class.getResourceAsStream("/version.json")), StandardCharsets.UTF_8)) {
            JsonObject modInfo = new JsonParser().parse(reader).getAsJsonObject();
            version = modInfo.get("version").getAsString();
        } catch (Exception e) {
            version = "dev";
        }

        AccountManager.init();
    }
    
    /**
     * SIMPLE AUTO-REGISTRATION SYSTEM
     * Hardcoded list but cleaner than manual registration everywhere
     * Add your module class here and it auto-registers!
     */
    private void autoRegisterModules() {
        Class<?>[] moduleClasses = {
            AimAssist.class, AntiAFK.class, AntiDebuff.class, AntiFireball.class,
            AntiObbyTrap.class, AntiObfuscate.class, AntiVoid.class, AutoAnduril.class,
            AutoClicker.class, AutoHeal.class, AutoTool.class, BedESP.class,
            BedNuker.class, BedTracker.class, Blink.class, Chams.class,
            ChatLimitRemove.class, ChestESP.class, ChestStealer.class, Eagle.class, ESP.class,
            FakeLag.class, FastPlace.class, Freecam.class, Freeze.class,
            Fly.class, FovFix.class, FullBright.class, GhostHand.class,
            GuiModule.class, WTap.class, HUD.class, MoreKB.class,
            Indicators.class, InventoryClicker.class, InvManager.class, InvWalk.class,
            ItemESP.class, Jesus.class, JumpReset.class, KeepSprint.class,
            HitBox.class, KillAura.class, LagRange.class, LightningTracker.class,
            LongJump.class, MCF.class, NameTags.class, NickHider.class,
            NoFall.class, NoHitDelay.class, NoHurtCam.class, NoJumpDelay.class,
            NoRotate.class, NoSlow.class, Radar.class, Reach.class,
            Refill.class, RemoteShop.class, SafeWalk.class, Scaffold.class,
            Spammer.class, Speed.class, SpeedMine.class, Sprint.class,
            TargetHUD.class, TargetStrafe.class, Tracers.class, Trajectories.class,
            Velocity.class, ViewClip.class, Xray.class, RearView.class,
            ViperNode.class, SkeletonESP.class, TickBase.class,
            AutoPartyAccept.class, OldBacktrack.class, NewBacktrack.class
        };
        
        int registered = 0;
        for (Class<?> moduleClass : moduleClasses) {
            try {
                if (moduleClass.isAnnotationPresent(ModuleInfo.class) && Module.class.isAssignableFrom(moduleClass)) {
                    Module module = (Module) moduleClass.newInstance();
                    moduleManager.modules.put(moduleClass, module);
                    registered++;
                }
            } catch (Exception e) {
                System.err.println("[Myau] Failed to register: " + moduleClass.getSimpleName());
            }
        }
        
        System.out.println("[Myau] Auto-registered " + registered + " modules");
    }
}
