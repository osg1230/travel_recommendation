package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.VirtualReservationView.*;
import com.travel.travel_recommendation.entity.User;
import com.travel.travel_recommendation.entity.UserStatus;
import com.travel.travel_recommendation.repository.UserRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * 가상 예약·결제(SFR-13-01~07)와 무결성 규칙(SFR-16-01~03). DB 정의서 15~18절과 'R3 트랜잭션과 상태 전이'를 따른다.
 * <ul>
 * <li>수량은 {@code remaining_quantity >= 요청 수량} 조건부 UPDATE로만 차감하고, 영향 행이 1일 때만 예약을 만든다.</li>
 * <li>예약 생성은 request_key, 결제는 idempotency_key UNIQUE로 중복을 막는다. 충돌하면 롤백한 뒤 기존 결과를 돌려준다.</li>
 * <li>예약·수량·결제·로그 변경은 한 트랜잭션이다. 예기치 않은 예외는 전체를 롤백하고 운영 이력에 실패를 남긴다.</li>
 * </ul>
 * 실제 시설에 예약이 전달되지 않고 결제수단은 TEST 고정이며 금융정보를 받지 않는다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
public class VirtualReservationService {
    public static final int MAX_ROOMS = 5;
    public static final int MAX_PEOPLE = 20;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String STOCK_SQL = """
        SELECT s.stock_id, s.place_id, p.place_name, p.place_type, p.address, p.source_place_id, s.usage_start_at, s.usage_end_at,
               s.total_quantity, s.remaining_quantity, s.unit_price, s.quantity_unit, s.is_active
        FROM dbo.reservation_stock s JOIN dbo.places p ON p.place_id=s.place_id
        """;

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final ObjectMapper mapper;
    private final VirtualPaymentGateway gateway;
    private final TransactionTemplate tx;
    private final TransactionTemplate failureTx;

    public VirtualReservationService(JdbcTemplate jdbc, UserRepository users, ObjectMapper mapper,
                                     VirtualPaymentGateway gateway, PlatformTransactionManager transactions) {
        this.jdbc=jdbc; this.users=users; this.mapper=mapper; this.gateway=gateway;
        tx=new TransactionTemplate(transactions); tx.setTimeout(10);
        failureTx=new TransactionTemplate(transactions);
        failureTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); failureTx.setTimeout(5);
    }

    private record Row(Long id, Long userId, Long stockId, Long itineraryId, int quantity, int partySize,
                       BigDecimal amount, String status, boolean paymentRequired) {}
    private record Change(int before, int after) {}

    // ───────────── 조회 ─────────────

    /**
     * 저장 일정의 숙박·관광 항목 중 같은 날짜에 가상 수량이 있는 장소를 찾는다. 본인 일정만 조회한다.
     * 가상 수량은 시스템 장소에만 있어, 카카오로 찾은 같은 곳(예: '오죽헌' ↔ '오죽헌·시립박물관')도
     * 장소 유형과 공백을 뺀 주소가 같고 이름이 한쪽에 포함되면 같은 장소로 본다.
     */
    @Transactional(readOnly=true,timeout=10)
    public List<Bookable> bookable(Long itineraryId, String loginId) {
        Long userId=member(loginId);
        var rows=jdbc.query("""
            SELECT DISTINCT i.day_no, i.item_type, s.stock_id
            FROM dbo.itinerary_items i
            JOIN dbo.itineraries t ON t.itinerary_id=i.itinerary_id AND t.user_id=?
            JOIN dbo.places ip ON ip.place_id=i.place_id
            JOIN dbo.reservation_stock s ON s.is_active=1 AND CAST(s.usage_start_at AS DATE)=CAST(i.start_at AS DATE)
            JOIN dbo.places sp ON sp.place_id=s.place_id
            WHERE i.itinerary_id=? AND i.item_type IN ('STAY','VISIT')
              AND (sp.place_id=ip.place_id
                   OR (sp.place_type=ip.place_type
                       AND REPLACE(sp.address,' ','')=REPLACE(ip.address,' ','')
                       AND (CHARINDEX(REPLACE(ip.place_name,' ',''),REPLACE(sp.place_name,' ',''))>0
                            OR CHARINDEX(REPLACE(sp.place_name,' ',''),REPLACE(ip.place_name,' ',''))>0)))
            ORDER BY i.day_no, s.stock_id""",
            (rs,n)->new Object[]{rs.getInt(1),rs.getString(2),rs.getLong(3)},userId,itineraryId);
        var result=new ArrayList<Bookable>();
        for(var r:rows) {
            var stock=stock((Long)r[2]).orElseThrow();
            Long active=jdbc.query("""
                SELECT TOP 1 reservation_id FROM dbo.virtual_reservations
                WHERE user_id=? AND itinerary_id=? AND stock_id=? AND status<>'CANCELED' ORDER BY reservation_id DESC""",
                (rs,n)->rs.getLong(1),userId,itineraryId,stock.stockId()).stream().findFirst().orElse(null);
            result.add(new Bookable((Integer)r[0],"STAY".equals(r[1])?"숙박":"관광",stock,active));
        }
        return result;
    }

    @Transactional(readOnly=true,timeout=10)
    public Stock stockForBooking(Long stockId, String loginId) {
        member(loginId);
        return bookableStock(stockId);
    }

    /** 같은 장소의 다른 날짜 수량. 예약 신청·변경 화면에서 날짜를 바꿀 때 쓴다. */
    @Transactional(readOnly=true,timeout=10)
    public Optional<Stock> stockOn(Long placeId, LocalDate date) {
        return jdbc.query(STOCK_SQL+" WHERE s.place_id=? AND s.is_active=1 AND CAST(s.usage_start_at AS DATE)=? ORDER BY s.stock_id",
            this::stockRow,placeId,date).stream().findFirst();
    }

    @Transactional(readOnly=true,timeout=10)
    public List<Summary> mine(String loginId) {
        Long userId=member(loginId);
        return jdbc.query("""
            SELECT r.reservation_id, r.booking_snapshot_json, r.quantity, r.party_size, r.expected_amount, r.status, r.created_at,
                   (SELECT TOP 1 v.status FROM dbo.virtual_payments v WHERE v.reservation_id=r.reservation_id ORDER BY v.payment_id DESC) pay
            FROM dbo.virtual_reservations r WHERE r.user_id=? ORDER BY r.created_at DESC, r.reservation_id DESC""",
            (rs,n)->summary(rs),userId);
    }

    @Transactional(readOnly=true,timeout=10)
    public Detail detail(Long reservationId, String loginId) {
        Long userId=member(loginId);
        var list=jdbc.query("""
            SELECT r.reservation_id, r.booking_snapshot_json, r.quantity, r.party_size, r.expected_amount, r.status, r.created_at,
                   (SELECT TOP 1 v.status FROM dbo.virtual_payments v WHERE v.reservation_id=r.reservation_id ORDER BY v.payment_id DESC) pay,
                   r.itinerary_id, r.payment_required, r.stock_id
            FROM dbo.virtual_reservations r WHERE r.reservation_id=? AND r.user_id=?""",
            (rs,n)->new Object[]{summary(rs),(Long)rs.getObject("itinerary_id",Long.class),rs.getBoolean("payment_required"),rs.getLong("stock_id")},
            reservationId,userId);
        if(list.isEmpty()) throw notFound();
        var row=list.get(0);
        var payments=jdbc.query("""
            SELECT payment_id, amount, status, failure_code, created_at, processed_at, refunded_at
            FROM dbo.virtual_payments WHERE reservation_id=? ORDER BY payment_id""",
            (rs,n)->new Payment(rs.getLong(1),rs.getBigDecimal(2),rs.getString(3),rs.getString(4),
                time(rs,5),time(rs,6),time(rs,7)),reservationId);
        var logs=jdbc.query("""
            SELECT event_type, before_quantity, delta_quantity, after_quantity, before_status, after_status, reason, created_at
            FROM dbo.reservation_logs WHERE reservation_id=? ORDER BY reservation_log_id""",
            (rs,n)->new Log(rs.getString(1),rs.getInt(2),rs.getInt(3),rs.getInt(4),rs.getString(5),rs.getString(6),
                rs.getString(7),time(rs,8)),reservationId);
        return new Detail((Summary)row[0],(Long)row[1],(Boolean)row[2],stock((Long)row[3]).orElse(null),payments,logs);
    }

    // ───────────── 예약 신청(SFR-13-01, SFR-16-01) ─────────────

    /**
     * 가상 예약을 만든다. 같은 request_key를 다시 보내면(새로고침·연속 클릭) 새로 만들지 않고 처음 예약을 돌려준다.
     * 남은 수량이 부족하면 409로 거절하며 아무것도 저장하지 않는다.
     */
    public long reserve(String loginId, Long stockId, Long itineraryId, int quantity, int partySize, UUID requestKey) {
        Long userId=member(loginId);
        if(requestKey==null) throw bad("예약 요청 정보가 없어요. 예약 화면을 다시 열어 주세요.");
        try {
            return guarded("RESERVATION",null,"RESERVATION_CREATE",
                ()->tx.execute(s->createInTx(userId,stockId,itineraryId,quantity,partySize,requestKey)));
        } catch(DuplicateKeyException sameKey) {
            // 동시에 온 같은 요청이다. 이쪽 차감은 롤백됐고, 먼저 커밋된 예약을 돌려준다.
            return tx.execute(s->existingByRequestKey(userId,requestKey)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"예약 요청이 겹쳤어요. 내 예약 목록에서 확인해 주세요.")));
        }
    }

    private long createInTx(Long userId, Long stockId, Long itineraryId, int quantity, int partySize, UUID requestKey) {
        var existing=existingByRequestKey(userId,requestKey);
        if(existing.isPresent()) return existing.get();
        if(itineraryId!=null && jdbc.queryForObject("SELECT COUNT(*) FROM dbo.itineraries WHERE itinerary_id=? AND user_id=?",
                Integer.class,itineraryId,userId)==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"연결할 내 일정을 찾지 못했어요.");
        var stock=bookableStock(stockId);
        validate(stock,quantity,partySize);
        var change=take(stockId,quantity);
        BigDecimal amount=stock.unitPrice().multiply(BigDecimal.valueOf(quantity));
        boolean paymentRequired=amount.signum()>0;
        String status=paymentRequired?"PENDING":"CONFIRMED";
        Long id=jdbc.queryForObject("""
            INSERT INTO dbo.virtual_reservations(user_id, stock_id, itinerary_id, request_key, quantity, party_size,
                   expected_amount, status, payment_required, booking_snapshot_json)
            OUTPUT inserted.reservation_id VALUES (?,?,?,?,?,?,?,?,?,?)""",Long.class,
            userId,stockId,itineraryId,requestKey.toString(),quantity,partySize,amount,status,paymentRequired,snapshot(stock));
        log(id,stockId,UUID.randomUUID(),"CREATE",change,null,status,"가상 예약 신청");
        return id;
    }

    private Optional<Long> existingByRequestKey(Long userId, UUID requestKey) {
        var rows=jdbc.query("SELECT reservation_id, user_id FROM dbo.virtual_reservations WHERE request_key=?",
            (rs,n)->new long[]{rs.getLong(1),rs.getLong(2)},requestKey.toString());
        if(rows.isEmpty()) return Optional.empty();
        if(rows.get(0)[1]!=userId) throw new ResponseStatusException(HttpStatus.CONFLICT,"이미 사용된 예약 요청이에요. 예약 화면을 다시 열어 주세요.");
        return Optional.of(rows.get(0)[0]);
    }

    // ───────────── 예약 변경(SFR-13-03) ─────────────

    /** PENDING 또는 결제 미사용 CONFIRMED 예약의 날짜(같은 장소의 다른 수량 구간)·수량·인원을 바꾸고 금액을 다시 계산한다. */
    public void change(String loginId, Long reservationId, Long newStockId, int quantity, int partySize) {
        Long userId=member(loginId);
        guarded("RESERVATION",reservationId,"RESERVATION_CHANGE",()->tx.execute(s->{
            var r=lockOwned(reservationId,userId);
            if("CANCELED".equals(r.status())) throw conflict("취소된 예약은 바꿀 수 없어요.");
            if(!"PENDING".equals(r.status()) && r.paymentRequired())
                throw conflict("결제가 끝난 예약은 바꿀 수 없어요. 예약을 취소(환불)한 뒤 새로 예약해 주세요.");
            var current=stock(r.stockId()).orElseThrow();
            var target=bookableStock(newStockId);
            if(!target.placeId().equals(current.placeId())) throw bad("같은 장소의 다른 날짜로만 바꿀 수 있어요.");
            validate(target,quantity,partySize);
            var op=UUID.randomUUID();
            if(target.stockId().equals(current.stockId())) {
                int delta=quantity-r.quantity();
                var change=delta>0?take(current.stockId(),delta):delta<0?giveBack(current.stockId(),-delta):unchanged(current.stockId());
                log(r.id(),current.stockId(),op,"CHANGE",change,r.status(),r.status(),"인원·수량 변경");
            } else {
                // 여러 재고를 잠글 때는 stock_id 순서를 통일해 교착을 피한다.
                boolean oldFirst=current.stockId()<target.stockId();
                Change restored=null, taken=null;
                if(oldFirst) restored=giveBack(current.stockId(),r.quantity());
                taken=take(target.stockId(),quantity);
                if(!oldFirst) restored=giveBack(current.stockId(),r.quantity());
                log(r.id(),current.stockId(),op,"CHANGE",restored,r.status(),r.status(),"날짜 변경: 이전 구간 수량 복구");
                log(r.id(),target.stockId(),op,"CHANGE",taken,r.status(),r.status(),"날짜 변경: 새 구간 수량 차감");
            }
            BigDecimal amount=target.unitPrice().multiply(BigDecimal.valueOf(quantity));
            jdbc.update("""
                UPDATE dbo.virtual_reservations SET stock_id=?, quantity=?, party_size=?, expected_amount=?,
                       booking_snapshot_json=?, updated_at=SYSUTCDATETIME() WHERE reservation_id=?""",
                target.stockId(),quantity,partySize,amount,snapshot(target),r.id());
            return null;
        }));
    }

    // ───────────── 예약 취소·환불(SFR-13-04·07) ─────────────

    /**
     * 예약을 취소하고 점유 수량을 한 번만 복구한다. 성공 결제가 있으면 같은 트랜잭션에서 REFUNDED로 바꾼다.
     * 이미 취소된 예약은 아무것도 바꾸지 않고 ALREADY_CANCELED를 돌려준다(재취소·재환불 차단).
     */
    public CancelOutcome cancel(String loginId, Long reservationId) {
        Long userId=member(loginId);
        var outcome=guarded("RESERVATION",reservationId,"RESERVATION_CANCEL",()->tx.execute(s->{
            var r=lockOwned(reservationId,userId);
            if("CANCELED".equals(r.status())) return CancelOutcome.ALREADY_CANCELED;
            return cancelLocked(r,"회원 취소");
        }));
        if(outcome==CancelOutcome.ALREADY_CANCELED)
            recordFailure(userId,"RESERVATION",reservationId,"RESERVATION_CANCEL","CANCELED","ALREADY_CANCELED");
        return outcome;
    }

    private CancelOutcome cancelLocked(Row r, String reason) {
        int refunded=jdbc.update("""
            UPDATE dbo.virtual_payments SET status='REFUNDED', refunded_at=SYSUTCDATETIME()
            WHERE reservation_id=? AND status='SUCCESS'""",r.id());
        jdbc.update("""
            UPDATE dbo.virtual_payments SET status='FAILED', failure_code='RESERVATION_CANCELED', processed_at=SYSUTCDATETIME()
            WHERE reservation_id=? AND status='READY'""",r.id());
        int canceled=jdbc.update("""
            UPDATE dbo.virtual_reservations SET status='CANCELED', canceled_at=SYSUTCDATETIME(), updated_at=SYSUTCDATETIME()
            WHERE reservation_id=? AND status IN ('PENDING','CONFIRMED')""",r.id());
        if(canceled!=1) throw new IllegalStateException("Reservation state changed while locked");
        var change=giveBack(r.stockId(),r.quantity());
        log(r.id(),r.stockId(),UUID.randomUUID(),"CANCEL",change,r.status(),"CANCELED",refunded>0?reason+" · 가상 환불":reason);
        return refunded>0?CancelOutcome.REFUNDED:CancelOutcome.CANCELED;
    }

    // ───────────── 가상 결제(SFR-13-05, SFR-16-02) ─────────────

    /**
     * 테스트 결제수단으로 한 번만 결제한다. 같은 멱등키·같은 내용이면 처음 결과를 돌려주고, 같은 키로 다른 예약·금액을 보내면 거절한다.
     * 승인은 결제 SUCCESS와 예약 CONFIRMED를 함께 커밋하고, 거절은 FAILED로 남기며 예약은 PENDING으로 둔다.
     */
    public PaymentResult pay(String loginId, Long reservationId, UUID idempotencyKey, boolean simulateDecline) {
        Long userId=member(loginId);
        if(idempotencyKey==null) throw bad("결제 요청 정보가 없어요. 예약 화면을 다시 열어 주세요.");
        try {
            return guarded("PAYMENT",reservationId,"PAYMENT_PROCESS",
                ()->tx.execute(s->payInTx(userId,reservationId,idempotencyKey,simulateDecline)));
        } catch(DuplicateKeyException sameKey) {
            // 같은 멱등키가 동시에 들어왔다. 먼저 커밋된 결제 결과를 다시 읽어 돌려준다.
            return tx.execute(s->replay(userId,reservationId,idempotencyKey)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"결제 요청이 겹쳤어요. 예약 상세에서 결제 상태를 확인해 주세요.")));
        }
    }

    private PaymentResult payInTx(Long userId, Long reservationId, UUID key, boolean simulateDecline) {
        var replayed=replay(userId,reservationId,key);
        if(replayed.isPresent()) return replayed.get();
        // 예약 행을 먼저 잠가 다른 멱등키의 동시 결제도 한 줄로 세운다.
        var r=lockOwned(reservationId,userId);
        // 잠금을 기다리는 동안 같은 키의 요청이 먼저 커밋됐을 수 있다. 그때는 거절하지 않고 처음 결과를 돌려준다.
        var committed=replay(userId,reservationId,key);
        if(committed.isPresent()) return committed.get();
        if("CANCELED".equals(r.status())) throw conflict("취소된 예약은 결제할 수 없어요.");
        if(!"PENDING".equals(r.status())) throw conflict("이미 결제가 끝났거나 결제가 필요 없는 예약이에요.");
        Long paymentId=jdbc.queryForObject("""
            INSERT INTO dbo.virtual_payments(reservation_id, idempotency_key, request_hash, amount, status)
            OUTPUT inserted.payment_id VALUES (?,?,?,?,'READY')""",Long.class,
            r.id(),key.toString(),hash(r.id(),r.amount()),r.amount());
        var outcome=gateway.charge(r.id(),r.amount(),simulateDecline);
        if(outcome.approved()) {
            jdbc.update("UPDATE dbo.virtual_payments SET status='SUCCESS', processed_at=SYSUTCDATETIME() WHERE payment_id=? AND status='READY'",paymentId);
            if(jdbc.update("UPDATE dbo.virtual_reservations SET status='CONFIRMED', updated_at=SYSUTCDATETIME() WHERE reservation_id=? AND status='PENDING'",r.id())!=1)
                throw new IllegalStateException("Reservation state changed while locked");
            return new PaymentResult(paymentId,"SUCCESS",false);
        }
        jdbc.update("UPDATE dbo.virtual_payments SET status='FAILED', failure_code=?, processed_at=SYSUTCDATETIME() WHERE payment_id=? AND status='READY'",
            outcome.failureCode()==null?VirtualPaymentGateway.DECLINED:outcome.failureCode(),paymentId);
        return new PaymentResult(paymentId,"FAILED",false);
    }

    /** 같은 멱등키의 기존 결제. 요청 내용(예약·금액·수단)이 다르면 거절한다. 다른 회원의 키는 없는 것으로 본다. */
    private Optional<PaymentResult> replay(Long userId, Long reservationId, UUID key) {
        var rows=jdbc.query("""
            SELECT v.payment_id, v.status, v.request_hash, r.user_id, r.reservation_id
            FROM dbo.virtual_payments v JOIN dbo.virtual_reservations r ON r.reservation_id=v.reservation_id
            WHERE v.idempotency_key=?""",
            (rs,n)->new Object[]{rs.getLong(1),rs.getString(2),rs.getBytes(3),rs.getLong(4),rs.getLong(5)},key.toString());
        if(rows.isEmpty()) return Optional.empty();
        var row=rows.get(0);
        if(!userId.equals(row[3])) throw conflict("이미 사용된 결제 요청이에요. 예약 화면을 다시 열어 주세요.");
        var amount=jdbc.query("SELECT expected_amount FROM dbo.virtual_reservations WHERE reservation_id=? AND user_id=?",
            (rs,n)->rs.getBigDecimal(1),reservationId,userId).stream().findFirst().orElseThrow(this::notFound);
        if(!Arrays.equals((byte[])row[2],hash(reservationId,amount)))
            throw conflict("같은 결제 요청으로 다른 예약이나 금액을 결제할 수 없어요. 예약 화면을 다시 열어 주세요.");
        return Optional.of(new PaymentResult((Long)row[0],(String)row[1],true));
    }

    // ───────────── 일정 삭제·탈퇴 연동 ─────────────

    /** 일정 삭제 전에 호출한다. 예약은 남기고 일정 연결만 끊는다(정의서 '삭제와 참조 유지'). */
    public void detachItinerary(Long itineraryId) {
        jdbc.update("UPDATE dbo.virtual_reservations SET itinerary_id=NULL, updated_at=SYSUTCDATETIME() WHERE itinerary_id=?",itineraryId);
    }

    /** 탈퇴 트랜잭션 안에서 호출한다. 점유 중 수량을 취소 절차로 한 번 복구한 뒤 로그·결제·예약을 지운다. */
    public void purgeForWithdrawal(Long userId) {
        var active=jdbc.query("""
            SELECT reservation_id, user_id, stock_id, itinerary_id, quantity, party_size, expected_amount, status, payment_required
            FROM dbo.virtual_reservations WITH (UPDLOCK, ROWLOCK) WHERE user_id=? AND status<>'CANCELED' ORDER BY stock_id, reservation_id""",
            this::row,userId);
        for(var r:active) cancelLocked(r,"회원 탈퇴");
        jdbc.update("DELETE l FROM dbo.reservation_logs l JOIN dbo.virtual_reservations r ON r.reservation_id=l.reservation_id WHERE r.user_id=?",userId);
        jdbc.update("DELETE v FROM dbo.virtual_payments v JOIN dbo.virtual_reservations r ON r.reservation_id=v.reservation_id WHERE r.user_id=?",userId);
        jdbc.update("DELETE FROM dbo.virtual_reservations WHERE user_id=?",userId);
    }

    // ───────────── 내부 도우미 ─────────────

    /** 조건부 차감. 영향 행이 없으면 매진 또는 수량 부족이다(SFR-16-01). */
    private Change take(Long stockId, int quantity) {
        var rows=jdbc.query("""
            UPDATE dbo.reservation_stock SET remaining_quantity=remaining_quantity-?, updated_at=SYSUTCDATETIME()
            OUTPUT deleted.remaining_quantity, inserted.remaining_quantity
            WHERE stock_id=? AND is_active=1 AND remaining_quantity>=?""",
            (rs,n)->new Change(rs.getInt(1),rs.getInt(2)),quantity,stockId,quantity);
        if(rows.size()!=1) {
            int left=jdbc.queryForObject("SELECT remaining_quantity FROM dbo.reservation_stock WHERE stock_id=?",Integer.class,stockId);
            throw conflict(left==0?"이 날짜는 매진됐어요. 다른 날짜를 골라 주세요.":"남은 수량("+left+")보다 많이 예약할 수 없어요.");
        }
        return rows.get(0);
    }

    private Change giveBack(Long stockId, int quantity) {
        var rows=jdbc.query("""
            UPDATE dbo.reservation_stock SET remaining_quantity=remaining_quantity+?, updated_at=SYSUTCDATETIME()
            OUTPUT deleted.remaining_quantity, inserted.remaining_quantity
            WHERE stock_id=? AND remaining_quantity+?<=total_quantity""",
            (rs,n)->new Change(rs.getInt(1),rs.getInt(2)),quantity,stockId,quantity);
        if(rows.size()!=1) throw new IllegalStateException("Stock restore exceeded total quantity");
        return rows.get(0);
    }

    private Change unchanged(Long stockId) {
        int left=jdbc.queryForObject("SELECT remaining_quantity FROM dbo.reservation_stock WITH (UPDLOCK, ROWLOCK) WHERE stock_id=?",Integer.class,stockId);
        return new Change(left,left);
    }

    private void log(Long reservationId, Long stockId, UUID operationId, String event, Change change,
                     String beforeStatus, String afterStatus, String reason) {
        jdbc.update("""
            INSERT INTO dbo.reservation_logs(reservation_id, stock_id, operation_id, event_type, before_quantity, delta_quantity,
                   after_quantity, before_status, after_status, reason) VALUES (?,?,?,?,?,?,?,?,?,?)""",
            reservationId,stockId,operationId.toString(),event,change.before(),change.after()-change.before(),change.after(),
            beforeStatus,afterStatus,reason);
    }

    private Row lockOwned(Long reservationId, Long userId) {
        return jdbc.query("""
            SELECT reservation_id, user_id, stock_id, itinerary_id, quantity, party_size, expected_amount, status, payment_required
            FROM dbo.virtual_reservations WITH (UPDLOCK, ROWLOCK) WHERE reservation_id=? AND user_id=?""",
            this::row,reservationId,userId).stream().findFirst().orElseThrow(this::notFound);
    }

    private Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getObject(4,Long.class),rs.getInt(5),rs.getInt(6),
            rs.getBigDecimal(7),rs.getString(8),rs.getBoolean(9));
    }

    private Optional<Stock> stock(Long stockId) {
        return jdbc.query(STOCK_SQL+" WHERE s.stock_id=?",this::stockRow,stockId).stream().findFirst();
    }

    /** 활성이고 이용 시작 전인 수량 구간만 예약·변경 대상으로 삼는다. */
    private Stock bookableStock(Long stockId) {
        if(stockId==null) throw bad("예약할 날짜를 골라 주세요.");
        var rows=jdbc.query(STOCK_SQL+" WHERE s.stock_id=? AND s.is_active=1",this::stockRow,stockId);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"예약할 수 있는 날짜를 찾지 못했어요.");
        var stock=rows.get(0);
        if(!stock.usageStart().isAfter(LocalDateTime.now(KST))) throw bad("이미 이용 시작 시각이 지난 날짜는 예약할 수 없어요.");
        return stock;
    }

    private Stock stockRow(ResultSet rs, int n) throws SQLException {
        String unit=rs.getString("quantity_unit");
        var entry=ReservationCatalog.of(rs.getString("source_place_id"),unit);
        return new Stock(rs.getLong("stock_id"),rs.getLong("place_id"),rs.getString("place_name"),rs.getString("place_type"),
            rs.getString("address"),time(rs,"usage_start_at"),time(rs,"usage_end_at"),rs.getInt("total_quantity"),
            rs.getInt("remaining_quantity"),rs.getBigDecimal("unit_price"),unit,entry.maxPerUnit(),entry.priceNote(),entry.sourceUrl());
    }

    /** 객실은 1~5실·객실당 최대 인원 이내, 시설은 인원만큼 수량을 점유한다. */
    private static void validate(Stock stock, int quantity, int partySize) {
        if(stock.room()) {
            if(quantity<1 || quantity>MAX_ROOMS) throw bad("객실은 1~"+MAX_ROOMS+"실까지 예약할 수 있어요.");
            if(partySize<1) throw bad("이용 인원을 1명 이상 입력해 주세요.");
            if(partySize<quantity) throw bad("객실 수보다 인원이 적을 수 없어요.");
            if(partySize>quantity*stock.maxPerUnit()) throw bad("객실당 최대 "+stock.maxPerUnit()+"명이에요. 객실 수를 늘려 주세요.");
        } else {
            if(partySize<1 || partySize>MAX_PEOPLE) throw bad("이용 인원은 1~"+MAX_PEOPLE+"명까지 예약할 수 있어요.");
            if(quantity!=partySize) throw bad("관광시설은 인원 수만큼 예약해요.");
        }
    }

    private String snapshot(Stock s) {
        var map=new LinkedHashMap<String,Object>();
        map.put("placeId",s.placeId()); map.put("placeName",s.placeName()); map.put("placeType",s.placeType());
        map.put("address",s.address()); map.put("usageStartAt",s.usageStart().toString()); map.put("usageEndAt",s.usageEnd().toString());
        map.put("unitPrice",s.unitPrice().toPlainString()); map.put("quantityUnit",s.unit()); map.put("priceNote",s.priceNote());
        return mapper.writeValueAsString(map);
    }

    private Summary summary(ResultSet rs) throws SQLException {
        var snap=mapper.readTree(rs.getString("booking_snapshot_json"));
        return new Summary(rs.getLong("reservation_id"),snap.path("placeName").asString(),
            LocalDateTime.parse(snap.path("usageStartAt").asString()),LocalDateTime.parse(snap.path("usageEndAt").asString()),
            rs.getInt("quantity"),rs.getInt("party_size"),snap.path("quantityUnit").asString(),rs.getBigDecimal("expected_amount"),
            rs.getString("status"),rs.getString("pay"),time(rs,"created_at"));
    }

    /** 요청 동일성 해시: 예약번호·금액·결제수단(TEST 상수). */
    static byte[] hash(Long reservationId, BigDecimal amount) {
        try {
            return MessageDigest.getInstance("SHA-256").digest((reservationId+"|"+amount.stripTrailingZeros().toPlainString()+"|"
                +VirtualPaymentGateway.METHOD).getBytes(StandardCharsets.UTF_8));
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /**
     * 상태 전이 거절(409)과 예기치 않은 예외(전체 롤백)를 운영 처리 이력(operation_logs)에 FAILED로 남긴다.
     * 기록은 별도 트랜잭션이라 본 작업의 롤백과 무관하게 남는다. 입력값 오류(400)·권한(404)은 남기지 않는다.
     */
    private <T> T guarded(String targetType, Long targetId, String action, Supplier<T> work) {
        try {
            return work.get();
        } catch(DuplicateKeyException duplicate) {
            throw duplicate;
        } catch(ResponseStatusException rejected) {
            if(rejected.getStatusCode()==HttpStatus.CONFLICT && targetId!=null)
                recordFailure(currentUserOf(targetType,targetId),targetType,targetId,action,null,"REJECTED: "+rejected.getReason());
            throw rejected;
        } catch(RuntimeException unexpected) {
            if(targetId!=null) recordFailure(currentUserOf(targetType,targetId),targetType,targetId,action,null,
                "ROLLBACK: "+unexpected.getClass().getSimpleName());
            throw unexpected;
        }
    }

    private Long currentUserOf(String targetType, Long reservationId) {
        return jdbc.query("SELECT user_id FROM dbo.virtual_reservations WHERE reservation_id=?",(rs,n)->rs.getLong(1),reservationId)
            .stream().findFirst().orElse(null);
    }

    private void recordFailure(Long actorUserId, String targetType, Long targetId, String action, String beforeStatus, String reason) {
        try {
            failureTx.executeWithoutResult(s->jdbc.update("""
                INSERT INTO dbo.operation_logs(actor_user_id, actor_kind, target_type, target_id, action_code, before_status, reason, outcome)
                VALUES (?,?,?,?,?,?,?,'FAILED')""",actorUserId,actorUserId==null?"SYSTEM":"MEMBER",targetType,targetId,action,beforeStatus,
                reason.length()>1000?reason.substring(0,1000):reason));
        } catch(RuntimeException ignored) {
            // 실패 기록이 안 돼도 사용자에게 보여 줄 원래 결과를 바꾸지 않는다.
        }
    }

    private static LocalDateTime time(ResultSet rs, int column) throws SQLException {
        Timestamp t=rs.getTimestamp(column); return t==null?null:t.toLocalDateTime();
    }
    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        Timestamp t=rs.getTimestamp(column); return t==null?null:t.toLocalDateTime();
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"예약을 찾지 못했어요. 내 예약만 볼 수 있어요."); }

    private Long member(String loginId) {
        if(loginId==null) throw new AccessDeniedException("Login required");
        return users.findByLoginId(loginId).filter(u->u.getStatus()==UserStatus.ACTIVE)
            .map(User::getId).orElseThrow(()->new AccessDeniedException("Account unavailable"));
    }
}
