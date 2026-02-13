package myau.ui.dataset.impl;

import myau.property.properties.RangeProperty;
import myau.ui.components.ModuleComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;

/**
 * RangeSlider - Sleek dual-handle slider for min/max values
 * Clean design with smooth animations
 */
public class RangeSlider {
    
    private final RangeProperty property;
    private final ModuleComponent parentModule;
    
    private boolean isDraggingMin = false;
    private boolean isDraggingMax = false;
    private int offsetY;
    
    private static final int SLIDER_HEIGHT = 14;
    private static final int HANDLE_WIDTH = 4;
    private static final int TRACK_HEIGHT = 2;
    
    public RangeSlider(RangeProperty property, ModuleComponent parentModule) {
        this.property = property;
        this.parentModule = parentModule;
    }
    
    public void render(int mouseX, int mouseY, int y) {
        this.offsetY = y;
        
        int x = (int) (parentModule.category.getX() + 4);
        int width = (int) (parentModule.category.getWidth() - 8);
        int sliderY = (int) (parentModule.category.getY() + y);
        
        Minecraft mc = Minecraft.getMinecraft();
        
        // Draw property name
        String name = property.getName().replace("-", " ");
        mc.fontRendererObj.drawStringWithShadow(name, x * 2, (sliderY + 2) * 2, 0xFFFFFF);
        
        // Draw value range on the right
        String valueText = property.formatValue();
        int valueWidth = mc.fontRendererObj.getStringWidth(valueText);
        mc.fontRendererObj.drawStringWithShadow(valueText, 
            (x + width - valueWidth / 2) * 2, (sliderY + 2) * 2, 0xAAAAAA);
        
        // Calculate positions for slider track
        int trackY = sliderY + 10;
        int trackX = x;
        int trackWidth = width;
        
        // Draw background track (dark)
        Gui.drawRect(trackX * 2, trackY * 2, 
                    (trackX + trackWidth) * 2, (trackY + TRACK_HEIGHT) * 2, 
                    0xFF2A2A2A);
        
        // Calculate handle positions
        double minPercent = (double)(property.getMinValue() - property.getAbsoluteMin()) / 
                           (property.getAbsoluteMax() - property.getAbsoluteMin());
        double maxPercent = (double)(property.getMaxValue() - property.getAbsoluteMin()) / 
                           (property.getAbsoluteMax() - property.getAbsoluteMin());
        
        int minHandleX = trackX + (int)(minPercent * trackWidth);
        int maxHandleX = trackX + (int)(maxPercent * trackWidth);
        
        // Draw active range (colored track between handles)
        Gui.drawRect(minHandleX * 2, trackY * 2, 
                    maxHandleX * 2, (trackY + TRACK_HEIGHT) * 2, 
                    0xFF4A9EFF); // Blue active range
        
        // Draw min handle
        boolean hoveringMin = isHoveringHandle(mouseX, mouseY, minHandleX, trackY);
        int minColor = (isDraggingMin || hoveringMin) ? 0xFFFFFFFF : 0xFFCCCCCC;
        Gui.drawRect((minHandleX - HANDLE_WIDTH / 2) * 2, (trackY - 2) * 2,
                    (minHandleX + HANDLE_WIDTH / 2) * 2, (trackY + TRACK_HEIGHT + 2) * 2,
                    minColor);
        
        // Draw max handle
        boolean hoveringMax = isHoveringHandle(mouseX, mouseY, maxHandleX, trackY);
        int maxColor = (isDraggingMax || hoveringMax) ? 0xFFFFFFFF : 0xFFCCCCCC;
        Gui.drawRect((maxHandleX - HANDLE_WIDTH / 2) * 2, (trackY - 2) * 2,
                    (maxHandleX + HANDLE_WIDTH / 2) * 2, (trackY + TRACK_HEIGHT + 2) * 2,
                    maxColor);
    }
    
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0) return;
        
        int x = (int) (parentModule.category.getX() + 4);
        int width = (int) (parentModule.category.getWidth() - 8);
        int trackY = (int) (parentModule.category.getY() + offsetY + 10);
        
        double minPercent = (double)(property.getMinValue() - property.getAbsoluteMin()) / 
                           (property.getAbsoluteMax() - property.getAbsoluteMin());
        double maxPercent = (double)(property.getMaxValue() - property.getAbsoluteMin()) / 
                           (property.getAbsoluteMax() - property.getAbsoluteMin());
        
        int minHandleX = x + (int)(minPercent * width);
        int maxHandleX = x + (int)(maxPercent * width);
        
        // Check which handle is being clicked
        if (isHoveringHandle(mouseX, mouseY, minHandleX, trackY)) {
            isDraggingMin = true;
        } else if (isHoveringHandle(mouseX, mouseY, maxHandleX, trackY)) {
            isDraggingMax = true;
        }
    }
    
    public void mouseReleased(int mouseX, int mouseY, int state) {
        isDraggingMin = false;
        isDraggingMax = false;
    }
    
    public void mouseDragged(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0 || (!isDraggingMin && !isDraggingMax)) return;
        
        int x = (int) (parentModule.category.getX() + 4);
        int width = (int) (parentModule.category.getWidth() - 8);
        
        // Calculate percentage from mouse position
        double percent = Math.max(0, Math.min(1, (double)(mouseX - x) / width));
        int range = property.getAbsoluteMax() - property.getAbsoluteMin();
        int newValue = property.getAbsoluteMin() + (int)(percent * range);
        
        if (isDraggingMin) {
            property.setMinValue(newValue);
        } else if (isDraggingMax) {
            property.setMaxValue(newValue);
        }
    }
    
    public void keyTyped(char typedChar, int keyCode) {
        // No keyboard input for range slider
    }
    
    public int getHeight() {
        return SLIDER_HEIGHT;
    }
    
    public boolean isVisible() {
        return property.isVisible();
    }
    
    private boolean isHoveringHandle(int mouseX, int mouseY, int handleX, int trackY) {
        int handleSize = 6; // Slightly larger hit box for easier grabbing
        return mouseX >= handleX - handleSize && mouseX <= handleX + handleSize &&
               mouseY >= trackY - 3 && mouseY <= trackY + TRACK_HEIGHT + 3;
    }
}
