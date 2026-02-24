package myau.events;

import myau.event.events.Event;
import net.minecraft.entity.Entity;

public class EntityMovementEvent implements Event {
    private final Entity entity;

    public EntityMovementEvent(Entity entity) {
        this.entity = entity;
    }

    public Entity getEntity() {
        return this.entity;
    }
}
