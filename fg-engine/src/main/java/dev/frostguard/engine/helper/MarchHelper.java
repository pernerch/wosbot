package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.RallyFlagCoordinates;
import dev.frostguard.vision.logging.ProfileContextLogger;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import net.sourceforge.tess4j.TesseractException;

import java.io.IOException;
import java.util.Objects;

// Handles march-slot availability checks, rally flag interaction,
// and left-panel menu toggling for deployment workflows.
public class MarchHelper {

    private final EmulatorController emu;
    private final String device;
    private final ResilientOcrExecutor<String> ocrStrings;
    private final ProfileContextLogger log;

    public MarchHelper(EmulatorController emuManager, String emulatorNumber,
                       ResilientOcrExecutor<String> stringHelper, AccountDescriptor profile) {
        this.emu = emuManager;
        this.device = emulatorNumber;
        this.ocrStrings = stringHelper;
        this.log = new ProfileContextLogger(MarchHelper.class, profile);
    }

    // Scans all 6 march slots via OCR; returns true on first idle slot found.
    public boolean checkMarchesAvailable() {
        openLeftMenuCitySection(false);
        int remaining = 6;
        try {
            while (remaining > 0) {
                int slotLabel = remaining;
                remaining--;
                PointData tl = CommonGameAreas.MARCH_SLOTS_TOP_LEFT[6 - slotLabel];
                PointData br = CommonGameAreas.MARCH_SLOTS_BOTTOM_RIGHT[6 - slotLabel];
                if (attemptSlotOcr(tl, br)) {
                    log.info("Idle slot: #" + slotLabel);
                    dismissLeftPanel();
                    return true;
                }
                log.debug("Slot #" + slotLabel + " busy");
            }
        } catch (Exception ex) {
            log.error("March scan error: " + ex.getMessage());
            dismissLeftPanel();
            return false;
        }
        log.info("All 6 slots occupied");
        dismissLeftPanel();
        return false;
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

    public void selectFlag(Integer flagNumber) {
        if (flagNumber == null) {
            log.debug("No flag — skipping");
            return;
        }
        log.debug("Selecting flag #" + flagNumber);
        emu.touchPoint(device, RallyFlagCoordinates.pointForFlag(flagNumber));
        interruptibleWait(300);

        String status = ocrStrings.attemptRecognition(
                CommonGameAreas.FLAG_UNLOCK_TEXT_OCR.topLeft(),
                CommonGameAreas.FLAG_UNLOCK_TEXT_OCR.bottomRight(),
                3, 200L, null, Objects::nonNull, text -> text);

        if (status == null) {
            log.debug("Flag #" + flagNumber + " confirmed");
            return;
        }
        if (!status.toLowerCase().contains("unlock")) {
            log.debug("Flag #" + flagNumber + " confirmed");
            return;
        }
        log.warn("Flag #" + flagNumber + " locked — backing out");
        emu.pressBack(device);
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
