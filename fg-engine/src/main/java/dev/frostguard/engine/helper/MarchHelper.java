package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.nav.RallyFlagCoordinates;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.vision.logging.ProfileContextLogger;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import dev.frostguard.vision.ocr.TesseractOcrProvider;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

// Handles march-slot availability checks, rally flag interaction,
// and left-panel menu toggling for deployment workflows.
public class MarchHelper {

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
            RawImageData frame = emu.captureScreen(device);
            BufferedImage image = TesseractOcrProvider.toBufferedImage(frame);

            List<MarchSlotState> slots = new ArrayList<>(SLOT_COUNT);
            for (int index = 0; index < SLOT_COUNT; index++) {
                slots.add(readSlot(frame, image, index));
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
    // A slot counts when OCR can read either Idle or a time string.
    public int detectUsableMarchSlots() {
        openLeftMenuCitySection(false);
        int usableSlots = 0;

        try {
            for (int i = 0; i < CommonGameAreas.MARCH_SLOTS_TOP_LEFT.length; i++) {
                PointData tl = CommonGameAreas.MARCH_SLOTS_TOP_LEFT[i];
                PointData br = CommonGameAreas.MARCH_SLOTS_BOTTOM_RIGHT[i];
                String raw = readSlotText(tl, br);
                if (isUnlockedSlot(raw)) {
                    usableSlots++;
                }
            }
            log.info("Detected usable march slots: " + usableSlots);
            return usableSlots;
        } finally {
            dismissLeftPanel();
        }
    }

    // Counts only usable march slots that are currently occupied.
    public int countOccupiedUsableMarchSlots() {
        openLeftMenuCitySection(false);
        int occupiedSlots = 0;

        try {
            for (int i = 0; i < CommonGameAreas.MARCH_SLOTS_TOP_LEFT.length; i++) {
                PointData tl = CommonGameAreas.MARCH_SLOTS_TOP_LEFT[i];
                PointData br = CommonGameAreas.MARCH_SLOTS_BOTTOM_RIGHT[i];
                String raw = readSlotText(tl, br);
                if (!isUnlockedSlot(raw)) {
                    continue;
                }
                if (!isIdleSlot(raw)) {
                    occupiedSlots++;
                }
            }
            log.info("Detected occupied usable march slots: " + occupiedSlots);
            return occupiedSlots;
        } finally {
            dismissLeftPanel();
        }
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

    private boolean attemptSlotOcr(PointData topLeft, PointData bottomRight) {
        int attempt = 0;
        while (attempt < 3) {
            try {
                String text = emu.readText(device, topLeft, bottomRight);
                if (text != null && text.toLowerCase().contains("idle")) return true;
                if (attempt < 2) Thread.sleep(100);
            } catch (IOException | TesseractException ex) {
                log.debug("Slot OCR #" + (attempt + 1) + " failed: " + ex.getMessage());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
            attempt++;
        }
        return false;
    }

    private String readSlotText(PointData topLeft, PointData bottomRight) {
        int attempt = 0;
        while (attempt < 3) {
            try {
                String text = emu.readText(device, topLeft, bottomRight);
                if (text != null && !text.isBlank()) {
                    return text;
                }
                if (attempt < 2) {
                    Thread.sleep(100);
                }
            } catch (IOException | TesseractException ex) {
                log.debug("Slot text OCR #" + (attempt + 1) + " failed: " + ex.getMessage());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "";
            }
            attempt++;
        }
        return "";
    }

    private boolean isUnlockedSlot(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ENGLISH);
        if (normalized.contains("unlock") || normalized.contains("unavailable")) {
            return false;
        }
        return isIdleSlot(normalized) || TIMER_PATTERN.matcher(normalized).find();
    }

    private boolean isIdleSlot(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ENGLISH);
        if (normalized.contains("idle")) {
            return true;
        }
        return false;
    }

    public void selectFlag(Integer flagNumber) {
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
