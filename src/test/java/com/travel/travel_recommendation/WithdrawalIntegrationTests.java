package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.ConfirmedTravelConditions;
import com.travel.travel_recommendation.entity.*;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 회원 본인 탈퇴(SFR-01-05)를 실제 SQL Server에서 확인한다. */
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-windows")
@Transactional
class WithdrawalIntegrationTests {
    @Autowired WithdrawalService withdrawals;
    @Autowired FavoriteService favorites;
    @Autowired PlaceRatingService ratings;
    @Autowired RecommendationJobService recommendations;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;
    private static final String PASSWORD="Travel123!";
    private User member;

    @BeforeEach void setup() { member=account(); }
    private User account() {
        String suffix=UUID.randomUUID().toString().replace("-","");
        return users.saveAndFlush(new User("test_"+suffix,encoder.encode(PASSWORD),"여행_"+suffix.substring(0,8)));
    }
    private long placeOf(String type) {
        return jdbc.queryForObject("SELECT TOP 1 place_id FROM dbo.places WHERE place_type=? AND is_active=1 ORDER BY place_id",Long.class,type);
    }
    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo."+table+" WHERE user_id=?",Integer.class,member.getId());
    }
    private void clear() { em.flush(); em.clear(); }

    @Test void withdrawalAnonymizesAccountAndDeletesPersonalRecords() {
        favorites.add(placeOf("ATTRACTION"),member.getLoginId());
        ratings.save(placeOf("ATTRACTION"),member.getLoginId(),5,List.of(),null);
        recommendations.create(new ConfirmedTravelConditions("TEXT","개인 여행 이야기","강릉",null,null,2,null,2,true,false,List.of(),""),member.getLoginId(),null);
        clear();
        assertThat(count("favorites")+count("place_ratings")+count("recommendation_requests")+count("ai_jobs")).isEqualTo(4);
        String nickname=member.getNickname();
        users.findById(member.getId()).orElseThrow().changeHomeArea("서울 송파구");
        clear();

        withdrawals.withdraw(member.getLoginId(),PASSWORD,true);
        clear();
        var after=users.findById(member.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(after.getWithdrawnAt()).isNotNull();
        assertThat(after.getNickname()).isEqualTo(WithdrawalService.ANONYMOUS_NICKNAME);
        assertThat(after.getHomeArea()).isNull();
        assertThat(encoder.matches(PASSWORD,after.getPasswordHash())).isFalse();
        assertThat(count("favorites")+count("place_ratings")+count("recommendation_requests")+count("ai_jobs")+count("itineraries")).isZero();
        // 탈퇴 회원이 쓰던 닉네임은 다른 회원이 쓸 수 있고, 익명 닉네임은 여러 탈퇴 회원이 함께 쓴다.
        assertThat(users.existsByNicknameAndStatusNot(nickname,UserStatus.WITHDRAWN)).isFalse();
        var second=account();
        withdrawals.withdraw(second.getLoginId(),PASSWORD,true);
        clear();
        assertThat(users.findById(second.getId()).orElseThrow().getNickname()).isEqualTo(WithdrawalService.ANONYMOUS_NICKNAME);
        // 아이디는 남아 있어 같은 아이디로 다시 가입할 수 없다.
        assertThat(users.existsByLoginId(member.getLoginId())).isTrue();
    }

    @Test void loginIdIsReplacedOnlyAfterThirtyDays() {
        var recent=account();
        withdrawals.withdraw(member.getLoginId(),PASSWORD,true);
        withdrawals.withdraw(recent.getLoginId(),PASSWORD,true);
        var active=account();
        clear();
        String oldLoginId=member.getLoginId();
        jdbc.update("UPDATE dbo.users SET withdrawn_at=DATEADD(day,-31,withdrawn_at) WHERE user_id=?",member.getId());
        jdbc.update("UPDATE dbo.users SET withdrawn_at=DATEADD(day,-29,withdrawn_at) WHERE user_id=?",recent.getId());

        assertThat(withdrawals.anonymizeExpired()).isEqualTo(1);
        clear();
        assertThat(users.findById(member.getId()).orElseThrow().getLoginId()).isEqualTo("withdrawn-"+member.getId());
        // 원래 아이디가 어디에도 남지 않아 다시 가입할 수 있고, 30일이 안 된 탈퇴 회원과 이용 중인 회원은 그대로다.
        assertThat(users.existsByLoginId(oldLoginId)).isFalse();
        assertThat(users.findById(recent.getId()).orElseThrow().getLoginId()).isEqualTo(recent.getLoginId());
        assertThat(users.findById(active.getId()).orElseThrow().getLoginId()).isEqualTo(active.getLoginId());
        // 이미 바꾼 회원은 다시 바꾸지 않는다.
        assertThat(withdrawals.anonymizeExpired()).isZero();
    }

    @Test void wrongPasswordMissingConsentAndAdminAreRefusedWithoutChanges() {
        favorites.add(placeOf("ATTRACTION"),member.getLoginId());
        clear();
        assertThatThrownBy(()->withdrawals.withdraw(member.getLoginId(),"Wrong123!",true)).isInstanceOf(ProfileUpdateException.class).hasMessageContaining("비밀번호");
        assertThatThrownBy(()->withdrawals.withdraw(member.getLoginId(),PASSWORD,false)).isInstanceOf(ProfileUpdateException.class).hasMessageContaining("동의");
        clear();
        assertThat(users.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(count("favorites")).isEqualTo(1);
        var admin=account();
        jdbc.update("UPDATE dbo.users SET role='ADMIN' WHERE user_id=?",admin.getId());
        clear();
        assertThatThrownBy(()->withdrawals.withdraw(admin.getLoginId(),PASSWORD,true)).isInstanceOf(ProfileUpdateException.class).hasMessageContaining("관리자");
    }

    @Test void accountScreenWarnsAboutActiveReservationsAndAdminsSeeNoForm() throws Exception {
        // 진행 중(결제 대기·예약 확정) 예약만 센다. 취소된 예약은 세지 않는다.
        long hotel=placeOf("ACCOMMODATION");
        // 예약 테스트(2031년)와 겹치지 않는 2032년 구간을 만든다. 테스트가 끝나면 롤백된다.
        java.time.LocalDateTime start=java.time.LocalDateTime.of(2032,1,1,15,0).plusMinutes(new Random().nextInt(400_000));
        long stock=jdbc.queryForObject("""
            INSERT INTO dbo.reservation_stock(place_id, usage_start_at, usage_end_at, total_quantity, remaining_quantity, unit_price, quantity_unit)
            OUTPUT inserted.stock_id VALUES (?,?,?,3,3,100000,'ROOM')""",Long.class,hotel,start,start.plusHours(21));
        String insert="""
            INSERT INTO dbo.virtual_reservations(user_id, stock_id, request_key, quantity, party_size, expected_amount, status, payment_required, booking_snapshot_json, canceled_at)
            VALUES (?,?,NEWID(),1,2,100000,?,1,'{}',CASE WHEN ?='CANCELED' THEN SYSDATETIME() END)""";
        for(String status:List.of("PENDING","CONFIRMED","CANCELED")) jdbc.update(insert,member.getId(),stock,status,status);
        assertThat(withdrawals.countActiveReservations(member.getLoginId())).isEqualTo(2);
        mvc.perform(get("/account").with(user(member.getLoginId())))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("진행 중인 예약 2건은 탈퇴하면 취소되고, 예약·결제 내역도 삭제돼요.")));
        // 관리자 계정은 탈퇴 폼 대신 안내를 본다.
        var admin=account();
        jdbc.update("UPDATE dbo.users SET role='ADMIN' WHERE user_id=?",admin.getId());
        clear();
        mvc.perform(get("/account").with(user(admin.getLoginId()).roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("관리자 계정은 탈퇴할 수 없어요")))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("name=\"confirm\""))));
    }

    @Test void screenWithdrawsLogsOutAndBlocksLoginAfterward() throws Exception {
        var session=new MockHttpSession();
        mvc.perform(post("/login").servletPath("/login").session(session).with(csrf()).param("loginId",member.getLoginId()).param("password",PASSWORD))
            .andExpect(status().is3xxRedirection());
        mvc.perform(get("/account").session(session)).andExpect(status().isOk()).andExpect(content().string(containsString("회원 탈퇴")));
        mvc.perform(post("/account/withdraw").session(session).with(csrf()).param("currentPassword","Wrong123!").param("confirm","true"))
            .andExpect(redirectedUrl("/account#withdraw-title")).andExpect(flash().attribute("withdrawError",containsString("비밀번호")));
        mvc.perform(post("/account/withdraw").session(session).param("currentPassword",PASSWORD).param("confirm","true"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/account/withdraw").session(session).with(csrf()).param("currentPassword",PASSWORD).param("confirm","true"))
            .andExpect(redirectedUrl("/?withdrawn"));
        mvc.perform(get("/?withdrawn")).andExpect(content().string(containsString("탈퇴가 끝났어요")));
        clear();
        mvc.perform(post("/login").servletPath("/login").with(csrf()).param("loginId",member.getLoginId()).param("password",PASSWORD))
            .andExpect(redirectedUrl("/login?error"));
    }
}
