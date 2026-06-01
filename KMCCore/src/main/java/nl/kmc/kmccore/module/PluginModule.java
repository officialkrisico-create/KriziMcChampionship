package nl.kmc.kmccore.module;

/**
 * Lifecycle contract for a self-contained group of managers.
 *
 * <p>Modules are enabled in dependency order by {@code KMCCore.onEnable()};
 * disabled in reverse order by {@code KMCCore.onDisable()}.
 * Each module is responsible for null-guarding its own managers on disable.
 */
public interface PluginModule {
    void enable();
    void disable();
}
