package nl.kmc.kmccore.module;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.simulation.SimulationEngine;
import nl.kmc.kmccore.snapshot.SnapshotManager;

/**
 * Phase 2.5 — tournament simulation and state snapshots.
 *
 * <p>Depends on {@link CoreModule}, {@link InfraModule}, and
 * {@link EngagementModule} being enabled first.
 */
public class SimulationModule implements PluginModule {

    private final KMCCore plugin;

    private SnapshotManager  snapshotManager;
    private SimulationEngine simulationEngine;

    public SimulationModule(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public void enable() {
        snapshotManager  = new SnapshotManager(plugin);
        simulationEngine = new SimulationEngine(plugin);
    }

    @Override
    public void disable() {
        // Neither manager holds long-lived resources; no-op.
    }

    public SnapshotManager  getSnapshotManager()  { return snapshotManager; }
    public SimulationEngine getSimulationEngine() { return simulationEngine; }
}
