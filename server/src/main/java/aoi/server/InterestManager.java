package aoi.server;

import java.util.Collection;
import java.util.List;

/**
 * Pluggable area-of-interest algorithm. Given a viewer and every entity in the world,
 * return the entities the viewer should be told about this tick.
 * Add new algorithms (grid, hexagonal, ray visibility, ...) by implementing this interface.
 */
public interface InterestManager {
    String name();

    /** Radius the client may draw as the viewer's area of interest. */
    double radius();

    List<Entity> visibleTo(Entity viewer, Collection<Entity> all);
}
