package com.travel.travel_recommendation.repository;
import com.travel.travel_recommendation.entity.OperationLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationLogRepository extends JpaRepository<OperationLog,Long> {
    List<OperationLog> findTop10ByTargetTypeAndTargetIdOrderByIdDesc(String targetType, Long targetId);
}
