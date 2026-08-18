package com.gahyeonbot.entity;

/**
 * DM 뉴스레터 테마. 각 사용자는 테마별로 구독을 켜고 끌 수 있다.
 * 새 테마 추가 = 여기에 값 하나 + 해당 콘텐츠 생성/발송 서비스만 붙이면 된다.
 */
public enum NewsletterTheme {
    GITHUB_TRENDING("github-trending", "GitHub 트렌딩",
            "매일 오전 7시(KST) GitHub 트렌딩 레포 요약을 DM으로 받습니다."),
    PERSONALIZED_NEWS("personalized-news", "맞춤뉴스",
            "공식 출처 또는 독립 출처로 검증된 관심 주제 뉴스를 받습니다."),
    AI_PAPERS("ai-papers", "AI 논문 다이제스트",
            "Hugging Face 논문 기반 최신 AI/ML 논문 요약 (준비중).");

    private final String code;
    private final String displayName;
    private final String description;

    NewsletterTheme(String code, String displayName, String description) {
        this.code = code;
        this.displayName = displayName;
        this.description = description;
    }

    /** 슬래시 옵션 값으로 쓰는 안정적인 코드. */
    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /** 옵션 code -> enum. 알 수 없으면 기본 테마(GITHUB_TRENDING). */
    public static NewsletterTheme fromCode(String code) {
        if (code != null) {
            for (NewsletterTheme t : values()) {
                if (t.code.equals(code)) {
                    return t;
                }
            }
        }
        return GITHUB_TRENDING;
    }
}
