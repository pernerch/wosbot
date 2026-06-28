package dev.frostguard.tasks.exploration;

import dev.frostguard.engine.schedule.LaunchPoint;


import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.service.StatisticsService;

import java.time.LocalDateTime;

public class DoExplorationRoutine extends DelayedTask {

    public DoExplorationRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override

    protected void execute() {

        tapRandomPoint(new PointData(40, 1190), new PointData(100, 1250));
        sleepTask(500);

        ImageSearchResultData result = templateSearchHelper.locatePattern(
                TemplatesEnum.EXPLORATION_BUTTON,
                SearchConfig.builder().withMaxAttempts(3).withDelay(1000L).build());

        if (result != null && result.isFound()) {
            logInfo("Exploring...");

            tapRandomPoint(new PointData(240, 1150), new PointData(480, 1200));
            sleepTask(300);
            boolean keepFighting = true;

            while (keepFighting) {
                tapRandomPoint(new PointData(55, 1170), new PointData(330, 1220));
                sleepTask(300);
                tapRandomPoint(new PointData(390, 1170), new PointData(670, 1220));

                boolean battleResultFound = false;

                for (int i = 0; i < 24; i++) {
                    ImageSearchResultData victory = templateSearchHelper.locatePattern(
                            TemplatesEnum.EXPLORATION_VICTORY,
                            SearchConfig.builder().withMaxAttempts(1).withDelay(0L).build());

                    if (victory != null && victory.isFound()) {
                        logInfo("Victory! Continue...");
                        StatisticsService.obtain().addToCounter(profile, "Exploration Fights Won", 1);
                        battleResultFound = true;
                        tapRandomPoint(new PointData(400, 990), new PointData(658, 1038));
                        sleepTask(200);
                        break; 
                    }

                    ImageSearchResultData defeat = templateSearchHelper.locatePattern(
                            TemplatesEnum.EXPLORATION_DEFEAT,
                            SearchConfig.builder().withMaxAttempts(1).withDelay(0L).build());

                    if (defeat != null && defeat.isFound()) {
                        logInfo("Defeated.. Rescheduling...");
                        StatisticsService.obtain().addToCounter(profile, "Exploration Fights Lost", 1);
                        battleResultFound = true;
                        keepFighting = false; 
                        this.reschedule(LocalDateTime.now().plusHours(1));
                        return; 
                    }

                    sleepTask(5000);
                }

                if (!battleResultFound) {
                    logWarning("Battle timeout: Neither victory nor defeat screen found within 2 minutes.");
                    keepFighting = false;
                }
            }

            this.reschedule(LocalDateTime.now().plusHours(1));

        } else {
            logWarning("Exploration button not found");
            this.reschedule(LocalDateTime.now().plusHours(1));
        }
    }
}
