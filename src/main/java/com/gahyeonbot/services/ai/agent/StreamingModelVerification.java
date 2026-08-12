package com.gahyeonbot.services.ai.agent;

final class StreamingModelVerification {
    private StreamingModelVerification() {}

    static boolean allows(
            boolean enabled,
            String configuredBaseUrl,
            String configuredModel,
            String verifiedBaseUrl,
            String verifiedModel) {
        if (!enabled) return false;
        String actualUrl = normalizeBaseUrl(configuredBaseUrl);
        String approvedUrl = normalizeBaseUrl(verifiedBaseUrl);
        String actualModel = normalize(configuredModel);
        String approvedModel = normalize(verifiedModel);
        return !actualUrl.isEmpty()
                && actualUrl.equals(approvedUrl)
                && !actualModel.isEmpty()
                && actualModel.equals(approvedModel);
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = normalize(value);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
