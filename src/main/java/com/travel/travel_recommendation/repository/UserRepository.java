package com.travel.travel_recommendation.repository;

import com.travel.travel_recommendation.entity.User;
import com.travel.travel_recommendation.entity.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);

    // 탈퇴 계정의 공통 닉네임은 중복 검사에서 제외한다.
    boolean existsByNicknameAndStatusNot(String nickname, UserStatus status);
}
