package com.gahyeonbot.commands.util;

/**
 * 봇의 모든 명령어에 대한 설명 상수들을 정의하는 클래스.
 * 명령어의 이름, 설명, 상세 설명을 중앙에서 관리합니다.
 * 
 * @author GahyeonBot Team
 * @version 1.0
 */
public class Description {
    // 명령어 설명 개요
    public static final String ALLHERE_NAME = "gather";
    public static final String ALLHERE_NAME_KO = "모여";
    public static final String ALLHERE_DESC = "모든 보이스 채널의 사용자들을 현재 보이스 채널로 이동시킵니다.";
    public static final String ALLHERE_DETAIL = "디스코드 서버의 모든 사용자를 현재 보이스 채널로 집결시킵니다.";

    public static final String CLEAN_NAME = "clean-chat";
    public static final String CLEAN_NAME_KO = "채팅정리";
    public static final String CLEAN_DESC = "채팅 삭제 명령어입니다. 전체 채팅과 내가 작성한 채팅 선택이 가능합니다.";
    public static final String CLEAN_DETAIL = "원하는 수만큼 메시지를 삭제합니다. 권장값은 100개 이하이며, 100개 초과 시 100개 단위로 삭제를 반복합니다.";

    public static final String INFO_NAME = "help";
    public static final String INFO_NAME_KO = "도움말";
    public static final String INFO_DESC = "명령어 사용법 안내서";
    public static final String INFO_DETAIL = "봇의 명령어 목록과 사용법을 안내합니다.";

    public static final String GAHYEONA_NAME = "gahyeona";
    public static final String GAHYEONA_NAME_KO = "가현아";
    public static final String GAHYEONA_DESC = "AI에게 질문하고 답변을 받습니다.";
    public static final String GAHYEONA_DETAIL = "Gahyeon AI에게 질문하고 답변을 받습니다. 질문은 1000자 이하로 입력해주세요.";

    public static final String WEATHER_NAME = "weather";
    public static final String WEATHER_NAME_KO = "날씨";
    public static final String WEATHER_DESC = "현재 날씨와 예보를 확인합니다.";
    public static final String WEATHER_DETAIL = "전체 날씨 현황을 확인합니다. 자연어 날씨 질문은 /가현아에서 할 수 있습니다.";

    public static final String TTS_NAME = "tts";
    public static final String TTS_NAME_KO = "티";
    public static final String TTS_DESC = "텍스트를 음성으로 읽어줍니다 (보이스 채널).";
    public static final String TTS_DETAIL = "보이스 채널에 들어간 뒤 사용하세요. 예) /티 text:안녕";

    public static final String DM_OPTIN_NAME = "dm-optin";
    public static final String DM_OPTIN_NAME_KO = "dm수신";
    public static final String DM_OPTIN_DESC = "GitHub 트렌딩 다이제스트 DM 수신을 켭니다.";
    public static final String DM_OPTIN_DETAIL = "매일 오전 7시(KST) GitHub 트렌딩 레포 요약을 DM으로 받습니다.";

    public static final String DM_OPTOUT_NAME = "dm-optout";
    public static final String DM_OPTOUT_NAME_KO = "dm거부";
    public static final String DM_OPTOUT_DESC = "GitHub 트렌딩 다이제스트 DM 수신을 끕니다.";
    public static final String DM_OPTOUT_DETAIL = "정기 DM 발송 대상에서 제외됩니다.";

    public static final String DM_STATUS_NAME = "dm-status";
    public static final String DM_STATUS_NAME_KO = "dm상태";
    public static final String DM_STATUS_DESC = "GitHub 트렌딩 DM 수신 상태를 확인합니다.";
    public static final String DM_STATUS_DETAIL = "현재 GitHub 트렌딩 다이제스트 DM 수신 동의 여부를 확인합니다.";

    //내보내기
    public static final String BOTOUT_NAME = "kick-bots";
    public static final String BOTOUT_NAME_KO = "봇퇴장";
    public static final String BOTOUT_DESC = "현재 보이스 채널에서 모든 봇을 즉시내보냅니다.";
    public static final String BOTOUT_DETAIL = "보이스 채널의 모든 봇을 내보내므로 필요한 봇이 있는지 확인하세요.";

    public static final String CANCLEOUT_NAME="cancel-schedule";
    public static final String CANCLEOUT_NAME_KO="퇴장취소";
    public static final String CANCLEOUT_DESC="예약된 작업을 취소합니다.";
    public static final String CANCLEOUT_DETAIL="예약된 나가기 작업을 취소합니다. 예약 ID를 입력하세요.";

    public static final String OUT_NAME = "schedule-kick";
    public static final String OUT_NAME_KO = "퇴장";
    public static final String OUT_DESC = "지정한 시간 후 보이스 채널 사용자를 내보냅니다.";
    public static final String OUT_DETAIL = """
        - **preset**: 미리 정의된 시간 옵션 (예: 1시간, 2시간).
        - **time**: 사용자가 직접 입력한 시간 (1~4자리 숫자 가능).
          - 4자리 숫자: 시간(2자리) + 분(2자리)
          - 3자리 숫자: 시간(1자리) + 분(2자리)
          - 1~2자리 숫자: 분(최대 99분)
        """;
    public static final String SEARCHOUT_NAME="list-schedules";
    public static final String SEARCHOUT_NAME_KO="퇴장조회";
    public static final String SEARCHOUT_DESC="예약된 작업 목록을 조회합니다.";
    public static final String SEARCHOUT_DETAIL="사용자의 예약된 나가기 작업 목록을 확인합니다.";

    public static final String WITHOUT_NAME = "schedule-kickall";
    public static final String WITHOUT_NAME_KO = "함께퇴장";
    public static final String WITHOUT_DESC = "현재 보이스 채널에 있는 모든 사용자를 지정된 시간에 내보냅니다.";
    public static final String WITHOUT_DETAIL = """
        - **preset**: 미리 정의된 시간 옵션 (예: 1시간, 2시간).
        - **time**: 사용자가 직접 입력한 시간 (1~4자리 숫자 가능).
          - 4자리 숫자: 시간(2자리) + 분(2자리)
          - 3자리 숫자: 시간(1자리) + 분(2자리)
          - 1~2자리 숫자: 분(최대 99분)
        """;

    //노래 API
    public static final String MUSIC_NAME = "music";
    public static final String MUSIC_NAME_KO = "뮤직";
    public static final String MUSIC_DESC = "음악 재생/대기열을 한 번에 제어합니다.";
    public static final String MUSIC_DETAIL = "예) /뮤직 action:추가 query:노래제목, /뮤직 action:대기열";

    public static final String ADD_NAME = "music-add";
    public static final String ADD_NAME_KO = "뮤직추가";
    public static final String ADD_DESC = "노래를 추가합니다.";
    public static final String ADD_DETAIL = "검색할 노래 제목을 입력하세요.";

    public static final String CLEAR_NAME = "music-clear";
    public static final String CLEAR_NAME_KO = "뮤직초기화";
    public static final String CLEAR_DESC = "현재 노래를 포함한 대기열을 초기화합니다.";
    public static final String CLEAR_DETAIL = "현재 재생 중인 음악을 멈추고 대기열을 초기화합니다.";

    public static final String PAUSE_NAME = "music-pause";
    public static final String PAUSE_NAME_KO = "뮤직정지";
    public static final String PAUSE_DESC = "현재 노래를 일시정지합니다.";
    public static final String PAUSE_DETAIL = "현재 재생 중인 음악을 멈추고 대기열을 초기화합니다.";

    public static final String QUEUE_NAME = "music-queue";
    public static final String QUEUE_NAME_KO = "뮤직리스트";
    public static final String QUEUE_DESC = "현재 대기열을 표시합니다.";
    public static final String QUEUE_DETAIL = "현재 대기열에 있는 모든 곡을 나열합니다.";

    public static final String RESUME_NAME="music-resume";
    public static final String RESUME_NAME_KO="뮤직재생";
    public static final String RESUME_DESC="현재 노래를 다시재생합니다.";
    public static final String RESUME_DETAIL="현재 정지상태인 음악을 다시 재생시킵니다";

    public static final String SKIP_NAME="music-skip";
    public static final String SKIP_NAME_KO="뮤직다음곡";
    public static final String SKIP_DESC="다음곡을 재생합니다.";
    public static final String SKIP_DETAIL="";

    /*
    public static final String _NAME="";
    public static final String _DESC="";
    public static final String _DETAIL="";
    */




}
