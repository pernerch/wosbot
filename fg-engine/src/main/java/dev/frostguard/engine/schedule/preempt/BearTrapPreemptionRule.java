package dev.frostguard.engine.schedule.preempt;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.RawImageData;

/**
 * Fires when the Bear Trap running indicator is visible on-screen,
 * causing the scheduler to interrupt the current task and launch the
 * bear-trap routine instead.
 */
public class BearTrapPreemptionRule implements PreemptionRule {

    @Override
    public boolean shouldPreempt(EmulatorController controller,
                                 AccountDescriptor profile,
                                 RawImageData screenshot) {
        try {
            ImageSearchResultData match = controller.locatePattern(
                    profile.getEmulatorNumber(), screenshot,
                    TemplatesEnum.BEAR_HUNT_IS_RUNNING, 90);
            return match.isFound();
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public TpDailyTaskEnum getTaskToExecute() { return TpDailyTaskEnum.BEAR_TRAP; }

    @Override
    public String getRuleName() { return "BearTrapActive"; }
}
