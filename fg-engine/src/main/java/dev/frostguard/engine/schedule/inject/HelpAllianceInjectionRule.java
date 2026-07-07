package dev.frostguard.engine.schedule.inject;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.HelpOnlyModeSettings;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.service.ConfigService;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Taps the Alliance Help shortcut button when it appears on the main
 * screen.  Runs cooperatively during a task's idle sleep window and
 * respects the per-profile enable flag.
 */
public class HelpAllianceInjectionRule implements InjectionRule {

    private static final int TEMPLATE_THRESHOLD = 90;
    private static final int MAX_RECOVERY_BACK_PRESSES = 3;
    private static final int TAP_JITTER_RADIUS_PX = 10;
    private static final int SCREEN_MIN_X = 15;
    private static final int SCREEN_MAX_X = 705;
    private static final int SCREEN_MIN_Y = 15;
    private static final int SCREEN_MAX_Y = 1265;

    private static final SecureRandom HUMAN_RANDOM = new SecureRandom();
    private static final Map<Long, Long> NEXT_ALLOWED_TAP_EPOCH_MS = new ConcurrentHashMap<>();

    @Override
    public boolean shouldInject(EmulatorController controller,
                                AccountDescriptor profile,
                                RawImageData frame) {
        if (!isHelpEnabledForProfile(profile)) {
            return false;
        }
        if (!isTapWindowOpen(profile.getId())) {
            return false;
        }
        try {
            boolean found = controller.locatePattern(
                    profile.getEmulatorNumber(), frame,
                    TemplatesEnum.GAME_HOME_SHORTCUTS_HELP_REQUEST2, TEMPLATE_THRESHOLD).isFound();
            if (found) {
                scheduleNextTapWindow(profile.getId());
            }
            return found;
        } catch (Exception ignored) { return false; }
    }

    @Override
    public void executeInjection(EmulatorController controller,
                                 AccountDescriptor profile,
                                 DelayedTask activeTask) {
        activeTask.logDebug("Attempting Alliance Help tap");
        String devIdx = profile.getEmulatorNumber();
        try {
            ImageSearchResultData result = controller.locatePattern(
                    devIdx,
                    TemplatesEnum.GAME_HOME_SHORTCUTS_HELP_REQUEST2, TEMPLATE_THRESHOLD);
            if (result.isFound()) {
                PointData humanizedTapPoint = humanizeTapPoint(result.getPoint());
                controller.touchPoint(devIdx, humanizedTapPoint);
                pause(randomRange(420, 980));

                // Changed by pernerch | Date: 2026-07-04 | Why: prevent Alliance Help shortcut taps
                // from leaving automation stuck in chat/overlay screens by enforcing a quick back-out recovery.
                recoverIfNotOnMainScreen(controller, activeTask, devIdx);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            activeTask.logError("Alliance Help tap was interrupted.", ex);
        } catch (RuntimeException ex) {
            activeTask.logError("Alliance Help tap encountered an error.", ex);
        }
    }

    private void recoverIfNotOnMainScreen(EmulatorController controller,
                                          DelayedTask activeTask,
                                          String devIdx) throws InterruptedException {
        if (isOnMainScreen(controller, devIdx)) {
            return;
        }

        activeTask.logDebug("Alliance Help tap opened non-main screen (possible chat). Attempting back recovery.");

        for (int i = 1; i <= MAX_RECOVERY_BACK_PRESSES; i++) {
            controller.pressBack(devIdx);
            pause(randomRange(240, 520));

            if (isOnMainScreen(controller, devIdx)) {
                activeTask.logDebug("Alliance Help recovery successful after back press " + i + ".");
                return;
            }
        }

        activeTask.logWarning("Alliance Help recovery could not confirm return to home/world after back recovery.");
    }

    private boolean isOnMainScreen(EmulatorController controller, String devIdx) {
        return isTemplateVisible(controller, devIdx, TemplatesEnum.GAME_HOME_FURNACE)
                || isTemplateVisible(controller, devIdx, TemplatesEnum.GAME_HOME_WORLD);
    }

    private boolean isTemplateVisible(EmulatorController controller,
                                      String devIdx,
                                      TemplatesEnum template) {
        try {
            return controller.locatePattern(devIdx, template, TEMPLATE_THRESHOLD).isFound();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void pause(long millis) throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(millis);
    }

    private boolean isHelpEnabledForProfile(AccountDescriptor profile) {
        boolean profileToggle = profile.getConfig(ConfigurationKeyEnum.ALLIANCE_HELP_BOOL, Boolean.class);
        if (profileToggle) {
            return true;
        }
        Map<String, String> globalConfig = ConfigService.obtain().loadGlobalSettings();
        return HelpOnlyModeSettings.isEnabledForProfile(globalConfig, profile.getId());
    }

    private boolean isTapWindowOpen(Long profileId) {
        if (profileId == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        long nextAllowed = NEXT_ALLOWED_TAP_EPOCH_MS.getOrDefault(profileId, 0L);
        return now >= nextAllowed;
    }

    private void scheduleNextTapWindow(Long profileId) {
        if (profileId == null) {
            return;
        }
        long delayMs = randomHumanDelayMs();
        NEXT_ALLOWED_TAP_EPOCH_MS.put(profileId, System.currentTimeMillis() + delayMs);
    }

    private long randomHumanDelayMs() {
        int base = randomRange(650, 1750);
        int occasionalPause = HUMAN_RANDOM.nextDouble() < 0.2 ? randomRange(350, 1100) : 0;
        return base + occasionalPause;
    }

    private PointData humanizeTapPoint(PointData center) {
        int deltaX = randomRange(-TAP_JITTER_RADIUS_PX, TAP_JITTER_RADIUS_PX);
        int deltaY = randomRange(-TAP_JITTER_RADIUS_PX, TAP_JITTER_RADIUS_PX);
        int targetX = clamp(center.getX() + deltaX, SCREEN_MIN_X, SCREEN_MAX_X);
        int targetY = clamp(center.getY() + deltaY, SCREEN_MIN_Y, SCREEN_MAX_Y);
        return new PointData(targetX, targetY);
    }

    private int randomRange(int minInclusive, int maxInclusive) {
        if (maxInclusive <= minInclusive) {
            return minInclusive;
        }
        return HUMAN_RANDOM.nextInt(minInclusive, maxInclusive + 1);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public String getRuleName() { return "HelpAllianceInjectionRule"; }
}
