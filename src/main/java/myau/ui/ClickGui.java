
package myau.ui;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import myau.Myau;
import myau.module.Module;
import myau.module.modules.*;
import myau.ui.components.CategoryComponent;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.awt.*;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.List;

public class ClickGui extends GuiScreen {
    private static ClickGui instance;
    private final File configFile = new File("./config/Myau/", "clickgui.txt");
    private final ArrayList<CategoryComponent> categoryList;

    public ClickGui() {
        instance = this;

        // Automatically categorize all modules using their @ModuleInfo annotation
        Map<myau.enums.ModuleCategory, List<Module>> categorizedModules = new HashMap<>();
        
        // Initialize empty lists for each category
        for (myau.enums.ModuleCategory category : myau.enums.ModuleCategory.values()) {
            categorizedModules.put(category, new ArrayList<>());
        }

        // Categorize all registered modules automatically
        for (Module module : Myau.moduleManager.modules.values()) {
            myau.enums.ModuleCategory category = module.getCategory();
            categorizedModules.get(category).add(module);
        }

        // Sort modules within each category by priority, then by name
        Comparator<Module> comparator = Comparator
                .comparingInt(Module::getPriority)
                .thenComparing(m -> m.getName().toLowerCase());
        
        for (List<Module> moduleList : categorizedModules.values()) {
            moduleList.sort(comparator);
        }

        // Create category components
        this.categoryList = new ArrayList<>();
        int topOffset = 5;

        for (myau.enums.ModuleCategory category : myau.enums.ModuleCategory.values()) {
            List<Module> modules = categorizedModules.get(category);
            if (!modules.isEmpty()) {
                CategoryComponent component = new CategoryComponent(category.getDisplayName(), modules);
                component.setY(topOffset);
                categoryList.add(component);
                topOffset += 20;
            }
        }

        loadPositions();
    }

    public static ClickGui getInstance() {
        return instance;
    }

    public void initGui() {
        super.initGui();
    }

    public void drawScreen(int x, int y, float p) {
        drawRect(0, 0, this.width, this.height, new Color(0, 0, 0, 100).getRGB());

        mc.fontRendererObj.drawStringWithShadow("Myau " + Myau.version, 4, this.height - 3 - mc.fontRendererObj.FONT_HEIGHT * 2, new Color(60, 162, 253).getRGB());
        mc.fontRendererObj.drawStringWithShadow("dev, ksyz", 4, this.height - 3 - mc.fontRendererObj.FONT_HEIGHT, new Color(60, 162, 253).getRGB());

        for (CategoryComponent category : categoryList) {
            category.render(this.fontRendererObj);
            category.handleDrag(x, y);

            for (Component module : category.getModules()) {
                module.update(x, y);
            }
        }

        int wheel = Mouse.getDWheel();
        if (wheel != 0) {
            int scrollDir = wheel > 0 ? 1 : -1;
            for (CategoryComponent category : categoryList) {
                category.onScroll(x, y, scrollDir);
            }
        }
    }

    public void mouseClicked(int x, int y, int mouseButton) {
        Iterator<CategoryComponent> btnCat = categoryList.iterator();
        while (true) {
            CategoryComponent category;
            do {
                do {
                    if (!btnCat.hasNext()) {
                        return;
                    }

                    category = btnCat.next();
                    if (category.insideArea(x, y) && !category.isHovered(x, y) && !category.mousePressed(x, y) && mouseButton == 0) {
                        category.mousePressed(true);
                        category.xx = x - category.getX();
                        category.yy = y - category.getY();
                    }

                    if (category.mousePressed(x, y) && mouseButton == 0) {
                        category.setOpened(!category.isOpened());
                    }

                    if (category.isHovered(x, y) && mouseButton == 0) {
                        category.setPin(!category.isPin());
                    }
                } while (!category.isOpened());
            } while (category.getModules().isEmpty());

            for (Component c : category.getModules()) {
                c.mouseDown(x, y, mouseButton);
            }
        }

    }

    public void mouseReleased(int x, int y, int mouseButton) {
        Iterator<CategoryComponent> iterator = categoryList.iterator();

        CategoryComponent categoryComponent;
        while (iterator.hasNext()) {
            categoryComponent = iterator.next();
            if (mouseButton == 0) {
                categoryComponent.mousePressed(false);
            }
        }

        iterator = categoryList.iterator();

        while (true) {
            do {
                do {
                    if (!iterator.hasNext()) {
                        return;
                    }

                    categoryComponent = iterator.next();
                } while (!categoryComponent.isOpened());
            } while (categoryComponent.getModules().isEmpty());

            for (Component component : categoryComponent.getModules()) {
                component.mouseReleased(x, y, mouseButton);
            }
        }
    }

    public void keyTyped(char typedChar, int key) {
        // Check if any bind component is currently binding
        boolean isAnyBinding = false;
        for (CategoryComponent category : categoryList) {
            if (category.isOpened()) {
                for (Component component : category.getModules()) {
                    if (component instanceof myau.ui.components.ModuleComponent) {
                        myau.ui.components.ModuleComponent moduleComp = (myau.ui.components.ModuleComponent) component;
                        if (moduleComp.isBinding()) {
                            isAnyBinding = true;
                            break;
                        }
                    }
                }
            }
            if (isAnyBinding) break;
        }
        
        // ESC key: Only close GUI if not binding, otherwise let bind component handle it
        if (key == 1 && !isAnyBinding) {
            this.mc.displayGuiScreen(null);
        } else if (key == Keyboard.KEY_UP || key == Keyboard.KEY_DOWN) {
            int scrollDir = (key == Keyboard.KEY_UP) ? 1 : -1;
            // Get mouse position
            int mouseX = Mouse.getX() / 2;
            int mouseY = (this.mc.displayHeight - Mouse.getY()) / 2;
            
            // Only scroll the category being hovered over
            for (CategoryComponent category : categoryList) {
                if (category.isOpened()) {
                    // Check if mouse is in the category area
                    int areaTop = category.getY() + 13; // bh = 13
                    int areaBottom = category.getY() + 13 + 300; // MAX_HEIGHT = 300
                    if (mouseX >= category.getX() && mouseX <= category.getX() + category.getWidth() &&
                        mouseY >= areaTop && mouseY <= areaBottom) {
                        category.onScroll(mouseX, mouseY, scrollDir);
                        break; // Only scroll one category
                    }
                }
            }
        } else {
            Iterator<CategoryComponent> btnCat = categoryList.iterator();

            while (true) {
                CategoryComponent cat;
                do {
                    do {
                        if (!btnCat.hasNext()) {
                            return;
                        }

                        cat = btnCat.next();
                    } while (!cat.isOpened());
                } while (cat.getModules().isEmpty());

                for (Component component : cat.getModules()) {
                    component.keyTyped(typedChar, key);
                }
            }
        }
    }

    public void onGuiClosed() {
        savePositions();
    }

    public boolean doesGuiPauseGame() {
        return false;
    }

    private void savePositions() {
        JsonObject json = new JsonObject();
        for (CategoryComponent cat : categoryList) {
            JsonObject pos = new JsonObject();
            pos.addProperty("x", cat.getX());
            pos.addProperty("y", cat.getY());
            pos.addProperty("open", cat.isOpened());
            json.add(cat.getName(), pos);
        }
        try (FileWriter writer = new FileWriter(configFile)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(json, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadPositions() {
        if (!configFile.exists()) return;
        try (FileReader reader = new FileReader(configFile)) {
            JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
            for (CategoryComponent cat : categoryList) {
                if (json.has(cat.getName())) {
                    JsonObject pos = json.getAsJsonObject(cat.getName());
                    cat.setX(pos.get("x").getAsInt());
                    cat.setY(pos.get("y").getAsInt());
                    cat.setOpened(pos.get("open").getAsBoolean());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
