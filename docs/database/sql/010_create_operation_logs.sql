-- 009 적용 후 실행한다. 기존 회원 데이터는 변경하지 않는다.
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF OBJECT_ID('dbo.operation_logs','U') IS NOT NULL
    THROW 50000, 'Operation logs already exist. Review migration before proceeding.', 1;
-- 대상이 삭제돼도 기록은 남겨야 하므로 target_id에는 FK를 걸지 않는다.
CREATE TABLE dbo.operation_logs (
 log_id BIGINT IDENTITY PRIMARY KEY,
 actor_user_id BIGINT NULL REFERENCES dbo.users(user_id),
 actor_kind VARCHAR(10) NOT NULL CHECK(actor_kind IN ('ADMIN','MEMBER','SYSTEM')),
 target_type VARCHAR(20) NOT NULL CHECK(target_type IN ('USER','RESERVATION','PAYMENT')),
 target_id BIGINT NOT NULL,
 action_code VARCHAR(50) NOT NULL,
 before_status VARCHAR(32) NULL,
 after_status VARCHAR(32) NULL,
 reason NVARCHAR(1000) NOT NULL,
 outcome VARCHAR(10) NOT NULL CHECK(outcome IN ('SUCCESS','FAILED')),
 correlation_id UNIQUEIDENTIFIER NULL,
 created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME()
);
CREATE INDEX IX_operation_logs_target ON dbo.operation_logs(target_type, target_id, created_at DESC);
COMMIT;
