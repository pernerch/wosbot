package dev.frostguard.tasks.exploration;

import dev.frostguard.engine.schedule.LaunchPoint;


import java.time.LocalDateTime;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.nav.SearchConfigConstants;

public class ExplorationRoutine extends DelayedTask {

	public ExplorationRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
		super(profile, tpDailyTask);
	}

	@Override
	protected void execute() {
		tapRandomPoint(new PointData(40, 1190), new PointData(100, 1250));
		sleepTask(500);
		ImageSearchResultData claimResult = templateSearchHelper.locatePattern(
				TemplatesEnum.EXPLORATION_CLAIM, SearchConfigConstants.DEFAULT_SINGLE);
		if (claimResult.isFound()) {
			logInfo("Claiming exploration rewards...");
			tapRandomPoint(new PointData(560, 900), new PointData(670, 940));
			sleepTask(500);
			tapRandomPoint(new PointData(230, 890), new PointData(490, 960));
			sleepTask(500);

			tapRandomPoint(new PointData(230, 890), new PointData(490, 960));
			sleepTask(200);
			tapRandomPoint(new PointData(230, 890), new PointData(490, 960));
			sleepTask(200);
			tapRandomPoint(new PointData(230, 890), new PointData(490, 960));
			sleepTask(200);

			Integer minutes = profile.getConfig(ConfigurationKeyEnum.INT_EXPLORATION_CHEST_OFFSET, Integer.class);
			LocalDateTime nextSchedule = LocalDateTime.now().plusMinutes(minutes);
			this.reschedule(nextSchedule);
			logInfo("Exploration task completed. Next execution scheduled in " + minutes + " minutes.");

		} else {
			logInfo("No exploration rewards to claim.");
			Integer minutes = profile.getConfig(ConfigurationKeyEnum.INT_EXPLORATION_CHEST_OFFSET, Integer.class);
			LocalDateTime nextSchedule = LocalDateTime.now().plusMinutes(minutes);
			this.reschedule(nextSchedule);
			logInfo("Next execution scheduled in " + minutes + " minutes.");

		}
		pressBack();
		sleepTask(500);
	}

}
