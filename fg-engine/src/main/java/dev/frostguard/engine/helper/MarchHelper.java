package dev.frostguard.engine.helper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.MarchSlotStatus;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.nav.RallyFlagCoordinates;
import dev.frostguard.vision.logging.ProfileContextLogger;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;

// Handles march-slot availability checks, rally flag interaction,
// and left-panel menu toggling for deployment workflows.
public class MarchHelper {

    private static final int SLOT_COUNT = 6;
    private static final int LOCKED_FLAG_THRESHOLD = 88;
    private static final int MAX_FLAG_SLOTS = 6;
    private static final int FLAG_SLOT_TOLERANCE_PX = 45;
    private static final Pattern TIMER_PATTERN = Pattern.compile("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b");
    private static final PointData MARCH_RECALL_CONFIRM_TOP_LEFT = new PointData(446, 780);
    private static final PointData MARCH_RECALL_CONFIRM_BOTTOM_RIGHT = new PointData(578, 800);
    private static final AreaData MARCH_QUEUE_AREA = new AreaData(new PointData(10, 342), new PointData(435, 772));

    private final EmulatorController emu;
    private final String device;
    private final ResilientOcrExecutor<String> ocrStrings;
    private final ProfileContextLogger log;
    private final TemplateSearchHelper templateSearch;

    public MarchHelper(EmulatorController emuManager, String emulatorNumber,
                       ResilientOcrExecutor<String> stringHelper, AccountDescriptor profile) {
        this.emu = emuManager;
        this.device = emulatorNumber;
        this.ocrStrings = stringHelper;
        this.log = new ProfileContextLogger(MarchHelper.class, profile);
        this.templateSearch = new TemplateSearchHelper(emuManager, emulatorNumber, profile);
    }

    public boolean checkMarchesAvailable() {
        boolean anyIdle = readMarchQueue().stream().anyMatch(MarchSlotState::isIdle);
        if (!anyIdle) {
            log.info("No idle march slot");
        }
        return anyIdle;
    }

    // Reads every March Queue row from a single screenshot. Text is deliberately avoided: the status
    // line is classified by colour (white "Idle", orange "Unlock", red "Unavailable", nothing at all
    // for stationed troops) and the activity by its icon. Only the countdown needs OCR.
    public List<MarchSlotState> readMarchQueue() {
        openLeftMenuCitySection(false);
        try {
            List<MarchSlotState> slots = new ArrayList<>(SLOT_COUNT);
            for (int index = 0; index < SLOT_COUNT; index++) {
                slots.add(readSlot(index));
            }
            log.info("March queue: " + slots.stream()
                    .map(slot -> "#" + slot.slot() + "=" + slot.status()
                            + (slot.countdown() == null ? "" : "(" + slot.countdown() + ")"))
                    .collect(Collectors.joining(" ")));
            return slots;
        } catch (Exception ex) {
            log.error("March queue read error: " + ex.getMessage());
            return List.of();
        } finally {
            dismissLeftPanel();
        }
    }

    // Detects currently usable march capacity from the left march queue panel.
    // A slot counts when it is not LOCKED.
    public int detectUsableMarchSlots() {
        int usableSlots = (int) readMarchQueue().stream()
            .filter(slot -> slot.status().countsTowardsCapacity())
                .count();
        log.info("Detected usable march slots: " + usableSlots);
        return usableSlots;
    }

    // Counts only usable march slots that are currently occupied.
    public int countOccupiedUsableMarchSlots() {
        int occupiedSlots = (int) readMarchQueue().stream()
            .filter(slot -> slot.status().countsTowardsCapacity())
                .filter(slot -> slot.status() != MarchSlotStatus.IDLE)
                .count();
        log.info("Detected occupied usable march slots: " + occupiedSlots);
        return occupiedSlots;
    }

    // Recalls all active marches shown in the march queue panel.
    public int recallAllActiveMarches() {
        openLeftMenuCitySection(false);
        int recalled = 0;

        try {
            while (true) {
                List<ImageSearchResultData> recallButtons = templateSearch.locateAllPatterns(
                        TemplatesEnum.MARCHES_AREA_RECALL_BUTTON,
                        SearchConfig.builder()
                                .withArea(MARCH_QUEUE_AREA)
                                .withMaxAttempts(2)
                                .withDelay(150L)
                                .withMaxResults(6)
                                .build());

                if (recallButtons == null || recallButtons.isEmpty()) {
                    break;
                }

                ImageSearchResultData button = recallButtons.get(0);
                if (button == null || button.getPoint() == null) {
                    break;
                }

                emu.touchPoint(device, button.getPoint());
                interruptibleWait(200);
                emu.touchArea(device, MARCH_RECALL_CONFIRM_TOP_LEFT, MARCH_RECALL_CONFIRM_BOTTOM_RIGHT, 1, 200);
                recalled++;
                interruptibleWait(300);
            }

            log.info("Recalled active marches for capacity detection: " + recalled);
            return recalled;
        } finally {
            dismissLeftPanel();
        }
    }

    private MarchSlotState readSlot(int index) {
        AreaData statusArea = CommonGameAreas.MARCH_QUEUE_STATUS[index];
        AreaData timerArea = CommonGameAreas.MARCH_QUEUE_TIMER[index];
        AreaData iconArea = CommonGameAreas.MARCH_QUEUE_ICON[index];

        String statusText = ocrStrings.attemptRecognition(
                statusArea.topLeft(),
                statusArea.bottomRight(),
                2,
                120L,
                null,
                text -> text != null,
                text -> text);

        String timerText = ocrStrings.attemptRecognition(
                timerArea.topLeft(),
                timerArea.bottomRight(),
                2,
                120L,
                CommonOCRSettings.MARCH_QUEUE_TIMER_SETTINGS,
                text -> text != null,
                text -> text);

        Duration countdown = parseCountdown(timerText);
        MarchSlotStatus status = classifyStatus(statusText, countdown, iconArea);
        return new MarchSlotState(index + 1, status, countdown);
    }

    private MarchSlotStatus classifyStatus(String statusText, Duration countdown, AreaData iconArea) {
        String normalized = statusText == null ? "" : statusText.toLowerCase(Locale.ENGLISH);
        if (normalized.contains("unlock") || normalized.contains("unavailable")) {
            return MarchSlotStatus.LOCKED;
        }
        if (normalized.contains("idle")) {
            return MarchSlotStatus.IDLE;
        }
        if (countdown != null) {
            if (isReturningIcon(iconArea)) {
                return MarchSlotStatus.RETURNING;
            }
            if (normalized.contains("gather")) {
                return MarchSlotStatus.GATHERING;
            }
            return MarchSlotStatus.BUSY_UNKNOWN;
        }
        return MarchSlotStatus.STATIONED;
    }

    private boolean isReturningIcon(AreaData iconArea) {
        ImageSearchResultData icon = templateSearch.locatePattern(
                TemplatesEnum.MARCH_QUEUE_RETURNING_ICON,
                SearchConfig.builder()
                        .withArea(iconArea)
                        .withThreshold(88)
                        .withMaxAttempts(1)
                        .build());
        return icon != null && icon.isFound();
    }

    private Duration parseCountdown(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        java.util.regex.Matcher matcher = TIMER_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String timer = matcher.group();
        String[] parts = timer.split(":");
        try {
            if (parts.length == 2) {
                long minutes = Long.parseLong(parts[0]);
                long seconds = Long.parseLong(parts[1]);
                return Duration.ofMinutes(minutes).plusSeconds(seconds);
            }
            if (parts.length == 3) {
                long hours = Long.parseLong(parts[0]);
                long minutes = Long.parseLong(parts[1]);
                long seconds = Long.parseLong(parts[2]);
                return Duration.ofHours(hours).plusMinutes(minutes).plusSeconds(seconds);
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    public boolean selectFlag(Integer flagNumber) {
        if (flagNumber == null) {
            log.debug("No flag — skipping");
            return true;
        }
        if (isFlagLocked(flagNumber)) {
            log.warn("Flag #" + flagNumber + " shows a padlock — not selecting it");
            return false;
        }
        log.debug("Selecting flag #" + flagNumber);
        emu.touchPoint(device, RallyFlagCoordinates.pointForFlag(flagNumber));
        interruptibleWait(300);
        return true;
    }

    // Locating every padlock across the strip and mapping each to its nearest slot is immune to the
    // few pixels of tile drift; a per-slot window would leave a 58px template barely any room to slide.
    private boolean isFlagLocked(int flagNumber) {
        int slotX = RallyFlagCoordinates.pointForFlag(flagNumber).getX();
        List<ImageSearchResultData> padlocks = emu.locateAllPatterns(device,
                TemplatesEnum.RALLY_LOCKED_FLAG_SLOT,
                CommonGameAreas.RALLY_FLAG_BAR.topLeft(),
                CommonGameAreas.RALLY_FLAG_BAR.bottomRight(),
                LOCKED_FLAG_THRESHOLD, MAX_FLAG_SLOTS);

        // The multi-hit matcher logs nothing of its own, so record what it saw.
        log.debug("Flag bar: " + padlocks.size() + " padlock(s) located while checking flag #" + flagNumber);

        for (ImageSearchResultData padlock : padlocks) {
            if (Math.abs(padlock.getPoint().getX() - slotX) <= FLAG_SLOT_TOLERANCE_PX) {
                log.info("Flag #" + flagNumber + " padlocked at " + padlock.getPoint()
                        + " score=" + padlock.getMatchScore());
                return true;
            }
        }
        return false;
    }

    public void openLeftMenuCitySection(boolean cityTab) {
        log.debug("Left menu — " + (cityTab ? "city" : "wilderness"));
        emu.touchArea(device, CommonGameAreas.LEFT_MENU_TRIGGER.topLeft(),
                CommonGameAreas.LEFT_MENU_TRIGGER.bottomRight(), 3, 400);
        if (cityTab) {
            emu.touchArea(device, CommonGameAreas.LEFT_MENU_CITY_TAB.topLeft(),
                    CommonGameAreas.LEFT_MENU_CITY_TAB.bottomRight(), 3, 100);
        } else {
            emu.touchArea(device, CommonGameAreas.LEFT_MENU_WILDERNESS_TAB.topLeft(),
                    CommonGameAreas.LEFT_MENU_WILDERNESS_TAB.bottomRight(), 3, 100);
        }
    }

    // Closes the left panel via two sequential touch points.
    public void closeLeftMenu() {
        dismissLeftPanel();
    }

    private void dismissLeftPanel() {
        log.debug("Closing left menu");
        emu.touchPoint(device, CommonGameAreas.LEFT_MENU_CLOSE_CITY);
        interruptibleWait(500);
        emu.touchPoint(device, CommonGameAreas.LEFT_MENU_CLOSE_OUTSIDE);
        interruptibleWait(500);
    }

    private void interruptibleWait(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
