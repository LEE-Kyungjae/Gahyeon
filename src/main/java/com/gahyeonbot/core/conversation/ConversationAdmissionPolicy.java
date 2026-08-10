package com.gahyeonbot.core.conversation;

import java.util.List;

/** Deterministic conversation admission policy; performs no I/O. */
public final class ConversationAdmissionPolicy {
    public static final int MAX_MESSAGE_LENGTH = 1_000;
    public static final int ACTOR_HOURLY_LIMIT = 75;
    public static final int ACTOR_DAILY_LIMIT = 30;
    public static final int GLOBAL_DAILY_LIMIT = 50;
    public static final int GLOBAL_MONTHLY_LIMIT = 100;

    private static final List<String> ADVERSARIAL_KEYWORDS = List.of(
            "ignore", "disregard", "forget", "override", "bypass",
            "jailbreak", "promptinjection", "systemprompt",
            "ignoreprevious", "ignoreyour", "youarenow", "actas",
            "pretendyouare", "developmode", "danmode",
            "무시", "우회", "탈옥", "프롬프트주입", "시스템명령",
            "이전지시", "너는이제", "역할수행", "개발자모드");

    public AdmissionDecision decide(String message, AdmissionFacts facts) {
        if (message == null || message.isBlank()) {
            return AdmissionDecision.reject(AdmissionDecision.Reason.INVALID_INPUT, "빈 메시지가 전달되었습니다.");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return AdmissionDecision.reject(AdmissionDecision.Reason.INVALID_INPUT,
                    "질문이 너무 깁니다. " + MAX_MESSAGE_LENGTH + "자 이하로 입력해주세요.");
        }
        if (facts.moderationFlagged() || containsAdversarialKeyword(message)) {
            return AdmissionDecision.reject(AdmissionDecision.Reason.UNSAFE_INPUT,
                    "부적절한 요청이 감지되었습니다.");
        }
        if (facts.duplicate()) {
            return AdmissionDecision.reject(AdmissionDecision.Reason.DUPLICATE,
                    "같은 질문을 너무 빨리 다시 물어봤습니다. 잠시 후 다시 시도해주세요.");
        }
        if (facts.actorHourlyUsage() >= ACTOR_HOURLY_LIMIT) {
            return AdmissionDecision.reject(AdmissionDecision.Reason.ACTOR_HOURLY_LIMIT,
                    "1시간당 " + ACTOR_HOURLY_LIMIT + "회 제한을 초과했습니다. 잠시 후 다시 시도해주세요.");
        }
        if (facts.actorDailyUsage() >= ACTOR_DAILY_LIMIT) {
            return AdmissionDecision.reject(AdmissionDecision.Reason.ACTOR_DAILY_LIMIT,
                    "하루 " + ACTOR_DAILY_LIMIT + "회 제한을 초과했습니다. 내일 다시 시도해주세요.");
        }
        if (facts.totalDailyUsage() >= GLOBAL_DAILY_LIMIT) {
            return AdmissionDecision.reject(AdmissionDecision.Reason.GLOBAL_DAILY_LIMIT,
                    "오늘의 AI 사용 한도가 모두 소진되었습니다. 내일 다시 시도해주세요.");
        }
        if (facts.totalMonthlyUsage() >= GLOBAL_MONTHLY_LIMIT) {
            return AdmissionDecision.reject(AdmissionDecision.Reason.GLOBAL_MONTHLY_LIMIT,
                    "이번 달 AI 사용 한도가 모두 소진되었습니다. 다음 달에 다시 시도해주세요.");
        }
        return AdmissionDecision.allow();
    }

    private boolean containsAdversarialKeyword(String message) {
        String normalized = message.replaceAll("[\\s\\p{Punct}]", "").toLowerCase();
        return ADVERSARIAL_KEYWORDS.stream().anyMatch(normalized::contains);
    }
}
