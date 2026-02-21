package myau.events;

import myau.event.events.callables.EventCancellable;

public class KnockbackEvent extends EventCancellable {
    private double x;
    private double y;
    private double z;

    public KnockbackEvent(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double getX() {
        return this.x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return this.y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getZ() {
        return this.z;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public void setVelocity(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.setCancelled(true);
    }
}