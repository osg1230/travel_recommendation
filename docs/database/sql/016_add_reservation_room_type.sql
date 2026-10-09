-- 015 적용 후 실행한다. DB 정의서 15절 reservation_stock에 room_type(체험용 객실 종류)을 추가한다(2026-10-05).
-- 숙소는 모두 예약(체험)할 수 있게 하고, 객실 종류(싱글·스탠다드 더블·스탠다드 트윈·디럭스 더블·패밀리·스위트)별로 수량을 둔다.
-- 객실 수량 행은 회원이 저장 일정이나 예약 화면을 열 때 서버가 그 숙소·날짜 분만 만든다(시드 추가 없음).
-- 기존 수량·예약 행은 지우지 않는다. 기존 숙소 수량(ROOM)은 기준 객실인 STANDARD_DOUBLE로 표시하고, 관광시설(PERSON)은 NULL로 둔다.
-- 새 컬럼을 쓰는 문장은 같은 배치에서 컴파일되지 않으므로 EXEC로 실행한다.
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF COL_LENGTH('dbo.reservation_stock', 'room_type') IS NOT NULL
    THROW 50000, 'reservation_stock.room_type already exists. Review migration before proceeding.', 1;

ALTER TABLE dbo.reservation_stock ADD room_type VARCHAR(20) NULL;

EXEC(N'UPDATE dbo.reservation_stock SET room_type = ''STANDARD_DOUBLE'' WHERE quantity_unit = ''ROOM''');

EXEC(N'ALTER TABLE dbo.reservation_stock ADD CONSTRAINT CK_reservation_stock_room_type
    CHECK (room_type IS NULL OR (quantity_unit = ''ROOM''
        AND room_type IN (''SINGLE'',''STANDARD_DOUBLE'',''STANDARD_TWIN'',''DELUXE_DOUBLE'',''FAMILY'',''SUITE'')))');

-- 같은 숙소·같은 날짜에 객실 종류별로 한 행씩 둘 수 있게 유일 조건에 room_type을 더한다.
ALTER TABLE dbo.reservation_stock DROP CONSTRAINT UQ_reservation_stock_slot;
EXEC(N'ALTER TABLE dbo.reservation_stock ADD CONSTRAINT UQ_reservation_stock_slot
    UNIQUE(place_id, usage_start_at, usage_end_at, room_type)');

COMMIT TRANSACTION;
