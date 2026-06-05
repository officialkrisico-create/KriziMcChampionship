package nl.kmc.core.validation;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Central registry of {@link Validator}s for the Event Validation System.
 *
 * <p>Fully modular: any system registers a validator here without modifying the
 * EVS. Lives in {@code kmc-core} so both KMCCore and game plugins can register.
 *
 * <pre>{@code ValidationManager evs = container.get(ValidationManager.class);
 * evs.register(myValidator);}</pre>
 */
public final class ValidationManager {

    private final Map<String, Validator> validators = new LinkedHashMap<>();

    public void register(Validator v) {
        if (v != null) validators.put(v.id(), v);
    }

    public void unregister(String id) { validators.remove(id); }

    public Collection<Validator> getValidators() {
        return Collections.unmodifiableCollection(validators.values());
    }

    public Optional<Validator> get(String id) { return Optional.ofNullable(validators.get(id)); }

    public boolean isRegistered(String id) { return validators.containsKey(id); }
}
