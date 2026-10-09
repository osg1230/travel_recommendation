-- 004·005 적용 후 실행. 기존 요청·장소 데이터는 보존한다.
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF OBJECT_ID('dbo.recommendation_candidates','U') IS NOT NULL
    THROW 50000, 'Recommendation results already exist. Review migration before proceeding.', 1;
CREATE TABLE dbo.recommendation_candidates (
 candidate_id BIGINT IDENTITY PRIMARY KEY,
 request_id BIGINT NOT NULL REFERENCES dbo.recommendation_requests(request_id),
 place_id BIGINT NOT NULL REFERENCES dbo.places(place_id),
 rank_no TINYINT NOT NULL CHECK(rank_no BETWEEN 1 AND 5),
 recommendation_reason NVARCHAR(2000) NOT NULL,
 place_snapshot_json NVARCHAR(MAX) NOT NULL CHECK(ISJSON(place_snapshot_json)=1),
 created_at DATETIME2(3) NOT NULL DEFAULT SYSDATETIME(),
 UNIQUE(request_id,rank_no), UNIQUE(request_id,place_id)
);
CREATE INDEX IX_candidates_place ON dbo.recommendation_candidates(place_id);
-- 004에서 자동 명명된 status CHECK만 찾아 허용 상태를 확장한다.
DECLARE @statusConstraint SYSNAME;
SELECT @statusConstraint=name FROM sys.check_constraints
WHERE parent_object_id=OBJECT_ID('dbo.ai_jobs') AND definition LIKE '%QUEUED%' AND definition LIKE '%VALIDATING_SCHEDULE%';
IF @statusConstraint IS NULL THROW 50001, 'Expected job status constraint not found.', 1;
DECLARE @alterSql NVARCHAR(500)='ALTER TABLE dbo.ai_jobs DROP CONSTRAINT '+QUOTENAME(@statusConstraint);
EXEC sp_executesql @alterSql;
ALTER TABLE dbo.ai_jobs ADD CONSTRAINT CK_ai_jobs_status CHECK(status IN
 ('QUEUED','ANALYZING_CONDITIONS','SEARCHING_CANDIDATES','CANDIDATES_READY',
  'RECOMMENDATION_QUEUED','RANKING_CANDIDATES','GENERATING_SCHEDULE','VALIDATING_SCHEDULE','COMPLETED','FAILED'));
COMMIT;
