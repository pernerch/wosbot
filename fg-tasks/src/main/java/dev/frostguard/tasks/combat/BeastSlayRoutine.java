package dev.frostguard.tasks.combat;

import java.time.LocalDateTime;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.service.StatisticsService;

public class BeastSlayRoutine extends DelayedTask {

	private static final int MIN_STAMINA = 10;
	private static final int STAMINA_COST_PER_ATTACK = 10;

	private int maxQueues;
	private int beastLevel;

	/** Tracks the earliest time this task should resume (like GatherRoutine pattern). */
	private LocalDateTime earliestReschedule;

	public BeastSlayRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
		super(profile, tpTask);
	}

	@Override
	protected boolean consumesStamina() {
		return true;
	}

	@Override
	protected void execute() {
		earliestReschedule = null;

		// Load configuration
		Integer configMarches = profile.getConfig(ConfigurationKeyEnum.BEAST_HUNTING_MARCHES_INT, Integer.class);
		this.maxQueues = (configMarches != null) ? configMarches : 3;
		Integer configLevel = profile.getConfig(ConfigurationKeyEnum.BEAST_HUNTING_LEVEL_INT, Integer.class);
		this.beastLevel = (configLevel != null) ? configLevel : 30;

		// Use staminaHelper to check stamina (already read during initialization/validation)
		if (!staminaHelper.checkStaminaAndMarchesOrReschedule(MIN_STAMINA, MIN_STAMINA, this::reschedule)) {
			return;
		}

		int currentStamina = staminaHelper.getCurrentStamina();
		logInfo("Initiating beast attacks. Stamina: " + currentStamina
				+ ", Max queues: " + maxQueues + ", Beast level: " + beastLevel);

		int attacksDone = 0;

		// Fill available queues with beast attacks
		while (currentStamina >= MIN_STAMINA && attacksDone < maxQueues) {

			sleepTask(6000);
			// go to the beast search menu
			tapRandomPoint(new PointData(25, 850), new PointData(67, 898));
			sleepTask(1000);

			swipe(new PointData(20, 910), new PointData(70, 915));
			sleepTask(1000);
			// beast button
			tapRandomPoint(new PointData(70, 880), new PointData(120, 930));
			sleepTask(1000);
			// go to level 1
			swipe(new PointData(180, 1050), new PointData(1, 1050));

			// select beast level
			tapRandomPoint(new PointData(470, 1040), new PointData(500, 1070), beastLevel - 1, 100);
			sleepTask(1000);
			// click search
			tapRandomPoint(new PointData(301, 1200), new PointData(412, 1229));
			sleepTask(6000);

			// click attack - search for the attack button template

			tapRandomPoint(new PointData(270, 600), new PointData(460, 630));
			sleepTask(6000);
			
			ImageSearchResultData attackBtn = templateSearchHelper.locatePattern(
					TemplatesEnum.GAME_HOME_SHORTCUTS_ATTACK, SearchConfig.builder().build());
			if (attackBtn != null && attackBtn.isFound()) {
				tapPoint(attackBtn.getPoint());
			}
			
			sleepTask(3000);


			try {
				// Use staminaHelper to parse travel time via OCR (uses CommonGameAreas.TRAVEL_TIME_OCR_AREA)
				long travelSeconds = staminaHelper.parseTravelTime();

				// confirm attack
				tapRandomPoint(new PointData(450, 1183), new PointData(640, 1240));

				// Update stamina tracking
				staminaHelper.subtractStamina(STAMINA_COST_PER_ATTACK, false);
				currentStamina = staminaHelper.getCurrentStamina();
				attacksDone++;
				StatisticsService.obtain().addToCounter(profile, "Beast Attacks Sent", 1);

				// March returns in ~2x travel time
				long returnSeconds = (travelSeconds > 0) ? travelSeconds * 2 : 120;
				LocalDateTime marchReturn = LocalDateTime.now().plusSeconds(returnSeconds);
				updateReschedule(marchReturn);

				logInfo("Beast attacked. March returns in ~" + returnSeconds
						+ "s. Remaining stamina: " + currentStamina + ", attacks done: " + attacksDone);

			} catch (Exception e) {
				logError("Failed during beast attack: " + e.getMessage());
				// Conservative fallback reschedule
				updateReschedule(LocalDateTime.now().plusMinutes(5));
				break;
			}
		}

		// Finalize: reschedule to earliest beast return time (freeing the thread for other tasks)
		finalizeReschedule();
	}

	@Override
	protected LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.WORLD;
	}

	// ========================================================================
	// SCHEDULING HELPERS (GatherRoutine pattern)
	// ========================================================================

	private void updateReschedule(LocalDateTime t) {
		if (earliestReschedule == null || t.isBefore(earliestReschedule)) {
			earliestReschedule = t;
		}
	}

	private void finalizeReschedule() {
		if (earliestReschedule != null) {
			logInfo("Beast Hunting finished. Rescheduling to " + earliestReschedule + " (earliest march return).");
			reschedule(earliestReschedule);
		} else {
			logInfo("Beast Hunting finished. No marches dispatched. Rescheduling in 5 minutes.");
			reschedule(LocalDateTime.now().plusMinutes(5));
		}
	}

}
