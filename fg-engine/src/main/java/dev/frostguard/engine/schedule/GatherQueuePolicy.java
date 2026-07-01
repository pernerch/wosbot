package dev.frostguard.engine.schedule;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.service.TaskManagementService;

public final class GatherQueuePolicy {

    private static final int HARD_QUEUE_CAP = 4;
    private static final Set<TpDailyTaskEnum> HIGH_PRIORITY_MARCH_TASKS = Set.of(
            TpDailyTaskEnum.BEAR_TRAP,
            TpDailyTaskEnum.INTEL
    );

    private GatherQueuePolicy() {
    }

    public static int resolveActiveQueueLimit(int configuredLimit) {
        if (configuredLimit <= 0) {
            return 1;
        }
        return Math.min(HARD_QUEUE_CAP, configuredLimit);
    }

    public static boolean allowMarchDeployment(Collection<String> activeMarches, String resourceName) {
        if (activeMarches == null || resourceName == null || resourceName.isBlank()) {
            return true;
        }
        return activeMarches.stream().noneMatch(existing -> resourceName.equalsIgnoreCase(existing));
    }

    public static boolean shouldDeferGatherForPendingTasks(Collection<TpDailyTaskEnum> pendingTasks) {
        if (pendingTasks == null || pendingTasks.isEmpty()) {
            return false;
        }
        return pendingTasks.stream()
                .filter(task -> task != null)
                .anyMatch(HIGH_PRIORITY_MARCH_TASKS::contains);
    }

    public static boolean shouldRecallDuplicateGatherMarches(Collection<TpDailyTaskEnum> pendingTasks) {
        return shouldDeferGatherForPendingTasks(pendingTasks);
    }

    public static boolean hasPendingHigherPriorityMarchTask(AccountDescriptor profile) {
        if (profile == null || profile.getId() == null) {
            return false;
        }
        List<TpDailyTaskEnum> pendingTasks = Arrays.stream(TpDailyTaskEnum.values())
                .filter(HIGH_PRIORITY_MARCH_TASKS::contains)
                .filter(task -> isTaskPending(profile, task))
                .collect(Collectors.toList());
        return !pendingTasks.isEmpty();
    }

    private static boolean isTaskPending(AccountDescriptor profile, TpDailyTaskEnum task) {
        if (profile == null || task == null) {
            return false;
        }
        var state = TaskManagementService.shared().lookupTaskState(profile.getId(), task.getId());
        return state != null && (state.isScheduled() || state.isExecuting());
    }
}
