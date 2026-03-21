package myau.events;

import myau.event.events.Event;

public class MoveInputEvent implements Event {
    private boolean sneak;
    private float forward;
    private boolean forwardOverridden;
    private boolean sneakOverridden;

    public boolean isSneak() {
        return this.sneak;
    }

    public void setSneak(boolean sneak) {
        this.sneak = sneak;
        this.sneakOverridden = true;
    }

    public float getForward() {
        return this.forward;
    }

    public void setForward(float forward) {
        this.forward = forward;
        this.forwardOverridden = true;
    }

    public boolean isSneakOverridden() {
        return this.sneakOverridden;
    }

    public boolean isForwardOverridden() {
        return this.forwardOverridden;
    }
}