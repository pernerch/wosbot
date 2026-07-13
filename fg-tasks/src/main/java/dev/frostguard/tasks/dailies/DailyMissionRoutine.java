package dev.frostguard.tasks.dailies;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.vision.convert.GameTimeUtils;
import java.time.LocalDateTime;

public class DailyMissionRoutine extends DelayedTask {

private static final int FINAL_CHECK_BEFORE_RESET_MINUTES_VALUE = 2;

private static final int SAFETY_RESCHEDULE_MINUTES_VALUE = 30;

private static final int POPUP_DISMISS_TAP_COUNT_VALUE = 3;

private static final int INDIVIDUAL_CLAIM_MAX_REPETITIONS = 10;

private static final int INDIVIDUAL_CLAIM_LIMIT_RESCHEDULE_MINUTES = 5;

private static final PointData DAILY_MISSIONS_BUTTON_VALUE = new PointData(50, 1050);

private static final PointData POPUP_DISMISS_MIN_VALUE = new PointData(10, 100);

private static final PointData POPUP_DISMISS_MAX_VALUE = new PointData(600, 120);

private boolean autoScheduleEnabled;

private int checkOffsetMinutes;

public DailyMissionRoutine(AccountDescriptor profile, TpDailyTaskEnum dailyMission) {
		super(profile, dailyMission);
	}

@Override
	protected void execute() {

		hydrateTaskConfiguration();
		reachDailyMissions();
		switchToDailyMissionsTabFlow();
		boolean completedNormally = redeemAllRewards();
		if (!completedNormally) {
			dismissInterface();
			LocalDateTime retryAt = LocalDateTime.now().plusMinutes(INDIVIDUAL_CLAIM_LIMIT_RESCHEDULE_MINUTES);
			reschedule(retryAt);
			logWarning(routineLogDailyMissionLine("Stopped repeated individual-claim loop after "
					+ INDIVIDUAL_CLAIM_MAX_REPETITIONS + " attempts. Rescheduled for: "
					+ retryAt.format(DATETIME_FORMATTER)));
			return;
		}
		StatisticsService.obtain().addToCounter(profile, "Daily Missions Claimed", 1);
		dismissInterface();

		configureRecurringBehaviorFlow();
		queueNextExecution();
	}

@Override
	protected LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.ANY;
	}

private void configureRecurringBehaviorFlow() {
		boolean shouldRecur = !autoScheduleEnabled;
		setRecurring(shouldRecur);

		logInfo(routineLogDailyMissionLine(String.format("Task recurring: %s (auto-schedule: %s)",
				shouldRecur, autoScheduleEnabled)));
	}

private void switchToDailyMissionsTabFlow() {
		ImageSearchResultData dailyTabResult = templateSearchHelper.locatePattern(
				TemplatesEnum.DAILY_MISSION_DAILY_TAB,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (dailyTabResult.isFound()) {
			logInfo(routineLogDailyMissionLine("Switching to daily missions tab"));
			tapPoint(dailyTabResult.getPoint());
			sleepTask(500);

		} else {
			logDebug(routineLogDailyMissionLine("Daily tab not detected - may already be on correct tab"));
		}
	}

private ImageSearchResultData seekForIndividualClaimButton() {
		return templateSearchHelper.locatePattern(
				TemplatesEnum.DAILY_MISSION_CLAIM_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);
	}

private String routineLogDailyMissionLine(String note) {
        return "DailyMissionRoutine | " + note;
    }

private LocalDateTime queueFinalCheckBeforeReset(LocalDateTime gameReset) {
		LocalDateTime finalCheck = gameReset.minusMinutes(FINAL_CHECK_BEFORE_RESET_MINUTES_VALUE);
		logInfo(routineLogDailyMissionLine("Scheduling final check before reset at: " +
				finalCheck.format(DATETIME_FORMATTER)));
		return finalCheck;
	}

private boolean redeemRewardsIndividually() {
		logWarning(routineLogDailyMissionLine("'Claim All' button not detected. Collecting missions individually"));

		ImageSearchResultData claimResult;
		int claimedCount = 0;

		while ((claimResult = seekForIndividualClaimButton()).isFound()) {
			if (claimedCount >= INDIVIDUAL_CLAIM_MAX_REPETITIONS) {
				logWarning(routineLogDailyMissionLine("Detected repeated individual claim button without convergence. "
						+ "Stopping after " + INDIVIDUAL_CLAIM_MAX_REPETITIONS + " repetitions."));
				return false;
			}

			claimedCount++;
			logDebug(routineLogDailyMissionLine("Collecting individual reward #" + claimedCount));

			tapPoint(claimResult.getPoint());
			dismissRewardPopupsFlow();
			sleepTask(500);

		}

		logInfo(routineLogDailyMissionLine("Individual collecting complete. Claimed " + claimedCount + " rewards"));
		return true;
	}

private void hydrateTaskConfiguration() {
		this.autoScheduleEnabled = profile.getConfig(
				ConfigurationKeyEnum.DAILY_MISSION_AUTO_SCHEDULE_BOOL,
				Boolean.class);

		this.checkOffsetMinutes = profile.getConfig(
				ConfigurationKeyEnum.DAILY_MISSION_OFFSET_INT,
				Integer.class);

		logInfo(routineLogDailyMissionLine(String.format("Configuration - Auto-schedule: %s, Check offset: %d minutes",
				autoScheduleEnabled, checkOffsetMinutes)));
	}

private void dismissRewardPopupsFlow() {
		tapRandomPoint(
				POPUP_DISMISS_MIN_VALUE,
				POPUP_DISMISS_MAX_VALUE,
				POPUP_DISMISS_TAP_COUNT_VALUE,
				150

		);
	}

private void redeemAllRewardsAtOnce(ImageSearchResultData claimAllResult) {
		logInfo(routineLogDailyMissionLine("'Claim All' button detected. Collecting all rewards at once"));

		tapPoint(claimAllResult.getPoint());
		dismissRewardPopupsFlow();
	}

private ImageSearchResultData seekForClaimAllButton() {
		return templateSearchHelper.locatePattern(
				TemplatesEnum.DAILY_MISSION_CLAIMALL_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);
	}

private void reachDailyMissions() {
		logInfo(routineLogDailyMissionLine("Moving to daily missions interface"));

		tapPoint(DAILY_MISSIONS_BUTTON_VALUE);
		sleepTask(3000);

	}

private void queueNextExecution() {
		if (isRecurring()) {
			queueManualMode();
		} else {
			queueAutoMode();
		}
	}

private LocalDateTime queueAtOffsetTime(LocalDateTime proposedTime, LocalDateTime gameReset,
			boolean beforeFinalCheckWindow) {
		LocalDateTime cappedTime = gameReset.minusMinutes(FINAL_CHECK_BEFORE_RESET_MINUTES_VALUE);

		if (beforeFinalCheckWindow && proposedTime.isAfter(cappedTime)) {
			logInfo(routineLogDailyMissionLine("Proposed time exceeds reset window. Capping at: " +
					cappedTime.format(DATETIME_FORMATTER)));
			return cappedTime;
		}

		return proposedTime;
	}

private boolean redeemAllRewards() {
		logInfo(routineLogDailyMissionLine("Scanning for claim buttons"));

		ImageSearchResultData claimAllResult = seekForClaimAllButton();

		if (claimAllResult.isFound()) {
			redeemAllRewardsAtOnce(claimAllResult);
			return true;
		} else {
			return redeemRewardsIndividually();
		}
	}

private void dismissInterface() {
		pressBack();
		sleepTask(500);

	}

private void queueManualMode() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime gameReset = GameTimeUtils.dailyResetTime();
		LocalDateTime proposedTime = now.plusMinutes(checkOffsetMinutes);
		LocalDateTime finalCheckTime = gameReset.minusMinutes(FINAL_CHECK_BEFORE_RESET_MINUTES_VALUE);
		boolean beforeFinalCheckWindow = now.isBefore(finalCheckTime);

		LocalDateTime nextExecution;

		if (beforeFinalCheckWindow && proposedTime.isAfter(gameReset)) {
			nextExecution = queueFinalCheckBeforeReset(gameReset);
		} else {
			nextExecution = queueAtOffsetTime(proposedTime, gameReset, beforeFinalCheckWindow);
		}

		reschedule(nextExecution);
		logInfo(routineLogDailyMissionLine("Next execution scheduled for: " + nextExecution.format(DATETIME_FORMATTER) +
				" (Manual mode)"));
	}

private void queueAutoMode() {
		LocalDateTime safetyTime = LocalDateTime.now().plusMinutes(SAFETY_RESCHEDULE_MINUTES_VALUE);
		reschedule(safetyTime);

		logInfo(routineLogDailyMissionLine("Auto-schedule mode - safety reschedule at: " +
				safetyTime.format(DATETIME_FORMATTER)));
	}
}
