package dev.frostguard.api.configs;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shared parser/serializer for Help Only global settings.
 */
public final class HelpOnlyModeSettings {

    private HelpOnlyModeSettings() {
    }

    public static boolean isEnabled(Map<String, String> globalConfig) {
        if (globalConfig == null) {
            return false;
        }
        String raw = globalConfig.getOrDefault(
                ConfigurationKeyEnum.HELP_ONLY_MODE_ENABLED_BOOL.name(),
                ConfigurationKeyEnum.HELP_ONLY_MODE_ENABLED_BOOL.getDefaultValue());
        return Boolean.parseBoolean(raw);
    }

    public static Set<Long> parseSelectedProfileIds(Map<String, String> globalConfig) {
        if (globalConfig == null) {
            return Set.of();
        }
        return parseSelectedProfileIds(globalConfig.getOrDefault(
                ConfigurationKeyEnum.HELP_ONLY_PROFILE_IDS_STRING.name(),
                ConfigurationKeyEnum.HELP_ONLY_PROFILE_IDS_STRING.getDefaultValue()));
    }

    public static Set<Long> parseSelectedProfileIds(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return Set.of();
        }

        Set<Long> ids = new LinkedHashSet<>();
        String[] tokens = rawValue.split(",");
        for (String token : tokens) {
            if (token == null) {
                continue;
            }
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.valueOf(trimmed));
            } catch (NumberFormatException ignored) {
                // Skip malformed token and continue with valid IDs.
            }
        }
        return ids;
    }

    public static String serializeSelectedProfileIds(Set<Long> profileIds) {
        if (profileIds == null || profileIds.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Long id : profileIds) {
            if (id == null) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(id);
        }
        return builder.toString();
    }

    public static boolean isEnabledForProfile(Map<String, String> globalConfig, Long profileId) {
        if (profileId == null || !isEnabled(globalConfig)) {
            return false;
        }
        return parseSelectedProfileIds(globalConfig).contains(profileId);
    }
}
