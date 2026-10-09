-- 016 적용 후 실행한다. 저장 시각 기준을 UTC에서 한국 시각(KST, UTC+9)으로 바꾼다(2026-10-10).
-- 1) 생성·수정 시각의 DEFAULT를 SYSUTCDATETIME()에서 SYSDATETIME()으로 바꾼다(서버 현지 시각 = 한국 시각).
-- 2) 이미 UTC로 저장된 datetime2 값에 9시간을 더한다.
--    이용 시각을 처음부터 한국 시각으로 저장한 itinerary_items.start_at/end_at, reservation_stock.usage_start_at/usage_end_at은 바꾸지 않는다.
--    JSON 안의 시각(checkedAt 등)은 시간대가 붙은 절대 시각(Instant)이라 바꾸지 않는다.
-- 이미 적용된 DB(SYSUTCDATETIME 기본값이 없는 DB)에서는 중단한다. 실행 전 백업을 권장한다.
SET QUOTED_IDENTIFIER ON;  -- 필터 인덱스가 있는 테이블(users·virtual_payments)을 갱신하려면 필요하다
SET XACT_ABORT ON;
SET NOCOUNT ON;
BEGIN TRANSACTION;

IF NOT EXISTS (SELECT 1 FROM sys.default_constraints WHERE definition LIKE '%SYSUTCDATETIME%')
    THROW 50000, 'No SYSUTCDATETIME defaults found. 017 seems to be applied already.', 1;

-- 1) DEFAULT 제약 교체 (이름은 DB마다 달라 동적으로 찾는다)
DECLARE @sql NVARCHAR(MAX) = N'';
SELECT @sql += N'ALTER TABLE dbo.' + QUOTENAME(OBJECT_NAME(dc.parent_object_id)) + N' DROP CONSTRAINT ' + QUOTENAME(dc.name) + N';'
    + N'ALTER TABLE dbo.' + QUOTENAME(OBJECT_NAME(dc.parent_object_id)) + N' ADD CONSTRAINT ' + QUOTENAME(dc.name)
    + N' DEFAULT SYSDATETIME() FOR ' + QUOTENAME(c.name) + N';' + CHAR(10)
FROM sys.default_constraints dc
JOIN sys.columns c ON c.object_id = dc.parent_object_id AND c.column_id = dc.parent_column_id
WHERE dc.definition LIKE '%SYSUTCDATETIME%';
EXEC sp_executesql @sql;

-- 2) 기존 값 +9시간 (NULL은 그대로)
UPDATE dbo.users SET created_at = DATEADD(hour, 9, created_at), updated_at = DATEADD(hour, 9, updated_at), withdrawn_at = DATEADD(hour, 9, withdrawn_at);
UPDATE dbo.keyword_categories SET created_at = DATEADD(hour, 9, created_at), updated_at = DATEADD(hour, 9, updated_at);
UPDATE dbo.keywords SET created_at = DATEADD(hour, 9, created_at), updated_at = DATEADD(hour, 9, updated_at);
UPDATE dbo.recommendation_requests SET created_at = DATEADD(hour, 9, created_at), updated_at = DATEADD(hour, 9, updated_at);
UPDATE dbo.recommendation_candidates SET created_at = DATEADD(hour, 9, created_at);
UPDATE dbo.ai_jobs SET created_at = DATEADD(hour, 9, created_at), started_at = DATEADD(hour, 9, started_at),
    completed_at = DATEADD(hour, 9, completed_at), expires_at = DATEADD(hour, 9, expires_at);
UPDATE dbo.places SET created_at = DATEADD(hour, 9, created_at), updated_at = DATEADD(hour, 9, updated_at), verified_at = DATEADD(hour, 9, verified_at);
UPDATE dbo.itineraries SET created_at = DATEADD(hour, 9, created_at), updated_at = DATEADD(hour, 9, updated_at);
UPDATE dbo.itinerary_items SET created_at = DATEADD(hour, 9, created_at), updated_at = DATEADD(hour, 9, updated_at);  -- start_at/end_at 제외
UPDATE dbo.favorites SET created_at = DATEADD(hour, 9, created_at);
UPDATE dbo.place_ratings SET created_at = DATEADD(hour, 9, created_at), updated_at = DATEADD(hour, 9, updated_at);
UPDATE dbo.rating_tags SET created_at = DATEADD(hour, 9, created_at), updated_at = DATEADD(hour, 9, updated_at);
UPDATE dbo.operation_logs SET created_at = DATEADD(hour, 9, created_at);
UPDATE dbo.reservation_stock SET created_at = DATEADD(hour, 9, created_at), updated_at = DATEADD(hour, 9, updated_at);  -- usage_* 제외
UPDATE dbo.virtual_reservations SET created_at = DATEADD(hour, 9, created_at), updated_at = DATEADD(hour, 9, updated_at), canceled_at = DATEADD(hour, 9, canceled_at);
UPDATE dbo.virtual_payments SET created_at = DATEADD(hour, 9, created_at), processed_at = DATEADD(hour, 9, processed_at), refunded_at = DATEADD(hour, 9, refunded_at);
UPDATE dbo.reservation_logs SET created_at = DATEADD(hour, 9, created_at);

COMMIT TRANSACTION;
PRINT 'timestamps converted to KST';
