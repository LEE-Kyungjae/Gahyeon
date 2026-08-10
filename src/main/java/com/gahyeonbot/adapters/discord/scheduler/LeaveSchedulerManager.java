package com.gahyeonbot.adapters.discord.scheduler;
import com.gahyeonbot.models.Reservation;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

public interface LeaveSchedulerManager {

    /**
     * 예약 추가
     * @param reservation 예약 정보
     * @return 예약 ID
     */
    long addReservation(Reservation reservation);

    /**
     * 예약 취소
     * @param reservationId 예약 ID
     * @return 성공 여부
     */
    boolean cancelReservation(long reservationId);

    /**
     * 예약 완료 처리 (실행 후 내부 목록에서 제거)
     * @param reservationId 예약 ID
     */
    void completeReservation(long reservationId);

    /**
     * 예약 조회
     * @param memberId 유저 ID
     * @return 예약 목록
     */
    List<Reservation> getReservations(long memberId);

    /**
     * 작업 예약
     * @param task 예약할 작업
     * @param delay 지연 시간
     * @param unit 시간 단위
     * @return 예약된 작업
     */
    ScheduledFuture<?> scheduleTask(Runnable task, long delay, java.util.concurrent.TimeUnit unit);

    /**
     * 예약 ID 생성
     * @return 새 예약 ID
     */
    long generateId();
}
