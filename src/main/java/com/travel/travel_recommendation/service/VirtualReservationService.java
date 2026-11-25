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
    /** 서버가 만드는 숙소 객실: 객실 종류마다 하루 3실, 참고 가격이 없으면 기준가 100,000원, 내일부터 1년 안의 날짜만. */
    private static final int ROOMS_PER_TYPE = 3;
    private static final BigDecimal DEFAULT_ROOM_PRICE = new BigDecimal("100000");
    private static final int MAX_ADVANCE_DAYS = 365;
    /** 서버가 만드는 관광시설 입장 수량: 하루 100명. 가격은 분류별 체험가(ReservationCatalog.ticketPrice). */
    private static final int TICKETS_PER_DAY = 100;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String STOCK_SQL = """
        SELECT s.stock_id, s.place_id, p.place_name, p.place_type, p.address, p.source_place_id, s.usage_start_at, s.usage_end_at,
               s.total_quantity, s.remaining_quantity, s.unit_price, s.quantity_unit, s.is_active, s.room_type
        FROM dbo.reservation_stock s JOIN dbo.places p ON p.place_id=s.place_id
        """;
    /**
     * 일정 항목의 장소(ip)와 수량이 있는 장소(sp)가 같은 곳인지. 같은 ID이거나, 카카오로 찾은 같은 곳이면
     * (장소 유형과 시·도를 뺀 주소가 같고 이름이 한쪽에 포함) 같은 장소로 본다. bookable() 설명 참고.
     */
    private static final String SAME_PLACE = """
        (sp.place_id=ip.place_id
         OR (sp.place_type=ip.place_type
             AND REPLACE(SUBSTRING(LTRIM(sp.address),CHARINDEX(' ',LTRIM(sp.address))+1,400),' ','')
               =REPLACE(SUBSTRING(LTRIM(ip.address),CHARINDEX(' ',LTRIM(ip.address))+1,400),' ','')
             AND (CHARINDEX(REPLACE(ip.place_name,' ',''),REPLACE(sp.place_name,' ',''))>0
                  OR CHARINDEX(REPLACE(sp.place_name,' ',''),REPLACE(ip.place_name,' ',''))>0)))
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
     * 카카오는 시·도를 줄여 쓰므로('경상북도' → '경북') 주소는 맨 앞 시·도 이름을 빼고 비교한다.
     * 숙소는 모두 예약할 수 있게, 수량이 없는 숙박 항목(체크아웃하는 마지막 날 제외)은 그 날짜의 객실 수량을 만들어 둔다.
     * 관광 항목은 입장권을 끊는 분류의 시설만 같은 방식으로 입장 수량을 만든다.
     * 숙소는 객실 종류가 여러 개라 대표로 스탠다드 더블 한 행만 돌려주고, 종류는 예약 신청 화면에서 고른다.
     */
    @Transactional(timeout=10)
    public List<Bookable> bookable(Long itineraryId, String loginId) { return bookable(itineraryId,loginId,Map.of()); }

    /** referencePrices: 일정을 저장할 때 찾아 둔 숙소 참고 가격(장소 ID → 원). 객실을 처음 만들 때 기준가로 쓴다. */
    @Transactional(timeout=10)
    public List<Bookable> bookable(Long itineraryId, String loginId, Map<Long,Integer> referencePrices) {
        Long userId=member(loginId);
        var found=matchingStock(userId,itineraryId);
        var stays=jdbc.query("""
            SELECT DISTINCT i.day_no, i.place_id, CAST(i.start_at AS DATE)
            FROM dbo.itinerary_items i
            JOIN dbo.itineraries t ON t.itinerary_id=i.itinerary_id AND t.user_id=?
            JOIN dbo.places p ON p.place_id=i.place_id AND p.place_type='ACCOMMODATION'
            WHERE i.itinerary_id=? AND i.item_type='STAY' AND CAST(i.start_at AS DATE)<t.end_date""",
            (rs,n)->new Object[]{rs.getInt(1),rs.getLong(2),rs.getDate(3).toLocalDate()},userId,itineraryId);
        boolean created=false;
        for(var stay:stays) {
            boolean matched=found.stream().anyMatch(r->r[0].equals(stay[0]) && r[3].equals(stay[1]));
            if(!matched) created|=ensureRooms((Long)stay[1],(LocalDate)stay[2],referencePrices.get((Long)stay[1]));
        }
        // 입장권을 끊는 관광시설(박물관·아쿠아리움·테마파크 등)도 수량이 없으면 그 날짜 분을 만든다.
        var visits=jdbc.query("""
            SELECT DISTINCT i.day_no, i.place_id, CAST(i.start_at AS DATE)
            FROM dbo.itinerary_items i
            JOIN dbo.itineraries t ON t.itinerary_id=i.itinerary_id AND t.user_id=?
            JOIN dbo.places p ON p.place_id=i.place_id AND p.place_type='ATTRACTION'
            WHERE i.itinerary_id=? AND i.item_type='VISIT'""",
            (rs,n)->new Object[]{rs.getInt(1),rs.getLong(2),rs.getDate(3).toLocalDate()},userId,itineraryId);
        for(var visit:visits) {
            boolean matched=found.stream().anyMatch(r->r[0].equals(visit[0]) && r[3].equals(visit[1]));
            if(!matched) created|=ensureTickets((Long)visit[1],(LocalDate)visit[2]);
        }
        var rows=created?matchingStock(userId,itineraryId):found;
        var result=new ArrayList<Bookable>();
        for(var r:rows) {
            var stock=stock((Long)r[2]).orElseThrow();
            // 숙소는 어느 객실 종류로 예약했든 같은 숙소·같은 날짜의 예약을 '내 예약'으로 본다.
            Long active=jdbc.query("""
                SELECT TOP 1 r.reservation_id FROM dbo.virtual_reservations r
                JOIN dbo.reservation_stock s ON s.stock_id=r.stock_id
                WHERE r.user_id=? AND r.itinerary_id=? AND r.status<>'CANCELED' AND s.place_id=? AND CAST(s.usage_start_at AS DATE)=?
                ORDER BY r.reservation_id DESC""",
                (rs,n)->rs.getLong(1),userId,itineraryId,stock.placeId(),stock.usageStart().toLocalDate()).stream().findFirst().orElse(null);
            result.add(new Bookable((Integer)r[0],(Long)r[3],"STAY".equals(r[1])?"숙박":"관광",stock,active));
        }
        return result;
    }

    private List<Object[]> matchingStock(Long userId, Long itineraryId) {
        return jdbc.query("""
            SELECT DISTINCT i.day_no, i.item_type, s.stock_id, i.place_id
            FROM dbo.itinerary_items i
            JOIN dbo.itineraries t ON t.itinerary_id=i.itinerary_id AND t.user_id=?
            JOIN dbo.places ip ON ip.place_id=i.place_id
            JOIN dbo.reservation_stock s ON s.is_active=1 AND CAST(s.usage_start_at AS DATE)=CAST(i.start_at AS DATE)
            JOIN dbo.places sp ON sp.place_id=s.place_id
            WHERE i.itinerary_id=? AND i.item_type IN ('STAY','VISIT')
              AND (s.room_type IS NULL OR s.room_type='STANDARD_DOUBLE')
              AND
            """+SAME_PLACE+"""
            ORDER BY i.day_no, s.stock_id""",
            (rs,n)->new Object[]{rs.getInt(1),rs.getString(2),rs.getLong(3),rs.getLong(4)},userId,itineraryId);
    }

    /**
     * 일정의 숙박·관광 항목에 이 장소가 있는지 본다. date를 주면 그 날짜 항목만 본다(숙박은 퇴실하는 마지막 날 제외).
     * 예약은 일정에 있는 장소만 받고, 수량 행은 일정에 있는 장소·날짜에만 만든다.
     */
    private boolean inItinerary(Long itineraryId, Long placeId, LocalDate date) {
        String sql = """
            SELECT COUNT(*) FROM dbo.itinerary_items i
            JOIN dbo.itineraries t ON t.itinerary_id=i.itinerary_id
            JOIN dbo.places ip ON ip.place_id=i.place_id
            JOIN dbo.places sp ON sp.place_id=?
            WHERE i.itinerary_id=? AND i.item_type IN ('STAY','VISIT') AND
            """+SAME_PLACE;
        if(date==null) return jdbc.queryForObject(sql,Integer.class,placeId,itineraryId)>0;
        return jdbc.queryForObject(sql+" AND CAST(i.start_at AS DATE)=? AND (i.item_type='VISIT' OR CAST(i.start_at AS DATE)<t.end_date)",
            Integer.class,placeId,itineraryId,date)>0;
    }

    /** 예약은 내 저장 일정에 있는 장소만 받는다. 주소의 번호만 바꿔 일정과 관계없는 수량을 잡지 못하게 한다. */
    private void requireItinerary(Long userId, Long itineraryId, Long placeId) {
        if(itineraryId==null) throw bad("저장한 일정에서 예약할 장소를 골라 주세요. 내 일정 상세의 '예약하기'로 시작해요.");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM dbo.itineraries WHERE itinerary_id=? AND user_id=?",
                Integer.class,itineraryId,userId)==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"연결할 내 일정을 찾지 못했어요.");
        if(!inItinerary(itineraryId,placeId,null))
            throw bad("이 일정에 없는 장소는 예약할 수 없어요. 일정 화면의 '예약하기'로 다시 시작해 주세요.");
    }

    /**
     * 숙소의 그 날짜 객실 종류별 수량을 없는 것만 만든다. 만든 행이 있으면 true.
     * 기준가는 그 숙소에 이미 있는 스탠다드 더블 가격 → 일정의 참고 가격 → 기본 체험가 순으로 정해, 같은 숙소는 날짜가 달라도 가격이 같다.
     * 지난 날짜·1년 넘게 남은 날짜·숙소가 아닌 장소에는 만들지 않는다.
     */
    private boolean ensureRooms(Long placeId, LocalDate date, Integer referenceWon) {
        if(!withinBookingWindow(date)) return false;
        if(jdbc.queryForObject("SELECT COUNT(*) FROM dbo.places WHERE place_id=? AND place_type='ACCOMMODATION' AND is_active=1",
                Integer.class,placeId)==0) return false;
        // 범위 잠금(HOLDLOCK)으로 같은 숙소·날짜의 객실을 동시에 두 번 만들지 않게 한다.
        var existing=jdbc.query("""
            SELECT room_type FROM dbo.reservation_stock WITH (UPDLOCK, HOLDLOCK)
            WHERE place_id=? AND quantity_unit='ROOM' AND CAST(usage_start_at AS DATE)=?""",
            (rs,n)->{ String type=rs.getString(1); return type==null?RoomType.STANDARD_DOUBLE.name():type; },placeId,date);
        BigDecimal base=jdbc.query("""
            SELECT TOP 1 unit_price FROM dbo.reservation_stock
            WHERE place_id=? AND quantity_unit='ROOM' AND (room_type IS NULL OR room_type='STANDARD_DOUBLE') ORDER BY stock_id DESC""",
            (rs,n)->rs.getBigDecimal(1),placeId).stream().findFirst()
            .orElse(referenceWon!=null && referenceWon>=10_000?BigDecimal.valueOf(referenceWon):DEFAULT_ROOM_PRICE);
        boolean created=false;
        for(var type:RoomType.values()) {
            if(existing.contains(type.name())) continue;
            // 입실 15:00 ~ 다음 날 퇴실 11:00의 1박 구간으로 만든다.
            jdbc.update("""
                INSERT INTO dbo.reservation_stock(place_id, usage_start_at, usage_end_at, total_quantity, remaining_quantity,
                       unit_price, quantity_unit, room_type) VALUES (?,?,?,?,?,?,'ROOM',?)""",
                placeId,date.atTime(15,0),date.plusDays(1).atTime(11,0),ROOMS_PER_TYPE,ROOMS_PER_TYPE,type.price(base),type.name());
            created=true;
        }
        return created;
    }

    /**
     * 입장권을 끊는 관광시설의 그 날짜 입장 수량이 없으면 만든다(하루 이용 09:00~18:00). 만들었으면 true.
     * 대상인지와 가격은 장소 세부 분류로 정하고, 그 장소에 이미 수량이 있으면 그 가격을 이어 쓴다.
     */
    private boolean ensureTickets(Long placeId, LocalDate date) {
        if(!withinBookingWindow(date)) return false;
        var subtypes=jdbc.query("SELECT subtype FROM dbo.places WHERE place_id=? AND place_type='ATTRACTION' AND is_active=1",
            (rs,n)->rs.getString(1),placeId);
        BigDecimal categoryPrice=subtypes.isEmpty()?null:ReservationCatalog.ticketPrice(subtypes.get(0));
        if(categoryPrice==null) return false;
        if(jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.reservation_stock WITH (UPDLOCK, HOLDLOCK)
                WHERE place_id=? AND quantity_unit='PERSON' AND CAST(usage_start_at AS DATE)=?""",Integer.class,placeId,date)>0) return false;
        BigDecimal price=jdbc.query("""
            SELECT TOP 1 unit_price FROM dbo.reservation_stock WHERE place_id=? AND quantity_unit='PERSON' ORDER BY stock_id DESC""",
            (rs,n)->rs.getBigDecimal(1),placeId).stream().findFirst().orElse(categoryPrice);
        jdbc.update("""
            INSERT INTO dbo.reservation_stock(place_id, usage_start_at, usage_end_at, total_quantity, remaining_quantity,
                   unit_price, quantity_unit) VALUES (?,?,?,?,?,?,'PERSON')""",
            placeId,date.atTime(9,0),date.atTime(18,0),TICKETS_PER_DAY,TICKETS_PER_DAY,price);
        return true;
    }

    /** 서버가 수량을 만들어 주는 날짜 범위: 내일부터 1년 안. 신청·변경 화면의 날짜 입력 범위도 같다. */
    public static boolean withinBookingWindow(LocalDate date) {
        return !date.isBefore(firstBookableDate()) && !date.isAfter(lastBookableDate());
    }
    public static LocalDate firstBookableDate() { return LocalDate.now(KST).plusDays(1); }
    public static LocalDate lastBookableDate() { return LocalDate.now(KST).plusDays(MAX_ADVANCE_DAYS); }

    /** 숙소의 그 날짜 객실 종류별 수량(예약 신청 화면의 선택지). 없는 종류는 만든 뒤 객실 종류 순서로 돌려준다. */
    @Transactional(timeout=10)
    public List<Stock> rooms(Long placeId, LocalDate date) {
        ensureRooms(placeId,date,null);
        return existingRooms(placeId,date);
    }

    private List<Stock> existingRooms(Long placeId, LocalDate date) {
        var list=jdbc.query(STOCK_SQL+" WHERE s.place_id=? AND s.is_active=1 AND s.quantity_unit='ROOM' AND CAST(s.usage_start_at AS DATE)=?",
            this::stockRow,placeId,date);
        list.sort(Comparator.comparing(s->RoomType.of(s.roomType())==null?RoomType.STANDARD_DOUBLE:RoomType.of(s.roomType())));
        return list;
    }

    /** 같은 장소의 다른 날짜 수량. 그 날짜 수량이 없으면 만든 뒤 찾는다. 숙소는 같은 객실 종류를 찾는다. */
    @Transactional(timeout=10)
    public Optional<Stock> sameOn(Stock current, LocalDate date) {
        if(!current.room()) { ensureTickets(current.placeId(),date); return stockOn(current.placeId(),date); }
        return sameRoomType(rooms(current.placeId(),date),current);
    }

    /**
     * 예약 신청 화면에서 고른 다른 날짜의 같은 장소 수량. 일정에 그 장소·날짜가 있을 때만 없는 수량을 만들고,
     * 일정에 없는 날짜는 이미 있는 수량만 찾는다(주소만 바꿔 아무 날짜에나 수량 행을 만들지 못하게).
     */
    @Transactional(timeout=10)
    public Optional<Stock> sameOnInItinerary(Stock current, Long itineraryId, LocalDate date) {
        if(inItinerary(itineraryId,current.placeId(),date)) return sameOn(current,date);
        if(!current.room()) return stockOn(current.placeId(),date);
        return sameRoomType(existingRooms(current.placeId(),date),current);
    }

    /** 예약 신청 화면의 객실 종류 선택지. 일정에 그 날짜 숙박이 있을 때만 없는 종류를 만들고, 아니면 있는 것만 보여 준다. */
    @Transactional(timeout=10)
    public List<Stock> roomsForBooking(Stock stock, Long itineraryId) {
        LocalDate date=stock.usageStart().toLocalDate();
        if(inItinerary(itineraryId,stock.placeId(),date)) ensureRooms(stock.placeId(),date,null);
        return existingRooms(stock.placeId(),date);
    }

    private static Optional<Stock> sameRoomType(List<Stock> rooms, Stock current) {
        return rooms.stream().filter(s->Objects.equals(s.roomType(),current.roomType())).findFirst().or(()->rooms.stream().findFirst());
    }

    /** 예약 신청 화면의 수량 구간. 이용 시작 전이고, 내 저장 일정에 있는 장소여야 한다. */
    @Transactional(readOnly=true,timeout=10)
    public Stock stockForBooking(Long stockId, Long itineraryId, String loginId) {
        Long userId=member(loginId);
        var stock=bookableStock(stockId);
        requireItinerary(userId,itineraryId,stock.placeId());
        return stock;
    }

    /** 같은 회원이 같은 장소·날짜에 잡아 둔 진행 중(취소 전) 예약. 신청 화면에서 이미 있는 예약을 알려 주는 데 쓴다. */
    @Transactional(readOnly=true,timeout=10)
    public Optional<Long> activeReservationOn(String loginId, Long placeId, LocalDate date) {
        Long userId=member(loginId);
        return jdbc.query("""
            SELECT TOP 1 r.reservation_id FROM dbo.virtual_reservations r
            JOIN dbo.reservation_stock s ON s.stock_id=r.stock_id
            WHERE r.user_id=? AND r.status<>'CANCELED' AND s.place_id=? AND CAST(s.usage_start_at AS DATE)=?
            ORDER BY r.reservation_id DESC""",(rs,n)->rs.getLong(1),userId,placeId,date).stream().findFirst();
    }

    /**
     * 이 일정에 연결된 진행 중 예약 가운데 지금 일정 항목과 맞지 않는 것(수정안 적용·장소 변경으로 일정에서 빠진 장소의 예약).
     * 예약은 일정이 바뀌어도 자동으로 취소하지 않으므로, 일정 화면에서 찾아갈 수 있게 따로 보여 준다.
     */
    @Transactional(readOnly=true,timeout=10)
    public List<Summary> leftoverReservations(Long itineraryId, String loginId, List<Bookable> bookables) {
        Long userId=member(loginId);
        // 지금 일정 항목과 맞는 '장소|날짜'. 이 목록에 있는 예약은 일정표의 '내 예약 보기'로 이미 이어진다.
        var matched=new HashSet<String>();
        for(var b:bookables) matched.add(b.stock().placeId()+"|"+b.stock().usageStart().toLocalDate());
        var rows=jdbc.query("""
            SELECT r.reservation_id, r.booking_snapshot_json, r.quantity, r.party_size, r.expected_amount, r.status, r.created_at,
                   (SELECT TOP 1 v.status FROM dbo.virtual_payments v WHERE v.reservation_id=r.reservation_id ORDER BY v.payment_id DESC) pay,
                   s.place_id, CAST(s.usage_start_at AS DATE) usage_date
            FROM dbo.virtual_reservations r JOIN dbo.reservation_stock s ON s.stock_id=r.stock_id
            WHERE r.user_id=? AND r.itinerary_id=? AND r.status<>'CANCELED'
            ORDER BY s.usage_start_at, r.reservation_id""",
            (rs,n)->matched.contains(rs.getLong("place_id")+"|"+rs.getDate("usage_date").toLocalDate())?null:summary(rs),
            userId,itineraryId);
        return rows.stream().filter(Objects::nonNull).toList();
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
                   r.itinerary_id, r.payment_required, r.stock_id, r.canceled_at
            FROM dbo.virtual_reservations r WHERE r.reservation_id=? AND r.user_id=?""",
            (rs,n)->new Object[]{summary(rs),(Long)rs.getObject("itinerary_id",Long.class),rs.getBoolean("payment_required"),rs.getLong("stock_id"),
                time(rs,"canceled_at")},
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
        return new Detail((Summary)row[0],(Long)row[1],(Boolean)row[2],stock((Long)row[3]).orElse(null),payments,logs,(LocalDateTime)row[4]);
    }

    // ───────────── 예약 신청(SFR-13-01, SFR-16-01) ─────────────

    /**
     * 가상 예약을 만든다. 같은 request_key를 다시 보내면(새로고침·연속 클릭) 새로 만들지 않고 처음 예약을 돌려준다.
     * 내 저장 일정(itineraryId, 필수)에 있는 장소만 예약한다. 남은 수량이 부족하면 409로 거절하며 아무것도 저장하지 않는다.
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

    /**
     * 예약 신청과 결제를 한 번에 처리한다(예약·결제 화면). 둘을 같은 트랜잭션으로 묶어,
     * 결제가 거절되면 예약·수량 차감·결제 기록을 모두 되돌린다. 그래서 결제에 실패한 예약은 남지 않는다.
     * 결제 멱등키는 예약 요청 키를 그대로 쓴다. 같은 요청 키를 다시 보내면(새로고침·연속 클릭) 처음 예약을 돌려준다.
     * 금액이 0원이라 결제가 필요 없는 예약은 신청만으로 확정된다.
     */
    public long reserveAndPay(String loginId, Long stockId, Long itineraryId, int quantity, int partySize, UUID requestKey, boolean simulateDecline) {
        Long userId=member(loginId);
        if(requestKey==null) throw bad("예약 요청 정보가 없어요. 예약 화면을 다시 열어 주세요.");
        try {
            return guarded("RESERVATION",null,"RESERVATION_CREATE",()->tx.execute(s->{
                long id=createInTx(userId,stockId,itineraryId,quantity,partySize,requestKey);
                String status=jdbc.queryForObject("SELECT status FROM dbo.virtual_reservations WHERE reservation_id=?",String.class,id);
                if("PENDING".equals(status)) {
                    var paid=payInTx(userId,id,requestKey,null,simulateDecline);
                    // 예외를 던지면 이 트랜잭션에서 만든 예약·수량 차감·결제 기록이 함께 취소된다.
                    if(!"SUCCESS".equals(paid.status())) throw conflict("결제가 실패했어요(테스트). 예약은 만들어지지 않았어요. 다시 시도해 주세요.");
                }
                return id;
            }));
        } catch(DuplicateKeyException sameKey) {
            // 동시에 온 같은 요청이다. 이쪽 처리는 롤백됐고, 먼저 커밋된 예약을 돌려준다.
            return tx.execute(s->existingByRequestKey(userId,requestKey)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"예약 요청이 겹쳤어요. 내 예약 목록에서 확인해 주세요.")));
        }
    }

    /** 회원 한 명이 동시에 둘 수 있는 결제 대기 예약 수. 요청 키만 바꿔 신청해 그날 수량을 혼자 다 잡지 못하게 한다. */
    public static final int MAX_PENDING_PER_MEMBER = 5;
    /** 결제 대기 예약은 마지막으로 바꾼 지 이만큼 지나면 취소하고 수량을 되돌린다(expirePending). */
    public static final int PENDING_EXPIRE_MINUTES = 30;

    private long createInTx(Long userId, Long stockId, Long itineraryId, int quantity, int partySize, UUID requestKey) {
        var existing=existingByRequestKey(userId,requestKey);
        if(existing.isPresent()) return existing.get();
        // 같은 회원의 동시 신청이 개수 확인을 함께 통과하지 않게 회원 행을 먼저 잠근다(수량 잠금보다 앞이라 순서가 늘 같다).
        jdbc.query("SELECT user_id FROM dbo.users WITH (UPDLOCK, ROWLOCK) WHERE user_id=?",(rs,n)->rs.getLong(1),userId);
        Integer pending=jdbc.queryForObject("SELECT COUNT(*) FROM dbo.virtual_reservations WHERE user_id=? AND status='PENDING'",Integer.class,userId);
        if(pending!=null && pending>=MAX_PENDING_PER_MEMBER)
            throw conflict("결제 대기 중인 예약이 "+MAX_PENDING_PER_MEMBER+"건이에요. 결제하거나 취소한 뒤 새로 예약해 주세요.");
        var stock=bookableStock(stockId);
        requireItinerary(userId,itineraryId,stock.placeId());
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
        log(id,stockId,UUID.randomUUID(),"CREATE",change,null,status,"예약 신청");
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

    /**
     * PENDING 또는 결제 미사용 CONFIRMED 예약의 날짜(같은 장소의 다른 수량 구간)·수량·인원을 바꾸고 금액을 다시 계산한다.
     * 바뀐 내용을 '이용일 11-18→11-19 · 인원 2→3명 · 금액 100,000→150,000원'처럼 돌려주고 이력 사유에도 남긴다.
     * 날짜·수량·인원이 모두 그대로면 아무것도 저장하지 않고 null을 돌려준다.
     */
    public String change(String loginId, Long reservationId, Long newStockId, int quantity, int partySize) {
        Long userId=member(loginId);
        return guarded("RESERVATION",reservationId,"RESERVATION_CHANGE",()->tx.execute(s->{
            var r=lockOwned(reservationId,userId);
            if("CANCELED".equals(r.status())) throw conflict("취소된 예약은 바꿀 수 없어요.");
            if(!"PENDING".equals(r.status()) && r.paymentRequired())
                throw conflict("결제가 끝난 예약은 바꿀 수 없어요. 예약을 취소(환불)한 뒤 새로 예약해 주세요.");
            var current=stock(r.stockId()).orElseThrow();
            if(!current.usageStart().isAfter(LocalDateTime.now(KST)))
                throw conflict("이용 시작 시각이 지나 바꿀 수 없어요.");
            if(current.stockId().equals(newStockId) && quantity==r.quantity() && partySize==r.partySize()) return null;
            var target=bookableStock(newStockId);
            if(!target.placeId().equals(current.placeId()) || !Objects.equals(target.roomType(),current.roomType()))
                throw bad("같은 장소(숙소는 같은 객실 종류)의 다른 날짜로만 바꿀 수 있어요.");
            validate(target,quantity,partySize);
            BigDecimal amount=target.unitPrice().multiply(BigDecimal.valueOf(quantity));
            String changes=changeText(current,target,r,quantity,partySize,amount);
            var op=UUID.randomUUID();
            if(target.stockId().equals(current.stockId())) {
                int delta=quantity-r.quantity();
                var change=delta>0?take(current.stockId(),delta):delta<0?giveBack(current.stockId(),-delta):unchanged(current.stockId());
                log(r.id(),current.stockId(),op,"CHANGE",change,r.status(),r.status(),changes);
            } else {
                // 여러 재고를 잠글 때는 stock_id 순서를 통일해 교착을 피한다.
                boolean oldFirst=current.stockId()<target.stockId();
                Change restored=null, taken=null;
                if(oldFirst) restored=giveBack(current.stockId(),r.quantity());
                taken=take(target.stockId(),quantity);
                if(!oldFirst) restored=giveBack(current.stockId(),r.quantity());
                log(r.id(),current.stockId(),op,"CHANGE",restored,r.status(),r.status(),changes+" (이전 날짜 수량 복구)");
                log(r.id(),target.stockId(),op,"CHANGE",taken,r.status(),r.status(),changes+" (새 날짜 수량 차감)");
            }
            jdbc.update("""
                UPDATE dbo.virtual_reservations SET stock_id=?, quantity=?, party_size=?, expected_amount=?,
                       booking_snapshot_json=?, updated_at=SYSDATETIME() WHERE reservation_id=?""",
                target.stockId(),quantity,partySize,amount,snapshot(target),r.id());
            return changes;
        }));
    }

    /** 바뀐 항목만 '이용일 11-18→11-19 · 객실 1→2실 · 인원 2→3명 · 금액 100,000→200,000원'처럼 이어 쓴다. */
    private static String changeText(Stock before, Stock after, Row r, int quantity, int partySize, BigDecimal amount) {
        var parts=new ArrayList<String>();
        LocalDate from=before.usageStart().toLocalDate(), to=after.usageStart().toLocalDate();
        if(!from.equals(to)) {
            // 해가 바뀌는 변경만 연도까지 쓴다.
            var format=java.time.format.DateTimeFormatter.ofPattern(from.getYear()==to.getYear()?"MM-dd":"yyyy-MM-dd");
            parts.add("이용일 "+from.format(format)+"→"+to.format(format));
        }
        if(after.room() && quantity!=r.quantity()) parts.add("객실 "+r.quantity()+"→"+quantity+"실");
        if(partySize!=r.partySize()) parts.add("인원 "+r.partySize()+"→"+partySize+"명");
        if(amount.compareTo(r.amount())!=0) parts.add("금액 "+won(r.amount())+"→"+won(amount)+"원");
        return parts.isEmpty()?"예약 구간 변경":String.join(" · ",parts);
    }

    private static String won(BigDecimal amount) { return String.format("%,d",amount.longValue()); }

    // ───────────── 예약 취소·환불(SFR-13-04·07) ─────────────

    /**
     * 예약을 취소하고 점유 수량을 한 번만 복구한다. 성공 결제가 있으면 같은 트랜잭션에서 REFUNDED로 바꾼다.
     * 이미 취소된 예약은 아무것도 바꾸지 않고 ALREADY_CANCELED를 돌려준다(재취소·재환불 차단).
     * 결제 대기 예약은 언제든 취소하지만, 확정된 예약은 이용 시작 시각이 지나면 취소·환불하지 않는다(409).
     */
    public CancelOutcome cancel(String loginId, Long reservationId) {
        Long userId=member(loginId);
        var outcome=guarded("RESERVATION",reservationId,"RESERVATION_CANCEL",()->tx.execute(s->{
            var r=lockOwned(reservationId,userId);
            if("CANCELED".equals(r.status())) return CancelOutcome.ALREADY_CANCELED;
            if("CONFIRMED".equals(r.status()) && usageStarted(r))
                throw conflict(r.paymentRequired()?"이용 시작 시각이 지나 취소·환불할 수 없어요.":"이용 시작 시각이 지나 취소할 수 없어요.");
            return cancelLocked(r,"회원 취소");
        }));
        if(outcome==CancelOutcome.ALREADY_CANCELED)
            recordFailure(userId,"RESERVATION",reservationId,"RESERVATION_CANCEL","CANCELED","ALREADY_CANCELED");
        return outcome;
    }

    /**
     * 결제하지 않고 PENDING_EXPIRE_MINUTES분 넘게 둔 결제 대기 예약을 취소해 잡아 둔 수량을 되돌린다. 실행기가 1분마다 부른다.
     * 한 건씩 따로 잠가 처리하고, 잠근 뒤 아직 결제 대기인지 다시 본다(그 사이 결제·취소됐으면 건너뛴다). 처리한 수를 돌려준다.
     */
    public int expirePending() {
        var ids=jdbc.queryForList("""
            SELECT TOP 50 reservation_id FROM dbo.virtual_reservations
            WHERE status='PENDING' AND updated_at < DATEADD(MINUTE,-?,SYSDATETIME()) ORDER BY reservation_id""",Long.class,PENDING_EXPIRE_MINUTES);
        int expired=0;
        for(Long id:ids) {
            Boolean done=tx.execute(s->{
                var r=jdbc.query("""
                    SELECT reservation_id, user_id, stock_id, itinerary_id, quantity, party_size, expected_amount, status, payment_required
                    FROM dbo.virtual_reservations WITH (UPDLOCK, ROWLOCK)
                    WHERE reservation_id=? AND status='PENDING' AND updated_at < DATEADD(MINUTE,-?,SYSDATETIME())""",
                    this::row,id,PENDING_EXPIRE_MINUTES).stream().findFirst().orElse(null);
                if(r==null) return false;
                cancelLocked(r,"결제 대기 "+PENDING_EXPIRE_MINUTES+"분이 지나 자동 취소");
                return true;
            });
            if(Boolean.TRUE.equals(done)) expired++;
        }
        return expired;
    }

    private CancelOutcome cancelLocked(Row r, String reason) {
        int refunded=jdbc.update("""
            UPDATE dbo.virtual_payments SET status='REFUNDED', refunded_at=SYSDATETIME()
            WHERE reservation_id=? AND status='SUCCESS'""",r.id());
        jdbc.update("""
            UPDATE dbo.virtual_payments SET status='FAILED', failure_code='RESERVATION_CANCELED', processed_at=SYSDATETIME()
            WHERE reservation_id=? AND status='READY'""",r.id());
        int canceled=jdbc.update("""
            UPDATE dbo.virtual_reservations SET status='CANCELED', canceled_at=SYSDATETIME(), updated_at=SYSDATETIME()
            WHERE reservation_id=? AND status IN ('PENDING','CONFIRMED')""",r.id());
        if(canceled!=1) throw new IllegalStateException("Reservation state changed while locked");
        var change=giveBack(r.stockId(),r.quantity());
        log(r.id(),r.stockId(),UUID.randomUUID(),"CANCEL",change,r.status(),"CANCELED",refunded>0?reason+" · 환불":reason);
        return refunded>0?CancelOutcome.REFUNDED:CancelOutcome.CANCELED;
    }

    // ───────────── 가상 결제(SFR-13-05, SFR-16-02) ─────────────

    /**
     * 테스트 결제수단으로 한 번만 결제한다. 같은 멱등키·같은 내용이면 처음 결과를 돌려주고, 같은 키로 다른 예약·금액을 보내면 거절한다.
     * 승인은 결제 SUCCESS와 예약 CONFIRMED를 함께 커밋하고, 거절은 FAILED로 남기며 예약은 PENDING으로 둔다.
     */
    public PaymentResult pay(String loginId, Long reservationId, UUID idempotencyKey, boolean simulateDecline) {
        return pay(loginId,reservationId,idempotencyKey,null,simulateDecline);
    }

    /**
     * expectedAmount: 결제 화면에 보였던 금액. 그 사이 다른 탭에서 예약을 바꿔 금액이 달라졌으면 결제하지 않는다(409).
     * null이면 비교하지 않는다.
     */
    public PaymentResult pay(String loginId, Long reservationId, UUID idempotencyKey, BigDecimal expectedAmount, boolean simulateDecline) {
        Long userId=member(loginId);
        if(idempotencyKey==null) throw bad("결제 요청 정보가 없어요. 예약 화면을 다시 열어 주세요.");
        try {
            return guarded("PAYMENT",reservationId,"PAYMENT_PROCESS",
                ()->tx.execute(s->payInTx(userId,reservationId,idempotencyKey,expectedAmount,simulateDecline)));
        } catch(DuplicateKeyException sameKey) {
            // 같은 멱등키가 동시에 들어왔다. 먼저 커밋된 결제 결과를 다시 읽어 돌려준다.
            return tx.execute(s->replay(userId,reservationId,idempotencyKey)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"결제 요청이 겹쳤어요. 예약 상세에서 결제 상태를 확인해 주세요.")));
        }
    }

    private PaymentResult payInTx(Long userId, Long reservationId, UUID key, BigDecimal expectedAmount, boolean simulateDecline) {
        var replayed=replay(userId,reservationId,key);
        if(replayed.isPresent()) return replayed.get();
        // 예약 행을 먼저 잠가 다른 멱등키의 동시 결제도 한 줄로 세운다.
        var r=lockOwned(reservationId,userId);
        // 잠금을 기다리는 동안 같은 키의 요청이 먼저 커밋됐을 수 있다. 그때는 거절하지 않고 처음 결과를 돌려준다.
        var committed=replay(userId,reservationId,key);
        if(committed.isPresent()) return committed.get();
        if("CANCELED".equals(r.status())) throw conflict("취소된 예약은 결제할 수 없어요.");
        if(!"PENDING".equals(r.status())) throw conflict("이미 결제가 끝났거나 결제가 필요 없는 예약이에요.");
        if(usageStarted(r)) throw conflict("이용 시작 시각이 지나 결제할 수 없어요. 예약을 취소해 주세요.");
        // 화면에 보인 금액과 지금 금액이 다르면(다른 탭에서 인원·날짜를 바꾼 경우) 결제하지 않고 다시 확인하게 한다.
        if(expectedAmount!=null && expectedAmount.compareTo(r.amount())!=0)
            throw conflict("금액이 바뀌었어요. 다시 확인해 주세요. 지금 결제할 금액은 "+won(r.amount())+"원이에요.");
        Long paymentId=jdbc.queryForObject("""
            INSERT INTO dbo.virtual_payments(reservation_id, idempotency_key, request_hash, amount, status)
            OUTPUT inserted.payment_id VALUES (?,?,?,?,'READY')""",Long.class,
            r.id(),key.toString(),hash(r.id(),r.amount()),r.amount());
        var outcome=gateway.charge(r.id(),r.amount(),simulateDecline);
        if(outcome.approved()) {
            jdbc.update("UPDATE dbo.virtual_payments SET status='SUCCESS', processed_at=SYSDATETIME() WHERE payment_id=? AND status='READY'",paymentId);
            if(jdbc.update("UPDATE dbo.virtual_reservations SET status='CONFIRMED', updated_at=SYSDATETIME() WHERE reservation_id=? AND status='PENDING'",r.id())!=1)
                throw new IllegalStateException("Reservation state changed while locked");
            return new PaymentResult(paymentId,"SUCCESS",false);
        }
        jdbc.update("UPDATE dbo.virtual_payments SET status='FAILED', failure_code=?, processed_at=SYSDATETIME() WHERE payment_id=? AND status='READY'",
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
        jdbc.update("UPDATE dbo.virtual_reservations SET itinerary_id=NULL, updated_at=SYSDATETIME() WHERE itinerary_id=?",itineraryId);
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
            UPDATE dbo.reservation_stock SET remaining_quantity=remaining_quantity-?, updated_at=SYSDATETIME()
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
            UPDATE dbo.reservation_stock SET remaining_quantity=remaining_quantity+?, updated_at=SYSDATETIME()
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

    /** 예약한 수량 구간의 이용 시작 시각(KST)이 지났는지. */
    private boolean usageStarted(Row r) {
        return stock(r.stockId()).map(s->!s.usageStart().isAfter(LocalDateTime.now(KST))).orElse(false);
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
        // 객실 종류가 있으면 객실당 최대 인원은 그 종류를 따른다. 예전 행(NULL)은 출처표의 값을 쓴다.
        var roomType=RoomType.of(rs.getString("room_type"));
        return new Stock(rs.getLong("stock_id"),rs.getLong("place_id"),rs.getString("place_name"),rs.getString("place_type"),
            rs.getString("address"),time(rs,"usage_start_at"),time(rs,"usage_end_at"),rs.getInt("total_quantity"),
            rs.getInt("remaining_quantity"),rs.getBigDecimal("unit_price"),unit,roomType==null?entry.maxPerUnit():roomType.maxPeople(),
            entry.priceNote(),entry.sourceUrl(),roomType==null?null:roomType.name(),roomType==null?null:roomType.label());
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
        if(s.roomTypeName()!=null) map.put("roomTypeName",s.roomTypeName());
        return mapper.writeValueAsString(map);
    }

    private Summary summary(ResultSet rs) throws SQLException {
        var snap=mapper.readTree(rs.getString("booking_snapshot_json"));
        return new Summary(rs.getLong("reservation_id"),snap.path("placeName").asString(),
            LocalDateTime.parse(snap.path("usageStartAt").asString()),LocalDateTime.parse(snap.path("usageEndAt").asString()),
            rs.getInt("quantity"),rs.getInt("party_size"),snap.path("quantityUnit").asString(),rs.getBigDecimal("expected_amount"),
            rs.getString("status"),rs.getString("pay"),time(rs,"created_at"),
            snap.hasNonNull("roomTypeName")?snap.get("roomTypeName").asString():null,
            snap.hasNonNull("unitPrice")?new BigDecimal(snap.get("unitPrice").asString()):null);
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
