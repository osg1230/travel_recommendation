-- 012 적용 후 실행한다. DB 정의서 15~18절(가상 예약 가능 수량·가상 예약·가상 결제·예약 수량 변경 이력)을 만든다.
-- R3 선택 확장(SFR-13·16). 실제 시설에 예약이 전달되지 않고 실제 금융정보·금전 거래는 없다.
-- 기존 장소·회원 데이터는 변경하지 않는다.
SET XACT_ABORT ON;
-- 필터 UNIQUE 인덱스(UF1)는 QUOTED_IDENTIFIER ON이 필요하다. sqlcmd 기본값은 OFF다.
SET QUOTED_IDENTIFIER ON;
BEGIN TRANSACTION;
IF OBJECT_ID('dbo.reservation_stock','U') IS NOT NULL OR OBJECT_ID('dbo.virtual_reservations','U') IS NOT NULL
   OR OBJECT_ID('dbo.virtual_payments','U') IS NOT NULL OR OBJECT_ID('dbo.reservation_logs','U') IS NOT NULL
    THROW 50000, 'Virtual reservation tables already exist. Review migration before proceeding.', 1;

-- 숙소는 1박 패키지 구간(객실), 유료 관광시설은 하루 이용 구간(인원)별로 수량을 둔다.
CREATE TABLE dbo.reservation_stock (
 stock_id BIGINT IDENTITY PRIMARY KEY,
 place_id BIGINT NOT NULL REFERENCES dbo.places(place_id),
 usage_start_at DATETIME2(3) NOT NULL,
 usage_end_at DATETIME2(3) NOT NULL,
 total_quantity INT NOT NULL CONSTRAINT CK_reservation_stock_total CHECK (total_quantity >= 0),
 remaining_quantity INT NOT NULL,
 unit_price DECIMAL(12,2) NOT NULL CONSTRAINT CK_reservation_stock_price CHECK (unit_price >= 0),
 quantity_unit VARCHAR(10) NOT NULL CONSTRAINT CK_reservation_stock_unit CHECK (quantity_unit IN ('ROOM','PERSON')),
 is_active BIT NOT NULL DEFAULT 1,
 created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(),
 updated_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(),
 row_version ROWVERSION NOT NULL,
 CONSTRAINT UQ_reservation_stock_slot UNIQUE(place_id, usage_start_at, usage_end_at),
 CONSTRAINT CK_reservation_stock_remaining CHECK (remaining_quantity >= 0 AND remaining_quantity <= total_quantity),
 CONSTRAINT CK_reservation_stock_period CHECK (usage_end_at > usage_start_at)
);

-- 예약번호는 reservation_id. 금액은 quantity × unit_price로 서버가 계산한다.
CREATE TABLE dbo.virtual_reservations (
 reservation_id BIGINT IDENTITY PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES dbo.users(user_id),
 stock_id BIGINT NOT NULL REFERENCES dbo.reservation_stock(stock_id),
 itinerary_id BIGINT NULL REFERENCES dbo.itineraries(itinerary_id),
 request_key UNIQUEIDENTIFIER NOT NULL CONSTRAINT UQ_virtual_reservations_request UNIQUE,
 quantity INT NOT NULL CONSTRAINT CK_virtual_reservations_quantity CHECK (quantity > 0),
 party_size INT NOT NULL CONSTRAINT CK_virtual_reservations_party CHECK (party_size > 0),
 expected_amount DECIMAL(12,2) NOT NULL CONSTRAINT CK_virtual_reservations_amount CHECK (expected_amount >= 0),
 status VARCHAR(12) NOT NULL DEFAULT 'PENDING'
    CONSTRAINT CK_virtual_reservations_status CHECK (status IN ('PENDING','CONFIRMED','CANCELED')),
 payment_required BIT NOT NULL DEFAULT 1,
 booking_snapshot_json NVARCHAR(MAX) NOT NULL CONSTRAINT CK_virtual_reservations_snapshot CHECK (ISJSON(booking_snapshot_json) = 1),
 created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(),
 updated_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(),
 canceled_at DATETIME2(3) NULL,
 row_version ROWVERSION NOT NULL,
 CONSTRAINT CK_virtual_reservations_canceled CHECK ((status = 'CANCELED' AND canceled_at IS NOT NULL) OR (status <> 'CANCELED' AND canceled_at IS NULL))
);
CREATE INDEX IX_virtual_reservations_user ON dbo.virtual_reservations(user_id, created_at DESC);
CREATE INDEX IX_virtual_reservations_stock ON dbo.virtual_reservations(stock_id);
CREATE INDEX IX_virtual_reservations_itinerary ON dbo.virtual_reservations(itinerary_id);

-- 결제수단은 TEST 고정(서버 상수)이라 수단 컬럼을 두지 않는다. 실제 금융정보 컬럼은 없다.
CREATE TABLE dbo.virtual_payments (
 payment_id BIGINT IDENTITY PRIMARY KEY,
 reservation_id BIGINT NOT NULL REFERENCES dbo.virtual_reservations(reservation_id),
 idempotency_key UNIQUEIDENTIFIER NOT NULL CONSTRAINT UQ_virtual_payments_idempotency UNIQUE,
 request_hash BINARY(32) NOT NULL,
 amount DECIMAL(12,2) NOT NULL CONSTRAINT CK_virtual_payments_amount CHECK (amount >= 0),
 status VARCHAR(12) NOT NULL DEFAULT 'READY'
    CONSTRAINT CK_virtual_payments_status CHECK (status IN ('READY','SUCCESS','FAILED','REFUNDED')),
 failure_code VARCHAR(60) NULL,
 created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(),
 processed_at DATETIME2(3) NULL,
 refunded_at DATETIME2(3) NULL,
 CONSTRAINT CK_virtual_payments_processed CHECK ((status = 'READY' AND processed_at IS NULL) OR (status <> 'READY' AND processed_at IS NOT NULL)),
 CONSTRAINT CK_virtual_payments_refunded CHECK ((status = 'REFUNDED' AND refunded_at IS NOT NULL) OR (status <> 'REFUNDED' AND refunded_at IS NULL)),
 CONSTRAINT CK_virtual_payments_failure CHECK (status <> 'FAILED' OR failure_code IS NOT NULL)
);
-- 다른 멱등키로 동시에 요청해도 성공 결제는 예약당 한 건만 허용한다(UF1).
CREATE UNIQUE INDEX UF_virtual_payments_success ON dbo.virtual_payments(reservation_id) WHERE status = 'SUCCESS';
CREATE INDEX IX_virtual_payments_reservation ON dbo.virtual_payments(reservation_id, created_at);

-- 성공한 수량 변경과 같은 트랜잭션에서 기록한다. 날짜 변경은 같은 operation_id의 두 행(복구·차감)이다.
CREATE TABLE dbo.reservation_logs (
 reservation_log_id BIGINT IDENTITY PRIMARY KEY,
 reservation_id BIGINT NOT NULL REFERENCES dbo.virtual_reservations(reservation_id),
 stock_id BIGINT NOT NULL REFERENCES dbo.reservation_stock(stock_id),
 operation_id UNIQUEIDENTIFIER NOT NULL,
 event_type VARCHAR(20) NOT NULL CONSTRAINT CK_reservation_logs_event CHECK (event_type IN ('CREATE','CHANGE','CANCEL')),
 before_quantity INT NOT NULL CONSTRAINT CK_reservation_logs_before CHECK (before_quantity >= 0),
 delta_quantity INT NOT NULL,
 after_quantity INT NOT NULL CONSTRAINT CK_reservation_logs_after CHECK (after_quantity >= 0),
 before_status VARCHAR(12) NULL,
 after_status VARCHAR(12) NOT NULL CONSTRAINT CK_reservation_logs_after_status CHECK (after_status IN ('PENDING','CONFIRMED','CANCELED')),
 reason NVARCHAR(500) NOT NULL,
 created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(),
 CONSTRAINT CK_reservation_logs_math CHECK (after_quantity = before_quantity + delta_quantity),
 CONSTRAINT CK_reservation_logs_before_status CHECK (before_status IS NULL OR before_status IN ('PENDING','CONFIRMED','CANCELED'))
);
CREATE INDEX IX_reservation_logs_reservation ON dbo.reservation_logs(reservation_id, created_at);
CREATE INDEX IX_reservation_logs_stock ON dbo.reservation_logs(stock_id);

-- 시스템 시드 수량(2026-10-01~2026-12-31 매일). 이용 시각은 KST 현지 시각이다.
-- 숙소 단위 가격: 여기어때 2026-11-18(수) 1박·2인 룸온리 판매가(2026-09-22 확인) 참고가. 실제 요금은 날짜마다 다르다.
--   씨마크 호텔 디럭스 킹 마운틴뷰 319,000 · 신라스테이 해운대 스탠다드 더블 시티뷰 352,000 · 라한셀렉트 경주 디럭스 트윈(산 전망) 200,000
-- 관광시설 단위 가격: 성인 개인 관람료. 오죽헌·시립박물관 3,000(강릉시 관람안내), 동궁과 월지 3,000(경주문화관광 입장료 안내).
;WITH days AS (
    SELECT CAST('2026-10-01' AS DATE) AS d
    UNION ALL SELECT DATEADD(DAY,1,d) FROM days WHERE d < '2026-12-31'
), seed AS (
    SELECT * FROM (VALUES
     (N'R1_GN_SEAMARQ',     'ROOM',   5,   319000.00, 15, 0, 1, 12),
     (N'R1_BS_SHILLA_STAY', 'ROOM',   5,   352000.00, 15, 0, 1, 12),
     (N'R1_GJ_LAHAN',       'ROOM',   5,   200000.00, 15, 0, 1, 11),
     (N'R1_GN_OJUKHEON',    'PERSON', 200, 3000.00,   9,  0, 0, 18),
     (N'R1_GJ_DONGGUNG',    'PERSON', 300, 3000.00,   9,  0, 0, 22)
    ) s(code, unit, qty, price, start_hour, start_offset, end_offset, end_hour)
)
INSERT INTO dbo.reservation_stock(place_id, usage_start_at, usage_end_at, total_quantity, remaining_quantity, unit_price, quantity_unit)
SELECT p.place_id,
       DATEADD(HOUR, s.start_hour, CAST(DATEADD(DAY, s.start_offset, days.d) AS DATETIME2(3))),
       DATEADD(HOUR, s.end_hour, CAST(DATEADD(DAY, s.end_offset, days.d) AS DATETIME2(3))),
       s.qty, s.qty, s.price, s.unit
FROM seed s
JOIN dbo.places p ON p.source_provider = 'SYSTEM' AND p.source_place_id = s.code AND p.is_active = 1
CROSS JOIN days
OPTION (MAXRECURSION 400);
COMMIT;
GO
