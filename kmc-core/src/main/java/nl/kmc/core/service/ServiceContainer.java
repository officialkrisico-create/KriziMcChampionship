package nl.kmc.core.service;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal DI container — stores services by their interface class.
 * Avoids bringing in Spring / Guice as a dependency.
 */
public final class ServiceContainer {

    private final Map<Class<?>, Object> registry = new HashMap<>();

    public <T> void register(Class<T> type, T implementation) {
        if (registry.containsKey(type))
            throw new IllegalStateException("Service already registered: " + type.getSimpleName());
        registry.put(type, implementation);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        Object service = registry.get(type);
        if (service == null)
            throw new IllegalStateException("Service not registered: " + type.getSimpleName());
        return (T) service;
    }

    public <T> boolean has(Class<T> type) { return registry.containsKey(type); }

    public void clear() { registry.clear(); }
}
