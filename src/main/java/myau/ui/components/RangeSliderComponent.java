package myau.ui.components;

import myau.property.properties.RangeProperty;
import myau.ui.Component;
import myau.ui.dataset.impl.RangeSlider;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Component wrapper for RangeSlider
 */
public class RangeSliderComponent implements Component {
    
    private final RangeSlider rangeSlider;
    private int offsetY;
    
    public RangeSliderComponent(RangeProperty property, ModuleComponent parentModule, int offsetY) {
        this.rangeSlider = new RangeSlider(property, parentModule);
        this.offsetY = offsetY;
    }
    
    @Override
    public void draw(AtomicInteger offset) {
        rangeSlider.render(0, 0, offsetY);
    }
    
    @Override
    public void setComponentStartAt(int newOffsetY) {
        this.offsetY = newOffsetY;
    }
    
    @Override
    public void update(int mousePosX, int mousePosY) {
        rangeSlider.mouseDragged(mousePosX, mousePosY, 0);
    }
    
    @Override
    public void mouseDown(int x, int y, int button) {
        rangeSlider.mouseClicked(x, y, button);
    }
    
    @Override
    public void mouseReleased(int x, int y, int button) {
        rangeSlider.mouseReleased(x, y, button);
    }
    
    @Override
    public void keyTyped(char chatTyped, int keyCode) {
        rangeSlider.keyTyped(chatTyped, keyCode);
    }
    
    @Override
    public int getHeight() {
        return rangeSlider.getHeight();
    }
    
    @Override
    public boolean isVisible() {
        return rangeSlider.isVisible();
    }
}
