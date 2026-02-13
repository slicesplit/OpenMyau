package myau.property.properties;

import com.google.gson.JsonObject;
import myau.property.Property;

/**
 * RangeProperty - A property that holds min and max integer values
 * Used for range sliders with dual handles
 */
public class RangeProperty extends Property<int[]> {
    
    private int minValue;
    private int maxValue;
    private final int absoluteMin;
    private final int absoluteMax;
    
    public RangeProperty(String name, int defaultMin, int defaultMax, int absoluteMin, int absoluteMax) {
        super(name, new int[]{defaultMin, defaultMax}, null);
        this.minValue = defaultMin;
        this.maxValue = defaultMax;
        this.absoluteMin = absoluteMin;
        this.absoluteMax = absoluteMax;
    }
    
    public int getMinValue() {
        return minValue;
    }
    
    public int getMaxValue() {
        return maxValue;
    }
    
    public void setMinValue(int value) {
        this.minValue = Math.max(absoluteMin, Math.min(value, maxValue));
        setValue(new int[]{minValue, maxValue});
    }
    
    public void setMaxValue(int value) {
        this.maxValue = Math.min(absoluteMax, Math.max(value, minValue));
        setValue(new int[]{minValue, maxValue});
    }
    
    public int getAbsoluteMin() {
        return absoluteMin;
    }
    
    public int getAbsoluteMax() {
        return absoluteMax;
    }
    
    @Override
    public String getValuePrompt() {
        return "range";
    }
    
    @Override
    public String formatValue() {
        return minValue + " - " + maxValue;
    }
    
    @Override
    public boolean parseString(String string) {
        try {
            String[] parts = string.split("-");
            if (parts.length == 2) {
                int min = Integer.parseInt(parts[0].trim());
                int max = Integer.parseInt(parts[1].trim());
                return setValue(new int[]{min, max});
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }
    
    @Override
    public boolean read(JsonObject jsonObject) {
        try {
            int min = jsonObject.get(this.getName() + "-min").getAsInt();
            int max = jsonObject.get(this.getName() + "-max").getAsInt();
            return setValue(new int[]{min, max});
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public void write(JsonObject jsonObject) {
        jsonObject.addProperty(this.getName() + "-min", minValue);
        jsonObject.addProperty(this.getName() + "-max", maxValue);
    }
    
    @Override
    public boolean setValue(Object value) {
        if (value instanceof int[]) {
            int[] arr = (int[]) value;
            if (arr.length == 2) {
                this.minValue = Math.max(absoluteMin, Math.min(arr[0], absoluteMax));
                this.maxValue = Math.min(absoluteMax, Math.max(arr[1], absoluteMin));
                // Ensure min <= max
                if (this.minValue > this.maxValue) {
                    int temp = this.minValue;
                    this.minValue = this.maxValue;
                    this.maxValue = temp;
                }
                return super.setValue(new int[]{minValue, maxValue});
            }
        }
        return false;
    }
}
