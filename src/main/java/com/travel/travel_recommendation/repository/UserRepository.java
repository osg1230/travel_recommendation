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

    boolean existsByNicknameAndStatusNotAndIdNot(String nickname, UserStatus status, Long id);

    // 같은 회원의 동시 수정이 서로의 변경을 덮어쓰지 않게 한다.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.loginId = :loginId")
    Optional<User> findForUpdateByLoginId(@org.springframework.data.repository.query.Param("loginId") String loginId);
}
