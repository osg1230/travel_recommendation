package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.VirtualReservationView.CancelOutcome;
import com.travel.travel_recommendation.entity.User;
import com.travel.travel_recommendation.repository.UserRepository;
import com.travel.travel_recommendation.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 가상 예약·결제 무결성(SFR-13·16, QUR-04, TER-08)을 실제 SQL Server에서 확인한다. SQL 013 적용이 필요하다.
 * 동시 요청은 서로 커밋된 결과를 봐야 하므로 테스트 트랜잭션으로 감싸지 않고, 만든 행을 테스트마다 직접 지운다.
 */
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-windows")
class VirtualReservationIntegrationTests {
    private static final BigDecimal PRICE = new BigDecimal("100000.00");
    @Autowired VirtualReservationService service;
    @Autowired WithdrawalService withdrawals;
    @Autowired SavedItineraryService savedItineraries;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoSpyBean VirtualPaymentGateway gateway;
    private final List<Long> userIds=new ArrayList<>();
    private final List<Long> stockIds=new ArrayList<>();
    private final List<Long> placeIds=new ArrayList<>();
    private long hotel;
    private static final Random RANDOM=new Random();

    @BeforeEach void setup() {
        hotel=jdbc.queryForObject("SELECT TOP 1 place_id FROM dbo.places WHERE place_type='ACCOMMODATION' AND source_place_id='R1_GN_SEAMARQ'",Long.class);
    }

    @AfterEach void cleanup() {
        for(Long stock:stockIds) {
            jdbc.update("DELETE l FROM dbo.reservation_logs l JOIN dbo.virtual_reservations r ON r.reservation_id=l.reservation_id WHERE r.stock_id=?",stock);
            jdbc.update("DELETE FROM dbo.reservation_logs WHERE stock_id=?",stock);
            jdbc.update("DELETE v FROM dbo.virtual_payments v JOIN dbo.virtual_reservations r ON r.reservation_id=v.reservation_id WHERE r.stock_id=?",stock);
            jdbc.update("DELETE FROM dbo.virtual_reservations WHERE stock_id=?",stock);
        }
        for(Long user:userIds) {
            jdbc.update("DELETE l FROM dbo.reservation_logs l JOIN dbo.virtual_reservations r ON r.reservation_id=l.reservation_id WHERE r.user_id=?",user);
            jdbc.update("DELETE v FROM dbo.virtual_payments v JOIN dbo.virtual_reservations r ON r.reservation_id=v.reservation_id WHERE r.user_id=?",user);
            jdbc.update("DELETE FROM dbo.virtual_reservations WHERE user_id=?",user);
            jdbc.update("DELETE FROM dbo.operation_logs WHERE actor_user_id=?",user);
            jdbc.update("DELETE i FROM dbo.itinerary_items i JOIN dbo.itineraries t ON t.itinerary_id=i.itinerary_id WHERE t.user_id=?",user);
            jdbc.update("DELETE FROM dbo.itineraries WHERE user_id=?",user);
        }
        for(Long stock:stockIds) jdbc.update("DELETE FROM dbo.reservation_stock WHERE stock_id=?",stock);
        for(Long user:userIds) jdbc.update("DELETE FROM dbo.users WHERE user_id=?",user);
        for(Long place:placeIds) jdbc.update("DELETE FROM dbo.places WHERE place_id=?",place);
        userIds.clear(); stockIds.clear(); placeIds.clear();
    }

    private User member() {
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,20);
        var user=users.saveAndFlush(new User("rv_"+suffix,encoder.encode("Travel123!"),"예약_"+suffix));
        userIds.add(user.getId());
        return user;
    }
    /** 시드와 겹치지 않는 먼 미래(2031년)의 1박 구간을 만든다. */
    private long stock(int remaining) { return stockAt(LocalDateTime.of(2031,1,1,15,0).plusMinutes(RANDOM.nextInt(400_000)),remaining); }
    private long stockAt(LocalDateTime start, int remaining) {
        long id=jdbc.queryForObject("""
            INSERT INTO dbo.reservation_stock(place_id, usage_start_at, usage_end_at, total_quantity, remaining_quantity, unit_price, quantity_unit)
            OUTPUT inserted.stock_id VALUES (?,?,?,?,?,?,'ROOM')""",Long.class,hotel,start,start.plusHours(21),remaining,remaining,PRICE);
        stockIds.add(id);
        return id;
    }
    private int remaining(long stock) { return jdbc.queryForObject("SELECT remaining_quantity FROM dbo.reservation_stock WHERE stock_id=?",Integer.class,stock); }
    private String reservationStatus(long reservation) { return jdbc.queryForObject("SELECT status FROM dbo.virtual_reservations WHERE reservation_id=?",String.class,reservation); }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql,Integer.class,args); }
    private static HttpStatus statusOf(Throwable e) { return (HttpStatus)((ResponseStatusException)e).getStatusCode(); }

    @Test void reservingDeductsStockOnceForTheSameRequestKey() {
        var owner=member(); long stock=stock(3); var key=UUID.randomUUID();
        long id=service.reserve(owner.getLoginId(),stock,null,1,2,key);
        assertThat(service.reserve(owner.getLoginId(),stock,null,1,2,key)).isEqualTo(id);
        assertThat(remaining(stock)).isEqualTo(2);
        assertThat(reservationStatus(id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT expected_amount FROM dbo.virtual_reservations WHERE reservation_id=?",BigDecimal.class,id))
            .isEqualByComparingTo("100000");
        assertThat(count("SELECT COUNT(*) FROM dbo.reservation_logs WHERE reservation_id=? AND event_type='CREATE' AND delta_quantity=-1",id)).isEqualTo(1);
        // 객실당 최대 2명(씨마크)을 넘거나 객실보다 인원이 적으면 저장하지 않는다.
        assertThatThrownBy(()->service.reserve(owner.getLoginId(),stock,null,1,3,UUID.randomUUID()))
            .satisfies(e->assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(remaining(stock)).isEqualTo(2);
    }

    @Test void twoConcurrentRequestsForTheLastUnitProduceOneReservation() throws Exception {
        long stock=stock(1);
        var members=List.of(member(),member());
        var results=race(2,i->service.reserve(members.get(i).getLoginId(),stock,null,1,1,UUID.randomUUID()));
        assertThat(results.successes()).isEqualTo(1);
        assertThat(results.conflicts()).isEqualTo(1);
        assertThat(remaining(stock)).isZero();
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_reservations WHERE stock_id=?",stock)).isEqualTo(1);
    }

    @Test void twentyParallelRequestsNeverOversell() throws Exception {
        long stock=stock(5);
        var members=new ArrayList<User>();
        for(int i=0;i<20;i++) members.add(member());
        var results=race(20,i->service.reserve(members.get(i).getLoginId(),stock,null,1,1,UUID.randomUUID()));
        assertThat(results.successes()).isEqualTo(5);
        assertThat(results.conflicts()).isEqualTo(15);
        assertThat(remaining(stock)).isZero();
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_reservations WHERE stock_id=?",stock)).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM dbo.reservation_logs WHERE stock_id=?",stock)).isEqualTo(5);
    }

    @Test void theSamePaymentKeyIsProcessedOnceEvenConcurrently() throws Exception {
        var owner=member(); long stock=stock(2);
        long id=service.reserve(owner.getLoginId(),stock,null,1,1,UUID.randomUUID());
        var key=UUID.randomUUID();
        var results=race(2,i->service.pay(owner.getLoginId(),id,key,false).paymentId());
        assertThat(results.successes()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_payments WHERE reservation_id=?",id)).isEqualTo(1);
        var replay=service.pay(owner.getLoginId(),id,key,false);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo("SUCCESS");
        assertThat(reservationStatus(id)).isEqualTo("CONFIRMED");
    }

    @Test void differentPaymentKeysAtOnceAllowOnlyOneSuccess() throws Exception {
        var owner=member(); long stock=stock(2);
        long id=service.reserve(owner.getLoginId(),stock,null,1,1,UUID.randomUUID());
        var results=race(5,i->service.pay(owner.getLoginId(),id,UUID.randomUUID(),false).paymentId());
        assertThat(results.successes()).isEqualTo(1);
        assertThat(results.conflicts()).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_payments WHERE reservation_id=? AND status='SUCCESS'",id)).isEqualTo(1);
    }

    @Test void aReusedPaymentKeyWithDifferentContentIsRejected() {
        var owner=member(); long stock=stock(3);
        long first=service.reserve(owner.getLoginId(),stock,null,1,1,UUID.randomUUID());
        long second=service.reserve(owner.getLoginId(),stock,null,1,1,UUID.randomUUID());
        var key=UUID.randomUUID();
        service.pay(owner.getLoginId(),first,key,true);
        assertThatThrownBy(()->service.pay(owner.getLoginId(),second,key,false))
            .satisfies(e->assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_payments WHERE reservation_id=?",second)).isZero();
    }

    @Test void declinedPaymentKeepsReservationPendingAndCanBeRetriedWithANewKey() {
        var owner=member(); long stock=stock(2);
        long id=service.reserve(owner.getLoginId(),stock,null,1,1,UUID.randomUUID());
        assertThat(service.pay(owner.getLoginId(),id,UUID.randomUUID(),true).status()).isEqualTo("FAILED");
        assertThat(reservationStatus(id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM dbo.virtual_payments WHERE reservation_id=?",String.class,id))
            .isEqualTo(VirtualPaymentGateway.DECLINED);
        assertThat(service.pay(owner.getLoginId(),id,UUID.randomUUID(),false).status()).isEqualTo("SUCCESS");
        assertThat(reservationStatus(id)).isEqualTo("CONFIRMED");
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_payments WHERE reservation_id=?",id)).isEqualTo(2);
    }

    @Test void anExceptionDuringPaymentRollsBackEverything() {
        var owner=member(); long stock=stock(2);
        long id=service.reserve(owner.getLoginId(),stock,null,1,1,UUID.randomUUID());
        doThrow(new IllegalStateException("injected")).when(gateway).charge(anyLong(),any(),anyBoolean());
        assertThatThrownBy(()->service.pay(owner.getLoginId(),id,UUID.randomUUID(),false)).isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_payments WHERE reservation_id=?",id)).isZero();
        assertThat(reservationStatus(id)).isEqualTo("PENDING");
        assertThat(remaining(stock)).isEqualTo(1);
        assertThat(count("""
            SELECT COUNT(*) FROM dbo.operation_logs WHERE target_type='PAYMENT' AND target_id=? AND outcome='FAILED' AND reason LIKE 'ROLLBACK%'""",id))
            .isEqualTo(1);
    }

    @Test void refundRestoresStockOnceAndBlocksASecondRefund() {
        var owner=member(); long stock=stock(2);
        long id=service.reserve(owner.getLoginId(),stock,null,1,1,UUID.randomUUID());
        service.pay(owner.getLoginId(),id,UUID.randomUUID(),false);
        assertThat(service.cancel(owner.getLoginId(),id)).isEqualTo(CancelOutcome.REFUNDED);
        assertThat(service.cancel(owner.getLoginId(),id)).isEqualTo(CancelOutcome.ALREADY_CANCELED);
        assertThat(remaining(stock)).isEqualTo(2);
        assertThat(reservationStatus(id)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT status FROM dbo.virtual_payments WHERE reservation_id=?",String.class,id)).isEqualTo("REFUNDED");
        assertThat(count("SELECT COUNT(*) FROM dbo.reservation_logs WHERE reservation_id=? AND event_type='CANCEL'",id)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM dbo.operation_logs WHERE target_type='RESERVATION' AND target_id=? AND outcome='FAILED'",id)).isEqualTo(1);
        // 취소된 예약은 다시 결제하거나 바꿀 수 없다.
        assertThatThrownBy(()->service.pay(owner.getLoginId(),id,UUID.randomUUID(),false))
            .satisfies(e->assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(()->service.change(owner.getLoginId(),id,stock,1,1))
            .satisfies(e->assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_payments WHERE reservation_id=?",id)).isEqualTo(1);
    }

    @Test void changingTheDateMovesStockAndRecalculatesTheAmountUntilPaid() {
        var owner=member();
        var start=LocalDateTime.of(2031,1,1,15,0).plusMinutes(RANDOM.nextInt(400_000));
        long day1=stockAt(start,3), day2=stockAt(start.plusDays(1),3);
        long id=service.reserve(owner.getLoginId(),day1,null,1,1,UUID.randomUUID());
        service.change(owner.getLoginId(),id,day2,2,3);
        assertThat(remaining(day1)).isEqualTo(3);
        assertThat(remaining(day2)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT expected_amount FROM dbo.virtual_reservations WHERE reservation_id=?",BigDecimal.class,id))
            .isEqualByComparingTo("200000");
        assertThat(count("SELECT COUNT(DISTINCT operation_id) FROM dbo.reservation_logs WHERE reservation_id=? AND event_type='CHANGE'",id)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM dbo.reservation_logs WHERE reservation_id=? AND event_type='CHANGE'",id)).isEqualTo(2);
        // 남은 수량보다 많이 바꾸려 하면 아무것도 바뀌지 않는다.
        assertThatThrownBy(()->service.change(owner.getLoginId(),id,day1,4,4))
            .satisfies(e->assertThat(statusOf(e)).isIn(HttpStatus.CONFLICT,HttpStatus.BAD_REQUEST));
        assertThat(remaining(day1)).isEqualTo(3);
        assertThat(remaining(day2)).isEqualTo(1);
        service.pay(owner.getLoginId(),id,UUID.randomUUID(),false);
        assertThatThrownBy(()->service.change(owner.getLoginId(),id,day1,1,1))
            .satisfies(e->assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test void anotherMemberCannotSeeOrChangeTheReservation() {
        var owner=member(); var other=member(); long stock=stock(2);
        long id=service.reserve(owner.getLoginId(),stock,null,1,1,UUID.randomUUID());
        assertThatThrownBy(()->service.detail(id,other.getLoginId())).satisfies(e->assertThat(statusOf(e)).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(()->service.cancel(other.getLoginId(),id)).satisfies(e->assertThat(statusOf(e)).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(()->service.pay(other.getLoginId(),id,UUID.randomUUID(),false)).satisfies(e->assertThat(statusOf(e)).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(service.mine(other.getLoginId())).isEmpty();
        assertThat(reservationStatus(id)).isEqualTo("PENDING");
        assertThat(remaining(stock)).isEqualTo(1);
    }

    @Test void savedItineraryOffersBookingAndDeletingItKeepsTheReservation() {
        var owner=member();
        // 숙박 항목과 수량 구간의 날짜가 같아야 하므로 시각은 15:00으로 고정하고 날짜만 무작위로 고른다.
        var start=LocalDateTime.of(2032,1,1,15,0).plusDays(RANDOM.nextInt(3000));
        long stock=stockAt(start,2);
        long itinerary=jdbc.queryForObject("""
            INSERT INTO dbo.itineraries(user_id, destination_place_id, title, start_date, end_date, transportation, conditions_snapshot_json)
            OUTPUT inserted.itinerary_id VALUES (?,?,N'예약 테스트',?,?,'CAR','{}')""",Long.class,owner.getId(),hotel,start.toLocalDate(),start.toLocalDate().plusDays(1));
        jdbc.update("""
            INSERT INTO dbo.itinerary_items(itinerary_id, day_no, sequence_no, item_type, place_id, start_at, end_at, activity)
            VALUES (?,1,1,'STAY',?,?,?,N'숙박')""",itinerary,hotel,start.plusHours(1),start.plusHours(18));
        var bookable=service.bookable(itinerary,owner.getLoginId());
        assertThat(bookable).singleElement().satisfies(b->assertThat(b.stock().stockId()).isEqualTo(stock));
        long id=service.reserve(owner.getLoginId(),stock,itinerary,1,2,UUID.randomUUID());
        assertThat(service.bookable(itinerary,owner.getLoginId()).get(0).activeReservationId()).isEqualTo(id);
        savedItineraries.delete(itinerary,owner.getLoginId());
        assertThat(jdbc.queryForObject("SELECT itinerary_id FROM dbo.virtual_reservations WHERE reservation_id=?",Long.class,id)).isNull();
        assertThat(reservationStatus(id)).isEqualTo("PENDING");
    }

    @Test void aKakaoPlaceAtTheSameAddressOffersTheSystemPlacesStock() {
        var owner=member();
        var start=LocalDateTime.of(2032,1,1,15,0).plusDays(RANDOM.nextInt(3000));
        long stock=stockAt(start,2);
        String address=jdbc.queryForObject("SELECT address FROM dbo.places WHERE place_id=?",String.class,hotel);
        // 카카오 이름은 띄어쓰기가 다르고, 이름만 같은 다른 주소의 장소는 연결하지 않는다.
        long sameSpot=kakaoPlace("씨마크호텔",address.replace(" ","  "));
        long elsewhere=kakaoPlace("씨마크 호텔","강원특별자치도 강릉시 다른로 1");
        long itinerary=jdbc.queryForObject("""
            INSERT INTO dbo.itineraries(user_id, destination_place_id, title, start_date, end_date, transportation, conditions_snapshot_json)
            OUTPUT inserted.itinerary_id VALUES (?,?,N'카카오 장소 예약 테스트',?,?,'CAR','{}')""",Long.class,owner.getId(),hotel,start.toLocalDate(),start.toLocalDate().plusDays(1));
        jdbc.update("""
            INSERT INTO dbo.itinerary_items(itinerary_id, day_no, sequence_no, item_type, place_id, start_at, end_at, activity)
            VALUES (?,1,1,'STAY',?,?,?,N'숙박')""",itinerary,elsewhere,start.plusHours(1),start.plusHours(18));
        assertThat(service.bookable(itinerary,owner.getLoginId())).isEmpty();
        jdbc.update("UPDATE dbo.itinerary_items SET place_id=? WHERE itinerary_id=?",sameSpot,itinerary);
        assertThat(service.bookable(itinerary,owner.getLoginId()))
            .singleElement().satisfies(b->assertThat(b.stock().stockId()).isEqualTo(stock));
        // 카카오는 시·도를 줄여 쓴다(예: '경상북도' → '경북'). 시·도 이름만 다르면 같은 주소로 본다.
        long shortProvince=kakaoPlace("씨마크 호텔","강원 "+address.substring(address.indexOf(' ')+1));
        jdbc.update("UPDATE dbo.itinerary_items SET place_id=? WHERE itinerary_id=?",shortProvince,itinerary);
        assertThat(service.bookable(itinerary,owner.getLoginId()))
            .singleElement().satisfies(b->assertThat(b.stock().stockId()).isEqualTo(stock));
    }

    private long kakaoPlace(String name, String address) {
        long id=jdbc.queryForObject("""
            INSERT INTO dbo.places(place_type, source_provider, source_place_id, place_name, region, address, verified_at)
            OUTPUT inserted.place_id VALUES ('ACCOMMODATION','KAKAO',?,?,N'강원특별자치도 강릉시',?,SYSUTCDATETIME())""",
            Long.class,"test_"+UUID.randomUUID(),name,address);
        placeIds.add(id);
        return id;
    }

    @Test void withdrawalRestoresHeldStockAndDeletesReservationRecords() {
        var owner=member(); long stock=stock(3);
        long paid=service.reserve(owner.getLoginId(),stock,null,1,1,UUID.randomUUID());
        service.pay(owner.getLoginId(),paid,UUID.randomUUID(),false);
        service.reserve(owner.getLoginId(),stock,null,1,1,UUID.randomUUID());
        assertThat(remaining(stock)).isEqualTo(1);
        withdrawals.withdraw(owner.getLoginId(),"Travel123!",true);
        assertThat(remaining(stock)).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_reservations WHERE user_id=?",owner.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_payments WHERE reservation_id=?",paid)).isZero();
    }

    @Test void pagesShowTheVirtualNoticeAndReservingRedirectsToTheDetail() throws Exception {
        var owner=member(); long stock=stock(2);
        mvc.perform(get("/my/reservations/new").param("stockId",String.valueOf(stock)).with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("실제 돈이 오가지 않아요")));
        var result=mvc.perform(post("/my/reservations").with(user(owner.getLoginId())).with(csrf())
                .param("stockId",String.valueOf(stock)).param("quantity","1").param("partySize","2").param("requestKey",UUID.randomUUID().toString()))
            .andExpect(status().is3xxRedirection()).andReturn();
        String location=result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith("/my/reservations/");
        mvc.perform(get(location).with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("결제 대기")));
        mvc.perform(get("/my/reservations").with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("예약번호")));
    }

    // ───────── 동시 실행 도우미 ─────────

    private record Race(int successes, int conflicts) {}
    private interface Attempt { Object run(int index) throws Exception; }

    /** 모든 스레드를 한 번에 출발시켜 성공 수와 409(매진·중복) 수를 센다. 그 밖의 예외는 테스트 실패로 본다. */
    private Race race(int threads, Attempt attempt) throws Exception {
        var pool=Executors.newFixedThreadPool(threads);
        var start=new CountDownLatch(1);
        var ok=new AtomicInteger(); var conflict=new AtomicInteger();
        var futures=new ArrayList<Future<?>>();
        for(int i=0;i<threads;i++) {
            int index=i;
            futures.add(pool.submit(()->{
                start.await();
                try { attempt.run(index); ok.incrementAndGet(); }
                catch(ResponseStatusException e) {
                    if(e.getStatusCode()!=HttpStatus.CONFLICT) throw e;
                    conflict.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        try { for(var f:futures) f.get(60,TimeUnit.SECONDS); }
        finally { pool.shutdownNow(); }
        return new Race(ok.get(),conflict.get());
    }
}
