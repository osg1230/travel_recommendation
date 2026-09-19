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
    List<AiJob> findTop5ByJobTypeAndStatusOrderByCreatedAtAsc(String jobType,String status);
    @Modifying(clearAutomatically=true,flushAutomatically=true)
    @Query("delete from AiJob j where j.userId is null and j.expiresAt<=:now")
    int deleteExpiredGuests(LocalDateTime now);
    @Modifying(clearAutomatically=true,flushAutomatically=true)
    @Query("""
        update AiJob j set j.status='FAILED', j.errorCode='SERVER_RESTART',
        j.message='서버가 재시작되어 처리를 마치지 못했어요. 조건을 확인하고 다시 요청해 주세요.', j.completedAt=:now
        where j.status in ('ANALYZING_CONDITIONS','SEARCHING_CANDIDATES','RANKING_CANDIDATES','GENERATING_SCHEDULE','VALIDATING_SCHEDULE')
        """)
    int recoverInterrupted(LocalDateTime now);
}
