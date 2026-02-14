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
     * TRUE AUTO-REGISTRATION SYSTEM
     * Uses reflection to scan for ALL classes with @ModuleInfo annotation
     * ZERO manual registration needed - just create the module class with @ModuleInfo!
     */
    private void autoRegisterModules() {
        try {
            // Get all classes in the modules package
            Package modulesPackage = Module.class.getPackage();
            String packageName = "myau.module.modules";
            
            // Scan classpath for module classes
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            String path = packageName.replace('.', '/');
            
            // Get all .class files in the modules directory
            java.net.URL resource = classLoader.getResource(path);
            if (resource == null) {
                System.err.println("Could not find modules package!");
                return;
            }
            
            File directory = new File(resource.getFile());
            if (!directory.exists()) {
                System.err.println("Modules directory does not exist!");
                return;
            }
            
            // Scan all .class files
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".class"));
            if (files == null) return;
            
            int registered = 0;
            for (File file : files) {
                String className = file.getName().replace(".class", "");
                String fullClassName = packageName + "." + className;
                
                try {
                    // Load the class
                    Class<?> clazz = Class.forName(fullClassName);
                    
                    // Check if it has @ModuleInfo annotation
                    if (clazz.isAnnotationPresent(ModuleInfo.class)) {
                        // Check if it extends Module
                        if (Module.class.isAssignableFrom(clazz)) {
                            // Create instance and register
                            Module module = (Module) clazz.newInstance();
                            moduleManager.modules.put(clazz, module);
                            registered++;
                        }
                    }
                } catch (Exception e) {
                    // Skip classes that can't be loaded
                }
            }
            
            System.out.println("[Myau] Auto-registered " + registered + " modules");
            
        } catch (Exception e) {
            System.err.println("Failed to auto-register modules!");
            e.printStackTrace();
        }
    }
}
