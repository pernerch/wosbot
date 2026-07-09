package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.RallyFlagCoordinates;
import dev.frostguard.vision.logging.ProfileContextLogger;
import net.sourceforge.tess4j.TesseractException;

import java.io.IOException;
import java.util.List;

// Handles march-slot availability checks, rally flag interaction,
// and left-panel menu toggling for deployment workflows.
public class MarchHelper {

    private static final int MAX_FLAG_SLOTS = 8;
    // A padlock matches its own icon at 98-100%; an unlocked slot never exceeds ~37%. Half a slot of
    // tolerance absorbs the tile drift without ever reaching a neighbouring slot (~74px apart).
    private static final double LOCKED_FLAG_THRESHOLD = 85;
    private static final int FLAG_SLOT_TOLERANCE_PX = 35;

    private final EmulatorController emu;
    private final String device;
    private final ProfileContextLogger log;

    public MarchHelper(EmulatorController emuManager, String emulatorNumber, AccountDescriptor profile) {
        this.emu = emuManager;
        this.device = emulatorNumber;
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

    // A locked slot wears a padlock, so it is recognised before it is tapped. The previous check read
    // the unlock prompt with OCR after tapping and treated anything that was not the word "unlock" -
    // garbage included - as a confirmation, which let locked flags through.
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
