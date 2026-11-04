package com.travel.travel_recommendation.repository;

import com.travel.travel_recommendation.entity.User;
import com.travel.travel_recommendation.entity.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);

    // 관리자 회원 관리의 검색이다. 검색어·상태는 비어 있으면 조건에서 제외한다.
    // 검색어의 %, _, [ 는 AdminMemberService가 \ 를 붙여 넘기고, escape '\' 로 글자 그대로 찾는다.
    @org.springframework.data.jpa.repository.Query("""
        select u from User u
        where (:keyword is null or lower(u.loginId) like lower(concat('%', :keyword, '%')) escape '\\'
            or lower(u.nickname) like lower(concat('%', :keyword, '%')) escape '\\')
          and (:status is null or u.status = :status)
        """)
    org.springframework.data.domain.Page<User> searchMembers(
        @org.springframework.data.repository.query.Param("keyword") String keyword,
        @org.springframework.data.repository.query.Param("status") UserStatus status,
        org.springframework.data.domain.Pageable pageable);

    // 가입 아이디에는 '-'를 쓸 수 없어 대체 아이디가 새 회원과 겹치지 않고, 이미 바꾼 회원은 다시 바꾸지 않는다.
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
        update User u set u.loginId = concat('withdrawn-', cast(u.id as string)), u.updatedAt = :now
        where u.status = :withdrawn and u.withdrawnAt < :withdrawnBefore and u.loginId not like 'withdrawn-%'
        """)
    int replaceWithdrawnLoginIds(
        @org.springframework.data.repository.query.Param("withdrawn") UserStatus withdrawn,
        @org.springframework.data.repository.query.Param("withdrawnBefore") java.time.LocalDateTime withdrawnBefore,
        @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);

    // 탈퇴 계정의 공통 닉네임은 중복 검사에서 제외한다.
    boolean existsByNicknameAndStatusNot(String nickname, UserStatus status);

    boolean existsByNicknameAndStatusNotAndIdNot(String nickname, UserStatus status, Long id);

    // 같은 회원의 동시 수정이 서로의 변경을 덮어쓰지 않게 한다.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.loginId = :loginId")
    Optional<User> findForUpdateByLoginId(@org.springframework.data.repository.query.Param("loginId") String loginId);

    // 관리자 상태 변경도 같은 잠금을 써서 회원의 탈퇴·정보 수정과 겹쳐도 차례대로 처리한다.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id = :id")
    Optional<User> findForUpdateById(@org.springframework.data.repository.query.Param("id") Long id);
}
