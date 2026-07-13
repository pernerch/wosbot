package dev.frostguard.tasks.dailies;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.api.configs.OcrDebugContext;
import dev.frostguard.api.configs.OcrDebugImageWriter;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;

public class ChiefOrderRoutine extends DelayedTask {

public enum ChiefOrderType {

		RUSH_JOB("Rush Job", 24, AreaData.of(421, 244, 555, 413)),


		URGENT_MOBILIZATION("Urgent Mobilization", 8, AreaData.of(145, 237, 282, 415)),


		PRODUCTIVITY_DAY("Productivity Day", 12, AreaData.of(143, 886, 275, 1057));

		private final String description;
		private final int cooldownHours;
		private final AreaData tapArea;

		ChiefOrderType(String description, int cooldownHours, AreaData tapArea) {
			this.description = description;
			this.cooldownHours = cooldownHours;
			this.tapArea = tapArea;
		}

		public String getDescription() {
			return description;
		}

		public int getCooldownHours() {
			return cooldownHours;
		}

		public AreaData getTapArea() {
			return tapArea;
		}
	}

private static final int ERROR_RETRY_MINUTES_VALUE = 10;
private static final int COOLDOWN_RETRY_BUFFER_SECONDS = 10;
private static final long ORDER_SCREEN_OPEN_WAIT_MS = 1800L;
private static final AreaData CHIEF_ORDER_SCREEN_AREA = new AreaData(
	new PointData(407, 208),
	new PointData(587, 428));
private static final AreaData CHIEF_ORDER_SCREEN_AREA_FALLBACK = new AreaData(
	new PointData(330, 180),
	new PointData(670, 520));
private static final PointData COOLDOWN_STATUS_TOP_LEFT = new PointData(399, 897);
private static final PointData COOLDOWN_STATUS_BOTTOM_RIGHT = new PointData(527, 946);
private static final Pattern COOLDOWN_TIMER_PATTERN = Pattern.compile("(\\d{1,2}:\\d{2}:\\d{2})");
private static final Pattern COOLDOWN_TEXT_PATTERN = Pattern.compile("on\\s*cooldown", Pattern.CASE_INSENSITIVE);
private static final TesseractSettingsData COOLDOWN_TEXT_OCR_SETTINGS = TesseractSettingsData.builder()
		.pageAnalysis(TesseractSettingsData.PageAnalysis.SINGLE_LINE)
		.recognitionEngine(TesseractSettingsData.RecognitionEngine.LSTM_ONLY)
		.allowedGlyphs("On cooldown:0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz ")
		.build();
private static final TesseractSettingsData COOLDOWN_TIMER_OCR_SETTINGS = TesseractSettingsData.builder()
		.pageAnalysis(TesseractSettingsData.PageAnalysis.SINGLE_LINE)
		.recognitionEngine(TesseractSettingsData.RecognitionEngine.LSTM_ONLY)
		.allowedGlyphs("0123456789:")
		.build();

private final ChiefOrderType chiefOrderType;
private boolean cooldownRescheduled = false;

public ChiefOrderRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask, ChiefOrderType chiefOrderType) {
		super(profile, tpTask);
		this.chiefOrderType = chiefOrderType;
	}

@Override
	public LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.HOME;
	}

@Override
	protected void execute() {
		cooldownRescheduled = false;
		logInfo(routineLogChiefOrderLine("Initiating Chief Order : " + chiefOrderType.getDescription() +
				" (Cooldown: " + chiefOrderType.getCooldownHours() + " hours)"));

		if (!openUpChiefOrderMenu()) {
			manageTaskFailure("Failed to open Chief Order menu");
			return;
		}

		if (!chooseOrderType()) {
			if (cooldownRescheduled) {
				return;
			}
			manageTaskFailure("Order type not available in Chief Order area");
			return;
		}

		if (!enactOrderFlow()) {
			if (cooldownRescheduled) {
				return;
			}
			if (!scheduleFromCooldownStatusIfPresent()) {
				manageTaskFailure("Failed to enact order");
			}
			return;
		}

		queueNextRun();
	}

private String routineLogChiefOrderLine(String note) {
        return "ChiefOrderRoutine | " + note;
    }

private boolean openUpChiefOrderMenu() {
		logInfo(routineLogChiefOrderLine("Looking for Chief Order menu access button"));

		ImageSearchResultData menuButton = templateSearchHelper.locatePattern(
				TemplatesEnum.CHIEF_ORDER_MENU_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (!menuButton.isFound()) {
			logError(routineLogChiefOrderLine("Chief Order menu button not detected"));
			return false;
		}

		logInfo(routineLogChiefOrderLine("Chief Order menu button detected. Pressing to open menu"));
		tapPoint(menuButton.getPoint());
		sleepTask(2000);


		return true;
	}

private void manageTaskFailure(String reason) {
		logWarning(routineLogChiefOrderLine("Routine pass did not complete: " + reason));

		LocalDateTime retryTime = LocalDateTime.now().plusMinutes(ERROR_RETRY_MINUTES_VALUE);
		reschedule(retryTime);

		logInfo(routineLogChiefOrderLine("Task rescheduled to retry in " + ERROR_RETRY_MINUTES_VALUE + " minutes"));
	}

private boolean chooseOrderType() {
		sleepTask(1500);

		AreaData tapArea = chiefOrderType.getTapArea();
		if (tapArea == null) {
			logError(routineLogChiefOrderLine("No tap area configured for " + chiefOrderType.getDescription()));
			return false;
		}

		logInfo(routineLogChiefOrderLine("Tapping Chief Order area for " + chiefOrderType.getDescription()));
		tapRandomPoint(tapArea.topLeft(), tapArea.bottomRight());
		sleepTask(ORDER_SCREEN_OPEN_WAIT_MS);


		return true;
	}

private boolean enactOrderFlow() {
		sleepTask(1500);


		logInfo(routineLogChiefOrderLine("Scanning for Chief Order Enact button"));

		ImageSearchResultData enactButton = locateChiefOrderEnactButton();

		if (!enactButton.isFound()) {
			logWarning(routineLogChiefOrderLine("Chief Order Enact button not detected"));
			return false;
		}

		logInfo(routineLogChiefOrderLine("Enact button detected. Pressing to enact order"));
		tapPoint(enactButton.getPoint());
		sleepTask(1000);


		pressBack();
		sleepTask(5000);


		logInfo(routineLogChiefOrderLine(chiefOrderType.getDescription() + " activated finished cleanly"));
		return true;
	}

private ImageSearchResultData locateChiefOrderEnactButton() {
		ImageSearchResultData strictAreaMatch = templateSearchHelper.locatePattern(
				TemplatesEnum.CHIEF_ORDER_ENACT_BUTTON,
				SearchConfig.builder()
						.withArea(CHIEF_ORDER_SCREEN_AREA)
						.withMaxAttempts(2)
						.withThreshold(90)
						.withDelay(250L)
						.build());
		if (strictAreaMatch.isFound()) {
			return strictAreaMatch;
		}

		logDebug(routineLogChiefOrderLine("Enact button not found in strict area; trying fallback area search"));
		ImageSearchResultData fallbackAreaMatch = templateSearchHelper.locatePattern(
				TemplatesEnum.CHIEF_ORDER_ENACT_BUTTON,
				SearchConfig.builder()
						.withArea(CHIEF_ORDER_SCREEN_AREA_FALLBACK)
						.withMaxAttempts(3)
						.withThreshold(86)
						.withDelay(300L)
						.build());
		if (fallbackAreaMatch.isFound()) {
			return fallbackAreaMatch;
		}

		logDebug(routineLogChiefOrderLine("Enact button still not found; trying global fallback search"));
		return templateSearchHelper.locatePattern(
				TemplatesEnum.CHIEF_ORDER_ENACT_BUTTON,
				SearchConfig.builder()
						.withMaxAttempts(2)
						.withThreshold(84)
						.withDelay(300L)
						.build());
	}

private boolean scheduleFromCooldownStatusIfPresent() {
		Duration remaining = readCooldownDurationFromStatusArea();
		if (remaining == null) {
			return false;
		}

		long seconds = Math.max(1, remaining.getSeconds() + COOLDOWN_RETRY_BUFFER_SECONDS);
		LocalDateTime retryTime = LocalDateTime.now().plusSeconds(seconds);
		reschedule(retryTime);
		cooldownRescheduled = true;
		pressBack();
		sleepTask(800);

		logInfo(routineLogChiefOrderLine(
				"Cooldown detected. Rescheduling at " + retryTime.format(DATETIME_FORMATTER) +
				" (remaining " + remaining.getSeconds() + "s + " + COOLDOWN_RETRY_BUFFER_SECONDS + "s buffer)"));
		return true;
	}

private Duration readCooldownDurationFromStatusArea() {
		String timerText = null;
		long debugEventTimestamp = System.currentTimeMillis();
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				dumpCooldownOcrRegionImage(attempt + 1, debugEventTimestamp);
				String rawText = (String) emuManager.getClass()
						.getMethod("readText", String.class, PointData.class, PointData.class, TesseractSettingsData.class)
						.invoke(emuManager, EMULATOR_NUMBER, COOLDOWN_STATUS_TOP_LEFT,
								COOLDOWN_STATUS_BOTTOM_RIGHT, COOLDOWN_TEXT_OCR_SETTINGS);
				if (!containsCooldownTimer(rawText)) {
					String timerOnlyText = (String) emuManager.getClass()
							.getMethod("readText", String.class, PointData.class, PointData.class, TesseractSettingsData.class)
							.invoke(emuManager, EMULATOR_NUMBER, COOLDOWN_STATUS_TOP_LEFT,
									COOLDOWN_STATUS_BOTTOM_RIGHT, COOLDOWN_TIMER_OCR_SETTINGS);
					if (containsCooldownTimer(timerOnlyText)) {
						rawText = timerOnlyText;
					}
				}
				logInfo(routineLogChiefOrderLine("Cooldown OCR attempt " + (attempt + 1) + " result: '"
						+ sanitizeOcrTextForLog(rawText) + "'"));
				if (containsCooldownTimer(rawText)) {
					if (!containsCooldownText(rawText)) {
						logDebug(routineLogChiefOrderLine("Cooldown timer detected without explicit 'On cooldown' text; using timer result"));
					}
					timerText = extractCooldownTimerToken(rawText);
					break;
				}
			} catch (ReflectiveOperationException ex) {
				logDebug(routineLogChiefOrderLine("Cooldown OCR attempt " + (attempt + 1) + " failed: " + ex.getMessage()));
			}

			if (attempt < 2) {
				sleepTask(150);
			}
		}

		if (timerText == null || timerText.isBlank()) {
			return null;
		}

		try {
			return parseCooldownDuration(timerText);
		} catch (IllegalArgumentException ex) {
			logWarning(routineLogChiefOrderLine("Cooldown OCR text found but parsing failed: '" + timerText + "'"));
			return null;
		}
	}

private Duration parseCooldownDuration(String rawText) {
		if (rawText == null) {
			throw new IllegalArgumentException("Cooldown text is null");
		}

		String normalised = rawText.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
		Matcher matcher = COOLDOWN_TIMER_PATTERN.matcher(normalised);
		if (!matcher.find()) {
			throw new IllegalArgumentException("No cooldown timer token found");
		}

		String token = matcher.group(1);
		String[] parts = token.split(":");
		if (parts.length != 3) {
			throw new IllegalArgumentException("Unexpected cooldown timer format: " + token);
		}

		try {
			int hours = Integer.parseInt(parts[0]);
			int minutes = Integer.parseInt(parts[1]);
			int seconds = Integer.parseInt(parts[2]);
			return Duration.ofHours(hours).plusMinutes(minutes).plusSeconds(seconds);
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException("Unable to parse cooldown timer: " + token, ex);
		}
	}

private boolean containsCooldownTimer(String rawText) {
		if (rawText == null || rawText.isBlank()) {
			return false;
		}
		return COOLDOWN_TIMER_PATTERN.matcher(rawText).find();
	}

private boolean containsCooldownText(String rawText) {
		if (rawText == null || rawText.isBlank()) {
			return false;
		}
		return COOLDOWN_TEXT_PATTERN.matcher(rawText).find();
	}

private String extractCooldownTimerToken(String rawText) {
		Matcher matcher = COOLDOWN_TIMER_PATTERN.matcher(rawText);
		if (!matcher.find()) {
			return null;
		}
		return matcher.group(1);
	}

private void dumpCooldownOcrRegionImage(int attemptNumber, long eventTimestamp) {
		try {
			RawImageData capture = emuManager.captureScreen(EMULATOR_NUMBER);
			if (capture == null || capture.getData() == null || capture.getData().length == 0) {
				logWarning(routineLogChiefOrderLine("Cooldown OCR snapshot skipped: no screenshot data available"));
				return;
			}

			BufferedImage fullImage = toBufferedImage(capture);
			if (fullImage == null) {
				logWarning(routineLogChiefOrderLine("Cooldown OCR snapshot skipped: failed to convert screenshot "
						+ "(w=" + capture.getWidth() + ", h=" + capture.getHeight() + ", bpp=" + capture.getBpp()
						+ ", bytes=" + capture.getData().length + ")"));
				return;
			}

			int left = Math.max(0, COOLDOWN_STATUS_TOP_LEFT.col());
			int top = Math.max(0, COOLDOWN_STATUS_TOP_LEFT.row());
			int right = Math.min(fullImage.getWidth(), COOLDOWN_STATUS_BOTTOM_RIGHT.col());
			int bottom = Math.min(fullImage.getHeight(), COOLDOWN_STATUS_BOTTOM_RIGHT.row());
			if (right <= left || bottom <= top) {
				logWarning(routineLogChiefOrderLine("Cooldown OCR snapshot skipped: invalid crop bounds"));
				return;
			}

			BufferedImage crop = fullImage.getSubimage(left, top, right - left, bottom - top);
			String contextToken = OcrDebugContext.getContextToken();
			String prefix = "ocr-reading-" + contextToken + "-cooldown-"
					+ left + "_" + top + "_" + (right - left) + "_" + (bottom - top);
			OcrDebugImageWriter.saveDebugImage(crop,
					prefix,
					eventTimestamp,
					attemptNumber);
		} catch (Exception ex) {
			logWarning(routineLogChiefOrderLine("Cooldown OCR snapshot failed: " + ex.getMessage()));
		}
	}

private BufferedImage toBufferedImage(RawImageData capture) {
		int width = capture.getWidth();
		int height = capture.getHeight();
		int bpp = capture.getBpp();
		byte[] raw = capture.getData();

		if (width <= 0 || height <= 0 || bpp <= 0 || raw == null) {
			return null;
		}

		int pixelCount = width * height;
		if (pixelCount <= 0) {
			return null;
		}

		if (bpp == 32 || bpp == 4) {
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			int[] pixels = new int[pixelCount];
			for (int i = 0; i < pixelCount; i++) {
				int idx = i * 4;
				if (idx + 3 >= raw.length) {
					break;
				}
				int r = raw[idx] & 0xFF;
				int g = raw[idx + 1] & 0xFF;
				int b = raw[idx + 2] & 0xFF;
				int a = raw[idx + 3] & 0xFF;
				pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
			}
			image.setRGB(0, 0, width, height, pixels, 0, width);
			return image;
		}

		if (bpp == 16) {
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			int[] pixels = new int[pixelCount];
			for (int i = 0; i < pixelCount; i++) {
				int idx = i * 2;
				if (idx + 1 >= raw.length) {
					break;
				}
				int lo = raw[idx] & 0xFF;
				int hi = raw[idx + 1] & 0xFF;
				int rgb565 = (hi << 8) | lo;
				int r = ((rgb565 >> 11) & 0x1F) * 255 / 31;
				int g = ((rgb565 >> 5) & 0x3F) * 255 / 63;
				int b = (rgb565 & 0x1F) * 255 / 31;
				pixels[i] = (r << 16) | (g << 8) | b;
			}
			image.setRGB(0, 0, width, height, pixels, 0, width);
			return image;
		}

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] pixels = new int[pixelCount];
		for (int i = 0; i < pixelCount; i++) {
			int idx = i * 3;
			if (idx + 2 >= raw.length) {
				break;
			}
			int r = raw[idx] & 0xFF;
			int g = raw[idx + 1] & 0xFF;
			int b = raw[idx + 2] & 0xFF;
			pixels[i] = (r << 16) | (g << 8) | b;
		}
		image.setRGB(0, 0, width, height, pixels, 0, width);
		return image;
	}

private String sanitizeOcrTextForLog(String rawText) {
		if (rawText == null) {
			return "<null>";
		}
		String normalized = rawText.replace("\r", " ").replace("\n", " ").trim();
		if (normalized.isEmpty()) {
			return "<empty>";
		}
		if (normalized.length() > 160) {
			return normalized.substring(0, 160) + "...";
		}
		return normalized;
	}

private void queueNextRun() {
		LocalDateTime nextExecutionTime = LocalDateTime.now()
				.plusHours(chiefOrderType.getCooldownHours());

		reschedule(nextExecutionTime);

		logInfo(routineLogChiefOrderLine("Task completed finished cleanly. Next execution in " +
				chiefOrderType.getCooldownHours() + " hours"));
	}
}
