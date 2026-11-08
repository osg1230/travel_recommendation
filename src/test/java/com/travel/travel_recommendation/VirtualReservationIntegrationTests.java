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
import static org.hamcrest.Matchers.not;
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
    /** 씨마크 숙박 항목이 있는 내 일정. 예약은 내 일정에 있는 장소만 받는다(날짜는 보지 않는다). */
    private long trip(User owner) {
        var day=LocalDate.of(2031,1,1);
        long itinerary=jdbc.queryForObject("""
            INSERT INTO dbo.itineraries(user_id, destination_place_id, title, start_date, end_date, transportation, conditions_snapshot_json)
            OUTPUT inserted.itinerary_id VALUES (?,?,N'예약 테스트 일정',?,?,'CAR','{}')""",Long.class,owner.getId(),hotel,day,day.plusDays(1));
        jdbc.update("""
            INSERT INTO dbo.itinerary_items(itinerary_id, day_no, sequence_no, item_type, place_id, start_at, end_at, activity)
            VALUES (?,1,1,'STAY',?,?,?,N'숙박')""",itinerary,hotel,day.atTime(16,0),day.atTime(23,0));
        return itinerary;
    }
    /** 이용 시작 시각이 지난 것처럼 수량 구간과 예약 스냅샷의 이용 시각을 2시간 전으로 옮긴다. */
    private void startNow(long stock) {
        var start=LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusHours(2).withNano(0);
        jdbc.update("UPDATE dbo.reservation_stock SET usage_start_at=?, usage_end_at=? WHERE stock_id=?",start,start.plusHours(20),stock);
        jdbc.update("""
            UPDATE dbo.virtual_reservations
            SET booking_snapshot_json=JSON_MODIFY(JSON_MODIFY(booking_snapshot_json,'$.usageStartAt',?),'$.usageEndAt',?) WHERE stock_id=?""",
            start.toString(),start.plusHours(20).toString(),stock);
    }
    private int remaining(long stock) { return jdbc.queryForObject("SELECT remaining_quantity FROM dbo.reservation_stock WHERE stock_id=?",Integer.class,stock); }
    private String reservationStatus(long reservation) { return jdbc.queryForObject("SELECT status FROM dbo.virtual_reservations WHERE reservation_id=?",String.class,reservation); }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql,Integer.class,args); }
    private static HttpStatus statusOf(Throwable e) { return (HttpStatus)((ResponseStatusException)e).getStatusCode(); }

    @Test void reservingDeductsStockOnceForTheSameRequestKey() {
        var owner=member(); long stock=stock(3); var key=UUID.randomUUID(); long trip=trip(owner);
        long id=service.reserve(owner.getLoginId(),stock,trip,1,2,key);
        assertThat(service.reserve(owner.getLoginId(),stock,trip,1,2,key)).isEqualTo(id);
        assertThat(remaining(stock)).isEqualTo(2);
        assertThat(reservationStatus(id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT expected_amount FROM dbo.virtual_reservations WHERE reservation_id=?",BigDecimal.class,id))
            .isEqualByComparingTo("100000");
        assertThat(count("SELECT COUNT(*) FROM dbo.reservation_logs WHERE reservation_id=? AND event_type='CREATE' AND delta_quantity=-1",id)).isEqualTo(1);
        // 객실당 최대 2명(씨마크)을 넘거나 객실보다 인원이 적으면 저장하지 않는다.
        assertThatThrownBy(()->service.reserve(owner.getLoginId(),stock,trip,1,3,UUID.randomUUID()))
            .satisfies(e->assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(remaining(stock)).isEqualTo(2);
    }

    // 결제 대기 예약은 회원당 MAX_PENDING_PER_MEMBER건까지 받고, 30분 넘게 결제하지 않으면 취소해 수량을 되돌린다.
    @Test void pendingReservationsAreLimitedAndExpireAfterThirtyMinutes() {
        var owner=member(); long trip=trip(owner); long stock=stock(10);
        var ids=new ArrayList<Long>();
        for(int i=0;i<VirtualReservationService.MAX_PENDING_PER_MEMBER;i++) ids.add(service.reserve(owner.getLoginId(),stock,trip,1,1,UUID.randomUUID()));
        assertThatThrownBy(()->service.reserve(owner.getLoginId(),stock,trip,1,1,UUID.randomUUID()))
            .satisfies(e->assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
        assertThat(remaining(stock)).isEqualTo(10-VirtualReservationService.MAX_PENDING_PER_MEMBER);
        // 한 건만 31분 전에 마지막으로 바꾼 것처럼 옮긴다. 그 한 건만 취소되고 수량이 돌아온다.
        jdbc.update("UPDATE dbo.virtual_reservations SET updated_at=DATEADD(MINUTE,-31,SYSDATETIME()) WHERE reservation_id=?",ids.get(0));
        assertThat(service.expirePending()).isGreaterThanOrEqualTo(1);
        assertThat(reservationStatus(ids.get(0))).isEqualTo("CANCELED");
        assertThat(reservationStatus(ids.get(1))).isEqualTo("PENDING");
        assertThat(remaining(stock)).isEqualTo(10-VirtualReservationService.MAX_PENDING_PER_MEMBER+1);
        assertThat(count("SELECT COUNT(*) FROM dbo.reservation_logs WHERE reservation_id=? AND event_type='CANCEL' AND delta_quantity=1",ids.get(0))).isEqualTo(1);
        // 한 건이 풀렸으니 다시 신청할 수 있다.
        assertThat(service.reserve(owner.getLoginId(),stock,trip,1,1,UUID.randomUUID())).isPositive();
    }

    @Test void twoConcurrentRequestsForTheLastUnitProduceOneReservation() throws Exception {
        long stock=stock(1);
        var members=List.of(member(),member());
        var trips=members.stream().map(this::trip).toList();
        var results=race(2,i->service.reserve(members.get(i).getLoginId(),stock,trips.get(i),1,1,UUID.randomUUID()));
        assertThat(results.successes()).isEqualTo(1);
        assertThat(results.conflicts()).isEqualTo(1);
        assertThat(remaining(stock)).isZero();
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_reservations WHERE stock_id=?",stock)).isEqualTo(1);
    }

    @Test void twentyParallelRequestsNeverOversell() throws Exception {
        long stock=stock(5);
        var members=new ArrayList<User>();
        for(int i=0;i<20;i++) members.add(member());
        var trips=members.stream().map(this::trip).toList();
        var results=race(20,i->service.reserve(members.get(i).getLoginId(),stock,trips.get(i),1,1,UUID.randomUUID()));
        assertThat(results.successes()).isEqualTo(5);
        assertThat(results.conflicts()).isEqualTo(15);
        assertThat(remaining(stock)).isZero();
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_reservations WHERE stock_id=?",stock)).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM dbo.reservation_logs WHERE stock_id=?",stock)).isEqualTo(5);
    }

    @Test void theSamePaymentKeyIsProcessedOnceEvenConcurrently() throws Exception {
        var owner=member(); long stock=stock(2);
        long id=service.reserve(owner.getLoginId(),stock,trip(owner),1,1,UUID.randomUUID());
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
        long id=service.reserve(owner.getLoginId(),stock,trip(owner),1,1,UUID.randomUUID());
        var results=race(5,i->service.pay(owner.getLoginId(),id,UUID.randomUUID(),false).paymentId());
        assertThat(results.successes()).isEqualTo(1);
        assertThat(results.conflicts()).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_payments WHERE reservation_id=? AND status='SUCCESS'",id)).isEqualTo(1);
    }

    @Test void aReusedPaymentKeyWithDifferentContentIsRejected() {
        var owner=member(); long stock=stock(3);
        long first=service.reserve(owner.getLoginId(),stock,trip(owner),1,1,UUID.randomUUID());
        long second=service.reserve(owner.getLoginId(),stock,trip(owner),1,1,UUID.randomUUID());
        var key=UUID.randomUUID();
        service.pay(owner.getLoginId(),first,key,true);
        assertThatThrownBy(()->service.pay(owner.getLoginId(),second,key,false))
            .satisfies(e->assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_payments WHERE reservation_id=?",second)).isZero();
    }

    @Test void declinedPaymentKeepsReservationPendingAndCanBeRetriedWithANewKey() {
        var owner=member(); long stock=stock(2);
        long id=service.reserve(owner.getLoginId(),stock,trip(owner),1,1,UUID.randomUUID());
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
        long id=service.reserve(owner.getLoginId(),stock,trip(owner),1,1,UUID.randomUUID());
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
        long id=service.reserve(owner.getLoginId(),stock,trip(owner),1,1,UUID.randomUUID());
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
        long id=service.reserve(owner.getLoginId(),day1,trip(owner),1,1,UUID.randomUUID());
        String changes=service.change(owner.getLoginId(),id,day2,2,3);
        // 바뀐 항목만 '이전→새 값'으로 알려 주고, 같은 문장을 이력 사유에도 남긴다.
        var dates=java.time.format.DateTimeFormatter.ofPattern("MM-dd");
        assertThat(changes).isEqualTo("이용일 "+start.format(dates)+"→"+start.plusDays(1).format(dates)
            +" · 객실 1→2실 · 인원 1→3명 · 금액 100,000→200,000원");
        assertThat(jdbc.queryForList("SELECT reason FROM dbo.reservation_logs WHERE reservation_id=? AND event_type='CHANGE'",String.class,id))
            .allSatisfy(reason->assertThat(reason).startsWith(changes));
        assertThat(remaining(day1)).isEqualTo(3);
        assertThat(remaining(day2)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT expected_amount FROM dbo.virtual_reservations WHERE reservation_id=?",BigDecimal.class,id))
            .isEqualByComparingTo("200000");
        assertThat(count("SELECT COUNT(DISTINCT operation_id) FROM dbo.reservation_logs WHERE reservation_id=? AND event_type='CHANGE'",id)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM dbo.reservation_logs WHERE reservation_id=? AND event_type='CHANGE'",id)).isEqualTo(2);
        // 날짜·객실·인원이 그대로면 저장하지 않는다.
        assertThat(service.change(owner.getLoginId(),id,day2,2,3)).isNull();
        assertThat(count("SELECT COUNT(*) FROM dbo.reservation_logs WHERE reservation_id=? AND event_type='CHANGE'",id)).isEqualTo(2);
        assertThat(service.change(owner.getLoginId(),id,day2,2,4)).isEqualTo("인원 3→4명");
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
        long id=service.reserve(owner.getLoginId(),stock,trip(owner),1,1,UUID.randomUUID());
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

    @Test void anyLodgingInASavedItineraryGetsRoomTypesToBook() {
        var owner=member();
        // 객실은 내일부터 1년 안의 날짜에만 만들어진다.
        var date=LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(30+RANDOM.nextInt(300));
        long lodging=kakaoPlace("테스트 펜션","강원특별자치도 강릉시 객실로 "+RANDOM.nextInt(100_000));
        long itinerary=jdbc.queryForObject("""
            INSERT INTO dbo.itineraries(user_id, destination_place_id, title, start_date, end_date, transportation, conditions_snapshot_json)
            OUTPUT inserted.itinerary_id VALUES (?,?,N'객실 종류 테스트',?,?,'CAR','{}')""",Long.class,owner.getId(),hotel,date,date.plusDays(1));
        jdbc.update("""
            INSERT INTO dbo.itinerary_items(itinerary_id, day_no, sequence_no, item_type, place_id, start_at, end_at, activity)
            VALUES (?,1,1,'STAY',?,?,?,N'숙박'), (?,2,1,'STAY',?,?,?,N'퇴실')""",
            itinerary,lodging,date.atTime(17,0),date.atTime(18,0),itinerary,lodging,date.plusDays(1).atTime(9,0),date.plusDays(1).atTime(10,0));
        var bookable=service.bookable(itinerary,owner.getLoginId(),Map.of(lodging,150_000));
        stockIds.addAll(jdbc.queryForList("SELECT stock_id FROM dbo.reservation_stock WHERE place_id=?",Long.class,lodging));
        // 숙박하는 첫날만 대상이고(마지막 날은 퇴실), 대표로 스탠다드 더블 한 행을 돌려준다. 기준가는 참고 가격이다.
        assertThat(bookable).singleElement().satisfies(b->{
            assertThat(b.dayNo()).isEqualTo(1);
            assertThat(b.stock().roomType()).isEqualTo("STANDARD_DOUBLE");
            assertThat(b.stock().unitPrice()).isEqualByComparingTo("150000");
        });
        var rooms=service.rooms(lodging,date);
        assertThat(rooms).extracting(r->r.roomType()).containsExactly("SINGLE","STANDARD_DOUBLE","STANDARD_TWIN","DELUXE_DOUBLE","FAMILY","SUITE");
        assertThat(rooms.get(5).unitPrice()).isEqualByComparingTo("300000");
        // 다시 조회해도 객실을 더 만들지 않는다.
        service.bookable(itinerary,owner.getLoginId());
        assertThat(count("SELECT COUNT(*) FROM dbo.reservation_stock WHERE place_id=?",lodging)).isEqualTo(6);
        // 스위트는 객실당 2명이라 1실에 3명은 거절하고, 어느 객실로 예약하든 일정에서는 '내 예약'으로 이어진다.
        long suite=rooms.get(5).stockId();
        assertThatThrownBy(()->service.reserve(owner.getLoginId(),suite,itinerary,1,3,UUID.randomUUID()))
            .satisfies(e->assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST));
        long id=service.reserve(owner.getLoginId(),suite,itinerary,1,2,UUID.randomUUID());
        assertThat(service.bookable(itinerary,owner.getLoginId()).get(0).activeReservationId()).isEqualTo(id);
        assertThat(service.detail(id,owner.getLoginId()).summary().roomTypeName()).isEqualTo("스위트룸");
    }

    @Test void ticketedAttractionsGetStockButPlacesToJustWalkAroundDoNot() {
        var owner=member();
        var date=LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(30+RANDOM.nextInt(300));
        long aquarium=attraction("테스트 아쿠아리움","문화,예술 > 문화시설 > 아쿠아리움");
        long beach=attraction("테스트 해수욕장","여행 > 관광,명소 > 해수욕장,해변");
        long itinerary=jdbc.queryForObject("""
            INSERT INTO dbo.itineraries(user_id, destination_place_id, title, start_date, end_date, transportation, conditions_snapshot_json)
            OUTPUT inserted.itinerary_id VALUES (?,?,N'관광시설 예약 테스트',?,?,'CAR','{}')""",Long.class,owner.getId(),hotel,date,date);
        jdbc.update("""
            INSERT INTO dbo.itinerary_items(itinerary_id, day_no, sequence_no, item_type, place_id, start_at, end_at, activity)
            VALUES (?,1,1,'VISIT',?,?,?,N'관람'), (?,1,2,'VISIT',?,?,?,N'산책')""",
            itinerary,aquarium,date.atTime(10,0),date.atTime(11,30),itinerary,beach,date.atTime(13,0),date.atTime(14,30));
        var bookable=service.bookable(itinerary,owner.getLoginId());
        stockIds.addAll(jdbc.queryForList("SELECT stock_id FROM dbo.reservation_stock WHERE place_id IN (?,?)",Long.class,aquarium,beach));
        // 아쿠아리움만 대상이고 분류별 체험가(25,000원)·하루 100명으로 만들어진다. 해변에는 수량을 만들지 않는다.
        assertThat(bookable).singleElement().satisfies(b->{
            assertThat(b.itemPlaceId()).isEqualTo(aquarium);
            assertThat(b.stock().room()).isFalse();
            assertThat(b.stock().unitPrice()).isEqualByComparingTo("25000");
            assertThat(b.stock().remaining()).isEqualTo(100);
        });
        service.bookable(itinerary,owner.getLoginId());
        assertThat(count("SELECT COUNT(*) FROM dbo.reservation_stock WHERE place_id=?",aquarium)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM dbo.reservation_stock WHERE place_id=?",beach)).isZero();
        long id=service.reserve(owner.getLoginId(),bookable.get(0).stock().stockId(),itinerary,3,3,UUID.randomUUID());
        assertThat(service.detail(id,owner.getLoginId()).summary().amount()).isEqualByComparingTo("75000");
    }

    private long attraction(String name, String subtype) {
        long id=jdbc.queryForObject("""
            INSERT INTO dbo.places(place_type, source_provider, source_place_id, place_name, region, address, subtype, verified_at)
            OUTPUT inserted.place_id VALUES ('ATTRACTION','KAKAO',?,?,N'강원특별자치도 강릉시',?,?,SYSDATETIME())""",
            Long.class,"test_"+UUID.randomUUID(),name,"강원특별자치도 강릉시 시험로 "+RANDOM.nextInt(100_000),subtype);
        placeIds.add(id);
        return id;
    }

    private long kakaoPlace(String name, String address) {
        long id=jdbc.queryForObject("""
            INSERT INTO dbo.places(place_type, source_provider, source_place_id, place_name, region, address, verified_at)
            OUTPUT inserted.place_id VALUES ('ACCOMMODATION','KAKAO',?,?,N'강원특별자치도 강릉시',?,SYSDATETIME())""",
            Long.class,"test_"+UUID.randomUUID(),name,address);
        placeIds.add(id);
        return id;
    }

    @Test void withdrawalRestoresHeldStockAndDeletesReservationRecords() {
        var owner=member(); long stock=stock(3);
        long paid=service.reserve(owner.getLoginId(),stock,trip(owner),1,1,UUID.randomUUID());
        service.pay(owner.getLoginId(),paid,UUID.randomUUID(),false);
        service.reserve(owner.getLoginId(),stock,trip(owner),1,1,UUID.randomUUID());
        assertThat(remaining(stock)).isEqualTo(1);
        withdrawals.withdraw(owner.getLoginId(),"Travel123!",true);
        assertThat(remaining(stock)).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_reservations WHERE user_id=?",owner.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_payments WHERE reservation_id=?",paid)).isZero();
    }

    @Test void pagesShowTheVirtualNoticeAndReservingRedirectsToTheDetail() throws Exception {
        var owner=member(); long stock=stock(2); long trip=trip(owner);
        // 예약은 저장한 일정에서 시작한다. 일정 없이 주소만 열면 이유와 함께 일정으로 돌아가는 링크를 보여 준다.
        mvc.perform(get("/my/reservations/new").param("stockId",String.valueOf(stock)).with(user(owner.getLoginId())))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("저장한 일정에서 예약할 장소를 골라 주세요")))
            .andExpect(content().string(containsString("이전 화면으로")));
        mvc.perform(get("/my/reservations/new").param("stockId",String.valueOf(stock)).param("itineraryId",String.valueOf(trip))
                .with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("실제 돈이 오가지 않아요")))
            .andExpect(content().string(not(containsString("이 날짜에 이미 예약이 있어요"))));
        var result=mvc.perform(post("/my/reservations").with(user(owner.getLoginId())).with(csrf())
                .param("stockId",String.valueOf(stock)).param("itineraryId",String.valueOf(trip))
                .param("quantity","1").param("partySize","2").param("requestKey",UUID.randomUUID().toString()))
            .andExpect(status().is3xxRedirection()).andReturn();
        String location=result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith("/my/reservations/");
        long id=Long.parseLong(location.substring(location.lastIndexOf('/')+1));
        // 뒤로 가기로 같은 장소·날짜의 신청 화면을 다시 열면 이미 있는 예약을 알려 준다(객실을 더 잡을 수 있어 막지는 않는다).
        mvc.perform(get("/my/reservations/new").param("stockId",String.valueOf(stock)).param("itineraryId",String.valueOf(trip))
                .with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("이 날짜에 이미 예약이 있어요(예약번호 "+id+")")));
        mvc.perform(get(location).with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("결제 대기")))
            .andExpect(content().string(containsString("100,000원 × 1실 = 100,000원")))
            .andExpect(content().string(containsString("수량이 잡혀 있어요. 결제하면 확정돼요.")))
            .andExpect(content().string(containsString("name=\"expectedAmount\"")));
        mvc.perform(get("/my/reservations").with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("예약번호")))
            .andExpect(content().string(containsString("결제하기 →")));
    }

    @Test void aRejectedBookingReturnsToTheFormWithTheInputKept() throws Exception {
        var owner=member(); long stock=stock(2); long trip=trip(owner);
        // 씨마크는 객실당 2명이라 1실에 3명은 거절된다. 오류 화면 대신 신청 화면으로 돌아가 이유와 입력값을 보여 준다.
        var rejected=mvc.perform(post("/my/reservations").with(user(owner.getLoginId())).with(csrf())
                .param("stockId",String.valueOf(stock)).param("itineraryId",String.valueOf(trip))
                .param("quantity","1").param("partySize","3").param("requestKey",UUID.randomUUID().toString()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/my/reservations/new?stockId="+stock+"&itineraryId="+trip))
            .andExpect(flash().attribute("reserveError",containsString("객실당 최대 2명")))
            .andExpect(flash().attribute("partySizeInput",3))
            .andReturn();
        mvc.perform(get(rejected.getResponse().getRedirectedUrl()).flashAttrs(rejected.getFlashMap()).with(user(owner.getLoginId())))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("객실당 최대 2명이에요. 객실 수를 늘려 주세요.")))
            .andExpect(content().string(containsString("value=\"3\"")));
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_reservations WHERE user_id=?",owner.getId())).isZero();
        assertThat(remaining(stock)).isEqualTo(2);
    }

    @Test void changesAndRepeatedCancelsTellWhatHappened() throws Exception {
        var owner=member(); long stock=stock(2);
        long id=service.reserve(owner.getLoginId(),stock,trip(owner),1,1,UUID.randomUUID());
        // 남은 객실(1실)보다 많이 늘리면 상세의 변경 칸으로 돌아가 이유를 보여 주고 입력값을 다시 채운다.
        var rejected=mvc.perform(post("/my/reservations/"+id+"/change").with(user(owner.getLoginId())).with(csrf())
                .param("quantity","3").param("partySize","3"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/my/reservations/"+id+"#change-title"))
            .andExpect(flash().attribute("changeError",containsString("남은 수량(1)보다 많이 예약할 수 없어요")))
            .andReturn();
        mvc.perform(get("/my/reservations/"+id).flashAttrs(rejected.getFlashMap()).with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("남은 수량(1)보다 많이 예약할 수 없어요")))
            .andExpect(content().string(containsString("value=\"3\"")));
        // 그대로 저장하면 아무것도 바꾸지 않고, 바꾸면 무엇이 바뀌었는지 알려 준다.
        mvc.perform(post("/my/reservations/"+id+"/change").with(user(owner.getLoginId())).with(csrf()).param("quantity","1").param("partySize","1"))
            .andExpect(flash().attribute("savedMessage","바뀐 내용이 없어요. 예약은 그대로예요."));
        mvc.perform(post("/my/reservations/"+id+"/change").with(user(owner.getLoginId())).with(csrf()).param("quantity","1").param("partySize","2"))
            .andExpect(flash().attribute("savedMessage","예약을 바꿨어요: 인원 1→2명"));
        // 취소를 두 번 눌러도 한 번만 처리하고, 지금 결제 상태를 함께 알려 준다.
        mvc.perform(post("/my/reservations/"+id+"/cancel").with(user(owner.getLoginId())).with(csrf()))
            .andExpect(flash().attribute("savedMessage",containsString("예약을 취소했어요")));
        mvc.perform(post("/my/reservations/"+id+"/cancel").with(user(owner.getLoginId())).with(csrf()))
            .andExpect(flash().attribute("savedMessage","이미 취소된 예약이에요(결제 없음). 다시 처리하지 않았어요."));
        mvc.perform(get("/my/reservations/"+id).with(user(owner.getLoginId())))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("결제 대기 → 취소됨")))
            .andExpect(content().string(not(containsString("PENDING"))))
            .andExpect(content().string(containsString("결제 없음")))
            .andExpect(content().string(containsString("최근 변경 시각")))
            .andExpect(content().string(containsString("취소 시각")))
            .andExpect(content().string(containsString("취소된 예약이에요. 다시 이용하려면")));
    }

    @Test void afterTheStartTimeOnlyUnpaidReservationsCanBeCanceled() throws Exception {
        var owner=member(); long stock=stock(3); long trip=trip(owner);
        long unpaid=service.reserve(owner.getLoginId(),stock,trip,1,1,UUID.randomUUID());
        long paid=service.reserve(owner.getLoginId(),stock,trip,1,1,UUID.randomUUID());
        service.pay(owner.getLoginId(),paid,UUID.randomUUID(),false);
        startNow(stock);
        // 결제 대기 예약은 결제·변경할 수 없고 취소만 된다.
        assertThatThrownBy(()->service.pay(owner.getLoginId(),unpaid,UUID.randomUUID(),false))
            .satisfies(e->assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT))
            .hasMessageContaining("이용 시작 시각이 지나 결제할 수 없어요");
        assertThatThrownBy(()->service.change(owner.getLoginId(),unpaid,stock,1,2))
            .satisfies(e->assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
        // 결제한 예약은 이용 시작 뒤에는 취소·환불하지 않는다.
        assertThatThrownBy(()->service.cancel(owner.getLoginId(),paid))
            .satisfies(e->assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT))
            .hasMessageContaining("이용 시작 시각이 지나 취소·환불할 수 없어요");
        assertThat(reservationStatus(paid)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status FROM dbo.virtual_payments WHERE reservation_id=?",String.class,paid)).isEqualTo("SUCCESS");
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_payments WHERE reservation_id=?",unpaid)).isZero();
        // 상세 화면은 할 수 없는 버튼을 숨기고 이유를 보여 준다.
        mvc.perform(get("/my/reservations/"+paid).with(user(owner.getLoginId())))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("이용 시작 시각이 지나 취소·환불할 수 없어요.")))
            .andExpect(content().string(containsString("확정 시각")))
            .andExpect(content().string(not(containsString("취소하고 환불받기"))));
        mvc.perform(get("/my/reservations/"+unpaid).with(user(owner.getLoginId())))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("이용 시작 시각이 지나 결제하거나 바꿀 수 없어요.")))
            .andExpect(content().string(not(containsString("id=\"pay-title\""))))
            .andExpect(content().string(not(containsString("id=\"change-title\""))))
            .andExpect(content().string(containsString("id=\"cancel-title\"")));
        mvc.perform(get("/my/reservations").with(user(owner.getLoginId())))
            .andExpect(content().string(containsString("이용일 지남")))
            .andExpect(content().string(not(containsString("결제하기 →"))));
        assertThat(service.cancel(owner.getLoginId(),unpaid)).isEqualTo(CancelOutcome.CANCELED);
        assertThat(remaining(stock)).isEqualTo(2);
    }

    @Test void paymentStopsWhenTheAmountOnScreenIsNoLongerCurrent() throws Exception {
        var owner=member(); long stock=stock(3);
        long id=service.reserve(owner.getLoginId(),stock,trip(owner),1,1,UUID.randomUUID());
        // 다른 탭에서 2실로 바꿨다. 1실 금액이 보이던 화면에서 결제하면 결제하지 않고 다시 확인하게 한다.
        service.change(owner.getLoginId(),id,stock,2,2);
        mvc.perform(post("/my/reservations/"+id+"/pay").with(user(owner.getLoginId())).with(csrf())
                .param("idempotencyKey",UUID.randomUUID().toString()).param("expectedAmount","100000.00"))
            .andExpect(status().isConflict())
            .andExpect(content().string(containsString("금액이 바뀌었어요. 다시 확인해 주세요.")))
            .andExpect(content().string(containsString("href=\"/my/reservations/"+id+"\"")));
        assertThat(count("SELECT COUNT(*) FROM dbo.virtual_payments WHERE reservation_id=?",id)).isZero();
        mvc.perform(post("/my/reservations/"+id+"/pay").with(user(owner.getLoginId())).with(csrf())
                .param("idempotencyKey",UUID.randomUUID().toString()).param("expectedAmount","200000.00"))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("savedMessage",containsString("결제가 완료")));
        assertThat(reservationStatus(id)).isEqualTo("CONFIRMED");
    }

    @Test void bookingNeedsAPlaceFromMyOwnItinerary() {
        var owner=member(); var other=member(); long stock=stock(2);
        long beach=attraction("테스트 해변","여행 > 관광,명소 > 해수욕장,해변");
        long beachTrip=jdbc.queryForObject("""
            INSERT INTO dbo.itineraries(user_id, destination_place_id, title, start_date, end_date, transportation, conditions_snapshot_json)
            OUTPUT inserted.itinerary_id VALUES (?,?,N'해변 일정',?,?,'CAR','{}')""",Long.class,owner.getId(),hotel,LocalDate.of(2031,1,1),LocalDate.of(2031,1,1));
        jdbc.update("""
            INSERT INTO dbo.itinerary_items(itinerary_id, day_no, sequence_no, item_type, place_id, start_at, end_at, activity)
            VALUES (?,1,1,'VISIT',?,?,?,N'산책')""",beachTrip,beach,LocalDateTime.of(2031,1,1,10,0),LocalDateTime.of(2031,1,1,11,0));
        // 일정 없이, 이 숙소가 없는 일정으로, 남의 일정으로는 예약하지 못하고 수량도 그대로다.
        assertThatThrownBy(()->service.reserve(owner.getLoginId(),stock,null,1,1,UUID.randomUUID()))
            .satisfies(e->assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(()->service.reserve(owner.getLoginId(),stock,beachTrip,1,1,UUID.randomUUID()))
            .satisfies(e->assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST)).hasMessageContaining("이 일정에 없는 장소");
        assertThatThrownBy(()->service.reserve(owner.getLoginId(),stock,trip(other),1,1,UUID.randomUUID()))
            .satisfies(e->assertThat(statusOf(e)).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(remaining(stock)).isEqualTo(2);
    }

    @Test void theBookingFormMakesRoomsOnlyForDatesInTheItinerary() throws Exception {
        var owner=member();
        var kst=ZoneId.of("Asia/Seoul");
        var date=LocalDate.now(kst).plusDays(30+RANDOM.nextInt(300));
        long lodging=kakaoPlace("테스트 호텔","강원특별자치도 강릉시 날짜로 "+RANDOM.nextInt(100_000));
        long itinerary=jdbc.queryForObject("""
            INSERT INTO dbo.itineraries(user_id, destination_place_id, title, start_date, end_date, transportation, conditions_snapshot_json)
            OUTPUT inserted.itinerary_id VALUES (?,?,N'2박 일정',?,?,'CAR','{}')""",Long.class,owner.getId(),hotel,date,date.plusDays(2));
        jdbc.update("""
            INSERT INTO dbo.itinerary_items(itinerary_id, day_no, sequence_no, item_type, place_id, start_at, end_at, activity)
            VALUES (?,1,1,'STAY',?,?,?,N'숙박'), (?,2,1,'STAY',?,?,?,N'숙박'), (?,3,1,'STAY',?,?,?,N'퇴실')""",
            itinerary,lodging,date.atTime(17,0),date.atTime(18,0),itinerary,lodging,date.plusDays(1).atTime(17,0),date.plusDays(1).atTime(18,0),
            itinerary,lodging,date.plusDays(2).atTime(9,0),date.plusDays(2).atTime(10,0));
        long room=service.rooms(lodging,date).get(1).stockId();
        try {
            assertThat(count("SELECT COUNT(*) FROM dbo.reservation_stock WHERE place_id=?",lodging)).isEqualTo(6);
            String form="/my/reservations/new?stockId="+room+"&itineraryId="+itinerary;
            // 일정에 있는 둘째 날은 객실을 만들어 보여 준다.
            mvc.perform(get("/my/reservations/new").param("stockId",String.valueOf(room)).param("itineraryId",String.valueOf(itinerary))
                    .param("date",date.plusDays(1).toString()).with(user(owner.getLoginId())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("min=\""+LocalDate.now(kst).plusDays(1)+"\"")));
            assertThat(count("SELECT COUNT(*) FROM dbo.reservation_stock WHERE place_id=?",lodging)).isEqualTo(12);
            // 퇴실하는 날·일정에 없는 날은 수량을 만들지 않고 신청 화면으로 돌아가 이유를 알려 준다.
            for(var other:List.of(date.plusDays(2),date.plusDays(5)))
                mvc.perform(get("/my/reservations/new").param("stockId",String.valueOf(room)).param("itineraryId",String.valueOf(itinerary))
                        .param("date",other.toString()).with(user(owner.getLoginId())))
                    .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(form))
                    .andExpect(flash().attribute("dateError",containsString("일정에 있는 날짜를 골라 주세요")));
            mvc.perform(get("/my/reservations/new").param("stockId",String.valueOf(room)).param("itineraryId",String.valueOf(itinerary))
                    .param("date",LocalDate.now(kst).plusDays(400).toString()).with(user(owner.getLoginId())))
                .andExpect(redirectedUrl(form)).andExpect(flash().attribute("dateError",containsString("내일부터 1년 안")));
            assertThat(count("SELECT COUNT(*) FROM dbo.reservation_stock WHERE place_id=?",lodging)).isEqualTo(12);
        } finally {
            stockIds.addAll(jdbc.queryForList("SELECT stock_id FROM dbo.reservation_stock WHERE place_id=?",Long.class,lodging));
        }
    }

    @Test void reservationsLeftBehindByItineraryEditsStayVisible() {
        var owner=member();
        var start=LocalDateTime.of(2032,1,1,15,0).plusDays(RANDOM.nextInt(3000));
        long stock=stockAt(start,2);
        long itinerary=jdbc.queryForObject("""
            INSERT INTO dbo.itineraries(user_id, destination_place_id, title, start_date, end_date, transportation, conditions_snapshot_json)
            OUTPUT inserted.itinerary_id VALUES (?,?,N'수정될 일정',?,?,'CAR','{}')""",Long.class,owner.getId(),hotel,start.toLocalDate(),start.toLocalDate().plusDays(1));
        jdbc.update("""
            INSERT INTO dbo.itinerary_items(itinerary_id, day_no, sequence_no, item_type, place_id, start_at, end_at, activity)
            VALUES (?,1,1,'STAY',?,?,?,N'숙박')""",itinerary,hotel,start.plusHours(1),start.plusHours(18));
        long id=service.reserve(owner.getLoginId(),stock,itinerary,1,1,UUID.randomUUID());
        assertThat(service.leftoverReservations(itinerary,owner.getLoginId(),service.bookable(itinerary,owner.getLoginId()))).isEmpty();
        // 수정안을 적용해 숙소가 바뀌었다. 예약은 취소되지 않고 '일정에서 빠진 장소의 예약'으로 남는다.
        long otherLodging=kakaoPlace("다른 펜션","강원특별자치도 강릉시 다른길 "+RANDOM.nextInt(100_000));
        jdbc.update("UPDATE dbo.itinerary_items SET place_id=? WHERE itinerary_id=?",otherLodging,itinerary);
        var bookables=service.bookable(itinerary,owner.getLoginId());
        assertThat(service.leftoverReservations(itinerary,owner.getLoginId(),bookables))
            .extracting(com.travel.travel_recommendation.dto.VirtualReservationView.Summary::id).containsExactly(id);
        // 취소한 예약은 보여 주지 않는다.
        service.cancel(owner.getLoginId(),id);
        assertThat(service.leftoverReservations(itinerary,owner.getLoginId(),bookables)).isEmpty();
    }

    @Test void pastDatesInAnItineraryAreNotOfferedForBooking() {
        var owner=member();
        var yesterday=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
        stockAt(yesterday.atTime(15,0),2);
        long itinerary=jdbc.queryForObject("""
            INSERT INTO dbo.itineraries(user_id, destination_place_id, title, start_date, end_date, transportation, conditions_snapshot_json)
            OUTPUT inserted.itinerary_id VALUES (?,?,N'지난 일정',?,?,'CAR','{}')""",Long.class,owner.getId(),hotel,yesterday,yesterday.plusDays(1));
        jdbc.update("""
            INSERT INTO dbo.itinerary_items(itinerary_id, day_no, sequence_no, item_type, place_id, start_at, end_at, activity)
            VALUES (?,1,1,'STAY',?,?,?,N'숙박')""",itinerary,hotel,yesterday.atTime(16,0),yesterday.atTime(23,0));
        assertThat(service.bookable(itinerary,owner.getLoginId()))
            .isNotEmpty().allSatisfy(b->assertThat(b.past()).isTrue());
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
