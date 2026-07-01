package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatherQueuePolicyTest {

    @Test
    void capsConfiguredQueueCountAtFour() {
        assertEquals(4, GatherQueuePolicy.resolveActiveQueueLimit(8));
        assertEquals(4, GatherQueuePolicy.resolveActiveQueueLimit(4));
        assertEquals(1, GatherQueuePolicy.resolveActiveQueueLimit(0));
    }

    @Test
    void preventsDuplicateMarchDeploymentsPerResource() {
        List<String> activeMarches = List.of("MEAT");

        assertFalse(GatherQueuePolicy.allowMarchDeployment(activeMarches, "MEAT"));
        assertTrue(GatherQueuePolicy.allowMarchDeployment(activeMarches, "WOOD"));
    }

    @Test
    void defersGatherWhenHigherPriorityMarchTasksArePending() {
        assertTrue(GatherQueuePolicy.shouldDeferGatherForPendingTasks(List.of(TpDailyTaskEnum.BEAR_TRAP)));
        assertTrue(GatherQueuePolicy.shouldDeferGatherForPendingTasks(List.of(TpDailyTaskEnum.INTEL)));
        assertFalse(GatherQueuePolicy.shouldDeferGatherForPendingTasks(List.of(TpDailyTaskEnum.GATHER_RESOURCES)));
    }
}
