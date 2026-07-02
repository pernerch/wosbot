package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.service.LoggingService;
import dev.frostguard.engine.service.StaminaService;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.RegexNumberParser;
import dev.frostguard.vision.logging.ProfileContextLogger;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;

import java.time.Duration;
import java.time.LocalDateTime;

// Orchestrates stamina tracking: OCR reads, regen delay computation,
// availability gating, and travel time parsing.
public class StaminaHelper {

    @FunctionalInterface
    public interface RescheduleCallback {
        void reschedule(LocalDateTime time);
    }

    private final EmulatorController device;
    private final String deviceSlot;
    private final ResilientOcrExecutor<Integer> numberReader;
    private final ResilientOcrExecutor<Duration> durationReader;
    private final StaminaService persistence;
    private final Long accountKey;
    private final ProfileContextLogger trace;
    private final MarchHelper marchSupport;
    private final String accountLabel;
    private final LoggingService centralLog;

    public StaminaHelper(EmulatorController emuManager, String emulatorNumber,
                         ResilientOcrExecutor<Integer> integerHelper,
                         ResilientOcrExecutor<Duration> durationHelper,
                         AccountDescriptor profile, MarchHelper marchHelper) {
        this.device = emuManager;
        this.deviceSlot = emulatorNumber;
        this.numberReader = integerHelper;
        this.durationReader = durationHelper;
        this.persistence = StaminaService.getServices();
        this.accountKey = profile.getId();
        this.trace = new ProfileContextLogger(StaminaHelper.class, profile);
        this.marchSupport = marchHelper;
        this.accountLabel = profile.getName();
        this.centralLog = LoggingService.obtain();
    }

    // Opens avatar screen, reads stamina via OCR, persists, then navigates back.
    // Changed by pernerch | Date: 2026-07-02 | Why: add timeout guards to prevent stamina reads from blocking for 1+ seconds when OCR is slow or UI is unresponsive.
    public void updateStaminaFromProfile() {
        emitDebug("Opening profile to read stamina");
        long startMs = System.currentTimeMillis();
        long maxDurationMs = 3000; // 3 second timeout for entire operation

        try {
            // Touch profile avatar (timeout guard: skip if over 1 sec elapsed)
            if (System.currentTimeMillis() - startMs < 1000) {
                device.touchArea(deviceSlot,
                        CommonGameAreas.PROFILE_AVATAR.topLeft(),
                        CommonGameAreas.PROFILE_AVATAR.bottomRight(), 1, 200);
            } else {
                emitWarn("Stamina read timeout: profile open exceeded 1s, aborting");
                return;
            }

            // Touch stamina button (timeout guard)
            if (System.currentTimeMillis() - startMs < 1500) {
                device.touchArea(deviceSlot,
                        CommonGameAreas.STAMINA_BUTTON.topLeft(),
                        CommonGameAreas.STAMINA_BUTTON.bottomRight(), 1, 200);
            } else {
                emitWarn("Stamina read timeout: stamina button click exceeded 1.5s, aborting");
                device.pressBack(deviceSlot);
                return;
            }

            // Reduced OCR attempts (3 instead of 5) with shorter delay (100ms instead of 200ms)
            // to avoid blocking for 1+ second when UI is sluggish.
            Integer reading = numberReader.attemptRecognition(
                    CommonGameAreas.STAMINA_OCR_AREA.topLeft(),
                    CommonGameAreas.STAMINA_OCR_AREA.bottomRight(),
                    3, 100L,
                    CommonOCRSettings.STAMINA_FRACTION_SETTINGS,
                    RegexNumberParser::hasFractionSyntax,
                    RegexNumberParser::numerator);

            if (reading == null) {
                emitWarn("OCR could not parse stamina (elapsed " + (System.currentTimeMillis() - startMs) + "ms)");
            } else {
                emitInfo("Stamina read: " + reading);
                persistence.setStamina(accountKey, reading);
            }
        } catch (Exception ex) {
            emitWarn("Stamina update error: " + ex.getMessage());
        } finally {
            // Safety navigation back
            try {
                device.pressBack(deviceSlot);
                device.pressBack(deviceSlot);
            } catch (Exception ex) {
                emitDebug("Press-back cleanup error: " + ex.getMessage());
            }
        }
    }

    // Reads stamina cost from the deployment confirmation screen.
    // Changed by pernerch | Date: 2026-07-02 | Why: reduce OCR attempts (3 instead of 5) to match deployment screen speed expectations.
    public Integer getSpentStamina() {
        Integer cost = numberReader.attemptRecognition(
                CommonGameAreas.SPENT_STAMINA_OCR_AREA.topLeft(),
                CommonGameAreas.SPENT_STAMINA_OCR_AREA.bottomRight(),
                3, 100L,
                CommonOCRSettings.SPENT_STAMINA_SETTINGS,
                txt -> RegexNumberParser.conformsTo(txt, CommonOCRSettings.NUMBER_PATTERN),
                txt -> RegexNumberParser.extractByPattern(txt, CommonOCRSettings.NUMBER_PATTERN));

        emitDebug(cost != null ? "Deployment cost: " + cost : "Deployment cost OCR failed");
        return cost;
    }

    public void subtractStamina(Integer spent, boolean rally) {
        int deduction;
        if (spent != null) {
            deduction = spent;
        } else {
            deduction = rally ? 25 : 10;
        }
        emitDebug("Deducting " + deduction + " (current " + persistence.getCurrentStamina(accountKey) + ")");
        persistence.subtractStamina(accountKey, deduction);
    }

    public void addStamina(Integer amount) {
        if (amount == null) return;
        emitDebug("Crediting " + amount + " (current " + persistence.getCurrentStamina(accountKey) + ")");
        persistence.addStamina(accountKey, amount);
    }

    public int getCurrentStamina() {
        return persistence.getCurrentStamina(accountKey);
    }

    // Computes minutes needed for stamina to regenerate from current to target.
    public int staminaRegenerationTime(int current, int target) {
        if (current >= target) return 0;
        int deficit = target - current;
        int waitMinutes = deficit * 5;
        emitDebug(deficit + " points deficit → " + waitMinutes + " min wait");
        return waitMinutes;
    }

    // Validates stamina and optionally march slots; reschedules on failure.
    // If verifyMarches is true, also checks march availability.
    public boolean checkStaminaAndMarchesOrReschedule(
            int min, int refresh, RescheduleCallback cb) {
        return verifyReadiness(min, refresh, cb, true);
    }

    public boolean checkStaminaOrReschedule(
            int min, int refresh, RescheduleCallback cb) {
        return verifyReadiness(min, refresh, cb, false);
    }

    private boolean verifyReadiness(int min, int refresh,
                                    RescheduleCallback cb, boolean verifyMarches) {
        int level = persistence.getCurrentStamina(accountKey);
        emitInfo("Stamina check: " + level);

        if (level < min) {
            int regenMinutes = staminaRegenerationTime(level, refresh);
            LocalDateTime retry = LocalDateTime.now().plusMinutes(regenMinutes);
            cb.reschedule(retry);
                emitWarn("Insufficient (" + level + "/" + min + ") - retry " +
                    GameTimeUtils.formatCountdown(retry));
            return false;
        }

        if (verifyMarches && !marchSupport.checkMarchesAvailable()) {
            cb.reschedule(LocalDateTime.now().plusMinutes(1));
            emitWarn("No march slots - retry in 1 min");
            return false;
        }

        return true;
    }

    // Reads travel-time from the deployment screen; returns seconds or 0 on failure.
    // Changed by pernerch | Date: 2026-07-02 | Why: reduce OCR delay to 100ms to prevent deployment screen hangs.
    public long parseTravelTime() {
        Duration parsed = durationReader.attemptRecognition(
                CommonGameAreas.TRAVEL_TIME_OCR_AREA.topLeft(),
                CommonGameAreas.TRAVEL_TIME_OCR_AREA.bottomRight(),
                3, 100L,
                CommonOCRSettings.TRAVEL_TIME_SETTINGS,
                GameTimeUtils::isAcceptedFormat,
                GameTimeUtils::parseDuration);

        if (parsed == null) {
            emitWarn("Travel time OCR failed");
            return 0;
        }
        long seconds = parsed.getSeconds();
        emitDebug("Travel estimate: " + seconds + "s");
        return seconds;
    }

    // ── logging shortcuts ────────────────────────────────────────────

    private void emitInfo(String msg) {
        String full = accountLabel + " - " + msg;
        trace.info(full);
        centralLog.emit(TpMessageSeverityEnum.INFO, "StaminaHelper", accountLabel, msg);
    }

    private void emitWarn(String msg) {
        String full = accountLabel + " - " + msg;
        trace.warn(full);
        centralLog.emit(TpMessageSeverityEnum.WARNING, "StaminaHelper", accountLabel, msg);
    }

    private void emitDebug(String msg) {
        String full = accountLabel + " - " + msg;
        trace.debug(full);
        centralLog.emit(TpMessageSeverityEnum.DEBUG, "StaminaHelper", accountLabel, msg);
    }
}
