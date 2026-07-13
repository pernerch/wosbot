package dev.frostguard.api.configs;

/**
 * Per-thread context used to annotate OCR debug image names with
 * the currently executing profile and routine.
 */
public final class OcrDebugContext {

    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    private OcrDebugContext() {
    }

    public static void setContext(String profileName, String routineName) {
        String profilePart = sanitise(profileName, "unknown-profile");
        String routinePart = sanitise(routineName, "unknown-routine");
        CONTEXT.set(profilePart + "-" + routinePart);
    }

    public static String getContextToken() {
        String token = CONTEXT.get();
        return (token == null || token.isBlank()) ? "global" : token;
    }

    public static void clear() {
        CONTEXT.remove();
    }

    private static String sanitise(String value, String fallback) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isBlank()) {
            return fallback;
        }
        return candidate.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}