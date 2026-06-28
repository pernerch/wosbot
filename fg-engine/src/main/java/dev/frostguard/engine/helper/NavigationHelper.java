package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.error.HomeNotFoundException;
import dev.frostguard.engine.error.ProfileInReconnectStateException;
import dev.frostguard.engine.nav.ButtonConstants;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.LoggingService;
import dev.frostguard.vision.logging.ProfileContextLogger;

// Screen location verification and cross-screen navigation for
// primary game views and auxiliary menus.
public class NavigationHelper {

    private final TemplateSearchHelper searcher;
    private final EmulatorController emu;
    private final String device;
    private final ProfileContextLogger log;
    private final String accountName;
    private final LoggingService logs;

    public NavigationHelper(EmulatorController emuManager, String emulatorNumber,
                            AccountDescriptor profile) {
        this.emu = emuManager;
        this.device = emulatorNumber;
        this.searcher = new TemplateSearchHelper(emuManager, emulatorNumber, profile);
        this.log = new ProfileContextLogger(NavigationHelper.class, profile);
        this.accountName = profile.getName();
        this.logs = LoggingService.obtain();
    }

    // ── alliance menu ────────────────────────────────────────────────

    public boolean navigateToAllianceMenu(AllianceMenu menu) {
        emu.touchArea(device,
                ButtonConstants.BOTTOM_MENU_ALLIANCE_BUTTON.topLeft(),
                ButtonConstants.BOTTOM_MENU_ALLIANCE_BUTTON.bottomRight());

        TemplatesEnum tpl;
        if (menu == AllianceMenu.WAR) tpl = TemplatesEnum.ALLIANCE_WAR_BUTTON;
        else if (menu == AllianceMenu.CHESTS) tpl = TemplatesEnum.ALLIANCE_CHEST_BUTTON;
        else if (menu == AllianceMenu.TERRITORY) tpl = TemplatesEnum.ALLIANCE_TERRITORY_BUTTON;
        else if (menu == AllianceMenu.SHOP) tpl = TemplatesEnum.ALLIANCE_SHOP_BUTTON;
        else if (menu == AllianceMenu.TECH) tpl = TemplatesEnum.ALLIANCE_TECH_BUTTON;
        else if (menu == AllianceMenu.HELP) tpl = TemplatesEnum.ALLIANCE_HELP_BUTTON;
        else tpl = TemplatesEnum.ALLIANCE_TRIUMPH_BUTTON;

        ImageSearchResultData hit = searcher.locatePattern(tpl,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!hit.isFound()) return false;
        emu.touchArea(device, hit.getPoint(), hit.getPoint(), 1, 1000);
        return true;
    }

    // ── event menu ───────────────────────────────────────────────────

    public boolean navigateToEventMenu(EventMenu event) {
        broadcastInfo("Navigating to " + event.name());

        // open the events panel
        ImageSearchResultData evtBtn = searcher.locatePattern(
                TemplatesEnum.HOME_EVENTS_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!evtBtn.isFound()) {
            broadcastWarn("Events panel missed");
            return false;
        }
        emu.touchPoint(device, evtBtn.getPoint());
        interruptibleWait(2000);

        // clear existing selection
        emu.touchArea(device, new PointData(529, 27), new PointData(635, 63), 5, 300);
        interruptibleWait(300);

        TemplatesEnum tpl = switch (event) {
            case HERO_MISSION -> TemplatesEnum.HERO_MISSION_EVENT_TAB;
            case MERCENARY -> TemplatesEnum.MERCENARY_EVENT_TAB;
            case ALLIANCE_CHAMPIONSHIP -> TemplatesEnum.ALLIANCE_CHAMPIONSHIP_TAB;
            case ALLIANCE_MOBILIZATION -> TemplatesEnum.ALLIANCE_MOBILIZATION_TAB;
            case TUNDRA_TRUCK -> TemplatesEnum.TUNDRA_TRUCK_TAB;
        };

        // search with swipe-left reset then swipe-right scanning
        ImageSearchResultData tab = searcher.locatePattern(tpl, SearchConfigConstants.DEFAULT_SINGLE);
        if (!tab.isFound()) {
            PointData swipeFrom = new PointData(80, 120);
            PointData swipeTo = new PointData(578, 130);
            for (int i = 0; i < 3; i++) { emu.swipeScreen(device, swipeFrom, swipeTo); interruptibleWait(400); }

            PointData rFrom = new PointData(630, 143);
            PointData rTo = new PointData(400, 128);
            int sweeps = 0;
            while (sweeps < 5 && !tab.isFound()) {
                tab = searcher.locatePattern(tpl, SearchConfigConstants.DEFAULT_SINGLE);
                if (tab.isFound()) break;
                emu.swipeScreen(device, rFrom, rTo);
                interruptibleWait(400);
                sweeps++;
            }
        }

        // fallback for mobilization unselected variant
        if (!tab.isFound() && event == EventMenu.ALLIANCE_MOBILIZATION) {
            broadcastDebug("Trying unselected mobilization tab");
            tab = searcher.locatePattern(TemplatesEnum.ALLIANCE_MOBILIZATION_UNSELECTED_TAB,
                    SearchConfigConstants.DEFAULT_SINGLE);
        }

        if (!tab.isFound()) {
            broadcastWarn("Tab not found: " + event);
            return false;
        }

        emu.touchPoint(device, tab.getPoint());
        interruptibleWait(1000);
        broadcastInfo("Reached " + event.name());
        return true;
    }

    public void clearEventTabSelection() {
        emu.touchArea(device, new PointData(529, 27), new PointData(635, 63), 5, 300);
        interruptibleWait(300);
    }

    // ── screen location ──────────────────────────────────────────────

    public void ensureCorrectScreenLocation(LaunchPoint target) {
        broadcastDebug("Locating screen — need " + target);
        int budget = 10;
        int pass = 1;
        while (pass <= budget) {
            // detect reconnect
            if (searcher.locatePattern(TemplatesEnum.GAME_HOME_RECONNECT,
                    SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
                throw new ProfileInReconnectStateException(accountName + " in reconnect state");
            }

            boolean atHome = searcher.locatePattern(TemplatesEnum.GAME_HOME_FURNACE,
                    SearchConfigConstants.DEFAULT_SINGLE).isFound();
            boolean atWorld = !atHome && searcher.locatePattern(TemplatesEnum.GAME_HOME_WORLD,
                    SearchConfigConstants.DEFAULT_SINGLE).isFound();

            // check if already at desired location
            if (target == LaunchPoint.ANY && (atHome || atWorld)) return;
            if (target == LaunchPoint.HOME && atHome) return;
            if (target == LaunchPoint.WORLD && atWorld) return;

            // try to navigate to desired location
            if (target == LaunchPoint.HOME && atWorld) {
                ImageSearchResultData w = searcher.locatePattern(TemplatesEnum.GAME_HOME_WORLD,
                        SearchConfigConstants.DEFAULT_SINGLE);
                if (w.isFound()) {
                    emu.touchPoint(device, w.getPoint());
                    interruptibleWait(2000);
                    if (searcher.locatePattern(TemplatesEnum.GAME_HOME_FURNACE,
                            SearchConfigConstants.DEFAULT_SINGLE).isFound()) return;
                }
            } else if (target == LaunchPoint.WORLD && atHome) {
                ImageSearchResultData h = searcher.locatePattern(TemplatesEnum.GAME_HOME_FURNACE,
                        SearchConfigConstants.DEFAULT_SINGLE);
                if (h.isFound()) {
                    emu.touchPoint(device, h.getPoint());
                    interruptibleWait(2000);
                    if (searcher.locatePattern(TemplatesEnum.GAME_HOME_WORLD,
                            SearchConfigConstants.DEFAULT_SINGLE).isFound()) return;
                }
            }

            // unknown screen — go back
            if (!atHome && !atWorld) {
                broadcastDebug("Unknown screen — back (" + pass + "/" + budget + ")");
                emu.pressBack(device);
                interruptibleWait(300);
            }
            pass++;
        }
        throw new HomeNotFoundException("Home not found after " + budget + " attempts");
    }

    // ── logging shortcuts ────────────────────────────────────────────

    private void broadcastInfo(String msg) {
        log.info(accountName + " - " + msg);
        logs.emit(TpMessageSeverityEnum.INFO, "NavigationHelper", accountName, msg);
    }

    private void broadcastWarn(String msg) {
        log.warn(accountName + " - " + msg);
        logs.emit(TpMessageSeverityEnum.WARNING, "NavigationHelper", accountName, msg);
    }

    private void broadcastDebug(String msg) {
        log.debug(accountName + " - " + msg);
        logs.emit(TpMessageSeverityEnum.DEBUG, "NavigationHelper", accountName, msg);
    }

    private void interruptibleWait(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private enum ScreenState { HOME, WORLD, RECONNECT, UNKNOWN }
    public enum AllianceMenu { WAR, CHESTS, TERRITORY, SHOP, TECH, HELP, TRIUMPH }
    public enum EventMenu { HERO_MISSION, MERCENARY, ALLIANCE_CHAMPIONSHIP, ALLIANCE_MOBILIZATION, TUNDRA_TRUCK }
}
