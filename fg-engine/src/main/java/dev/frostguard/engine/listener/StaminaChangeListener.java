package dev.frostguard.engine.listener;

// Receives energy-level mutation events for tracked accounts.
// Callbacks fire on OCR reads, regen ticks, and explicit API adjustments.
public interface StaminaChangeListener {

    // The energy level for accountId was updated.
    void onEnergyLevelChanged(Long accountId, int currentStamina);

    // Called before the tracker sweeps all accounts for regeneration.
    default void onRegenerationSweepStarting() {}

    // Called after the regeneration sweep completes.
    default void onRegenerationSweepFinished(int accountsAffected) {}
}
