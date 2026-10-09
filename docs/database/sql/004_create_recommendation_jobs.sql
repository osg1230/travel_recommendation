-- 기존 데이터는 보존한다. 001 적용 후 travel_recommendation DB에서 실행한다.
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF OBJECT_ID('dbo.recommendation_requests', 'U') IS NOT NULL
 OR OBJECT_ID('dbo.ai_jobs', 'U') IS NOT NULL OR OBJECT_ID('dbo.places', 'U') IS NOT NULL
    THROW 50000, 'Target tables already exist. Review schema before proceeding.', 1;

CREATE TABLE dbo.recommendation_requests (
 request_id BIGINT IDENTITY PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES dbo.users(user_id),
 input_mode VARCHAR(10) NOT NULL CHECK(input_mode IN ('TEXT','KEYWORD')),
 original_text NVARCHAR(2000) NULL,
 conditions_json NVARCHAR(MAX) NOT NULL CHECK(ISJSON(conditions_json)=1),
 region NVARCHAR(100) NULL, start_date DATE NULL, end_date DATE NULL,
 duration_days SMALLINT NULL CHECK(duration_days>0),
 budget DECIMAL(12,2) NULL CHECK(budget>=0), accommodation_needed BIT NULL,
 interpretation_summary NVARCHAR(1000) NULL,
 created_at DATETIME2(3) NOT NULL DEFAULT SYSDATETIME(),
 updated_at DATETIME2(3) NOT NULL DEFAULT SYSDATETIME(),
 CHECK((start_date IS NULL AND end_date IS NULL) OR
       (start_date IS NOT NULL AND end_date IS NOT NULL AND end_date>=start_date
        AND duration_days IS NOT NULL AND duration_days=DATEDIFF(day,start_date,end_date)+1)),
 CHECK(input_mode<>'TEXT' OR (original_text IS NOT NULL AND LEN(original_text)>0))
);
CREATE INDEX IX_requests_user ON dbo.recommendation_requests(user_id,created_at DESC);

CREATE TABLE dbo.ai_jobs (
 job_id UNIQUEIDENTIFIER NOT NULL PRIMARY KEY DEFAULT NEWID(),
 user_id BIGINT NULL REFERENCES dbo.users(user_id), guest_token_hash BINARY(32) NULL,
 request_id BIGINT NULL REFERENCES dbo.recommendation_requests(request_id),
 job_type VARCHAR(20) NOT NULL CHECK(job_type IN ('RECOMMENDATION','ITINERARY')),
 status VARCHAR(32) NOT NULL DEFAULT 'QUEUED' CHECK(status IN
 ('QUEUED','ANALYZING_CONDITIONS','SEARCHING_CANDIDATES','CANDIDATES_READY','GENERATING_SCHEDULE','VALIDATING_SCHEDULE','COMPLETED','FAILED')),
 attempt_count TINYINT NOT NULL DEFAULT 0 CHECK(attempt_count BETWEEN 0 AND 3),
 request_json NVARCHAR(MAX) NOT NULL CHECK(ISJSON(request_json)=1),
 result_json NVARCHAR(MAX) NULL CHECK(ISJSON(result_json)=1),
 validation_errors_json NVARCHAR(MAX) NULL CHECK(ISJSON(validation_errors_json)=1),
 error_code VARCHAR(60) NULL, message NVARCHAR(1000) NULL,
 created_at DATETIME2(3) NOT NULL DEFAULT SYSDATETIME(), started_at DATETIME2(3) NULL,
 completed_at DATETIME2(3) NULL, expires_at DATETIME2(3) NOT NULL, row_version ROWVERSION NOT NULL,
 CHECK((user_id IS NOT NULL AND guest_token_hash IS NULL) OR (user_id IS NULL AND guest_token_hash IS NOT NULL)),
 CHECK(user_id IS NOT NULL OR request_id IS NULL),
 CHECK((status IN ('COMPLETED','FAILED') AND completed_at IS NOT NULL) OR
       (status NOT IN ('COMPLETED','FAILED') AND completed_at IS NULL)),
 CHECK(status NOT IN ('COMPLETED','CANDIDATES_READY') OR result_json IS NOT NULL),
 CHECK(status<>'FAILED' OR error_code IS NOT NULL)
);
CREATE INDEX IX_jobs_status ON dbo.ai_jobs(status,created_at);
CREATE INDEX IX_jobs_expiry ON dbo.ai_jobs(expires_at);
CREATE INDEX IX_jobs_user ON dbo.ai_jobs(user_id,created_at);
CREATE INDEX IX_jobs_request ON dbo.ai_jobs(request_id);

CREATE TABLE dbo.places (
 place_id BIGINT IDENTITY PRIMARY KEY,
 place_type VARCHAR(20) NOT NULL CHECK(place_type IN ('DESTINATION','ATTRACTION','ACCOMMODATION','RESTAURANT')),
 source_provider VARCHAR(40) NOT NULL, source_place_id NVARCHAR(150) NOT NULL,
 place_name NVARCHAR(200) NOT NULL, region NVARCHAR(100) NOT NULL,
 address NVARCHAR(300) NULL, latitude DECIMAL(9,6) NULL CHECK(latitude BETWEEN -90 AND 90),
 longitude DECIMAL(9,6) NULL CHECK(longitude BETWEEN -180 AND 180),
 subtype NVARCHAR(80) NULL, description NVARCHAR(2000) NULL,
 details_json NVARCHAR(MAX) NULL CHECK(ISJSON(details_json)=1),
 source_url NVARCHAR(1000) NULL, map_url NVARCHAR(1000) NULL,
 is_active BIT NOT NULL DEFAULT 1, verified_at DATETIME2(3) NOT NULL,
 created_at DATETIME2(3) NOT NULL DEFAULT SYSDATETIME(), updated_at DATETIME2(3) NOT NULL DEFAULT SYSDATETIME(),
 UNIQUE(source_provider,source_place_id),
 CHECK((latitude IS NULL AND longitude IS NULL) OR (latitude IS NOT NULL AND longitude IS NOT NULL))
);
COMMIT;
