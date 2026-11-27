package com.travel.travel_recommendation.repository;
import com.travel.travel_recommendation.entity.AiJob;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface AiJobRepository extends JpaRepository<AiJob,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from AiJob j where j.id=:id")
    Optional<AiJob> lockById(UUID id);
    List<AiJob> findTop5ByStatusOrderByCreatedAtAsc(String status);
    List<AiJob> findByRequestIdAndUserId(Long requestId, Long userId);
    /** 회원의 처리 중인 작업이 걸린 추천 요청 ID들. 추천 이력 목록에서 요청마다 따로 묻지 않으려고 한 번에 읽는다. */
    @Query("select distinct j.requestId from AiJob j where j.userId=:userId and j.requestId is not null and j.status in :statuses")
    Set<Long> findRequestIdsByUserIdAndStatusIn(Long userId, Collection<String> statuses);
    @Modifying(clearAutomatically=true,flushAutomatically=true)
    @Query("delete from AiJob j where j.requestId=:requestId and j.userId=:userId")
    int deleteByRequest(Long requestId, Long userId);
    List<AiJob> findTop5ByJobTypeAndStatusOrderByCreatedAtAsc(String jobType,String status);
    long countByUserIdAndJobTypeAndStatusIn(Long userId,String jobType,Collection<String> statuses);
    long countByGuestTokenHashAndJobTypeAndStatusIn(byte[] guestTokenHash,String jobType,Collection<String> statuses);
    /** 회원 또는 비회원(브라우저)이 최근에 만든 작업 10개. 같은 여행지로 예전에 만든 일정 초안을 찾는 데 쓴다. */
    List<AiJob> findTop10ByUserIdAndJobTypeAndStatusOrderByCreatedAtDesc(Long userId,String jobType,String status);
    List<AiJob> findTop10ByGuestTokenHashAndJobTypeAndStatusOrderByCreatedAtDesc(byte[] guestTokenHash,String jobType,String status);
    @Modifying(clearAutomatically=true,flushAutomatically=true)
    @Query("delete from AiJob j where j.userId is null and j.expiresAt<=:now")
    int deleteExpiredGuests(LocalDateTime now);
    /** 회원의 일정 초안·수정안 작업 중 expiredBefore보다 먼저 만료된 것. 회원 추천 작업(추천 이력)은 지우지 않는다. */
    @Modifying(clearAutomatically=true,flushAutomatically=true)
    @Query("delete from AiJob j where j.userId is not null and j.jobType='ITINERARY' and j.expiresAt<=:expiredBefore")
    int deleteExpiredMemberDrafts(LocalDateTime expiredBefore);
    @Modifying(clearAutomatically=true,flushAutomatically=true)
    @Query("""
        update AiJob j set j.status='FAILED', j.errorCode='SERVER_RESTART',
        j.message='서버가 재시작되어 처리를 마치지 못했어요. 조건을 확인하고 다시 요청해 주세요.', j.completedAt=:now
        where j.status in ('ANALYZING_CONDITIONS','SEARCHING_CANDIDATES','RANKING_CANDIDATES','GENERATING_SCHEDULE','VALIDATING_SCHEDULE')
        """)
    int recoverInterrupted(LocalDateTime now);
    @Modifying(clearAutomatically=true,flushAutomatically=true)
    @Query("""
        update AiJob j set j.status='FAILED', j.errorCode='TIMEOUT',
        j.message='처리가 너무 오래 걸려 마치지 못했어요. 다시 요청해 주세요.', j.completedAt=:now
        where j.status in ('ANALYZING_CONDITIONS','SEARCHING_CANDIDATES','RANKING_CANDIDATES','GENERATING_SCHEDULE','VALIDATING_SCHEDULE')
        and j.startedAt<:startedBefore
        """)
    int failStuck(LocalDateTime startedBefore,LocalDateTime now);
}
