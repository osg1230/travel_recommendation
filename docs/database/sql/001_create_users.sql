-- SQL Server 2019 / 회원 테이블만 생성한다. 자동 시작 시 실행하지 않는다.
SET XACT_ABORT ON;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET ARITHABORT ON;
SET NUMERIC_ROUNDABORT OFF;

IF DB_NAME() <> N'travel_recommendation'
    THROW 50001, 'Run only in travel_recommendation.', 1;

IF OBJECT_ID(N'dbo.users', N'U') IS NOT NULL
    THROW 50002, 'dbo.users already exists. Inspect the schema before proceeding.', 1;

BEGIN TRANSACTION;
CREATE TABLE dbo.users (
    user_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_users PRIMARY KEY,
    login_id VARCHAR(50) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    nickname NVARCHAR(50) NOT NULL,
    role VARCHAR(20) NOT NULL CONSTRAINT DF_users_role DEFAULT 'MEMBER',
    status VARCHAR(20) NOT NULL CONSTRAINT DF_users_status DEFAULT 'ACTIVE',
    created_at DATETIME2(3) NOT NULL CONSTRAINT DF_users_created_at DEFAULT SYSDATETIME(),
    updated_at DATETIME2(3) NOT NULL CONSTRAINT DF_users_updated_at DEFAULT SYSDATETIME(),
    withdrawn_at DATETIME2(3) NULL,
    CONSTRAINT UQ_users_login_id UNIQUE (login_id),
    CONSTRAINT CK_users_role CHECK (role IN ('MEMBER', 'ADMIN')),
    CONSTRAINT CK_users_status CHECK (status IN ('ACTIVE', 'RESTRICTED', 'WITHDRAWN')),
    CONSTRAINT CK_users_withdrawn_at CHECK (
        (status = 'WITHDRAWN' AND withdrawn_at IS NOT NULL)
        OR (status <> 'WITHDRAWN' AND withdrawn_at IS NULL)
    )
);
-- 탈퇴 계정은 같은 익명 닉네임을 사용할 수 있다. 이용 제한 회원은 중복을 허용하지 않는다.
CREATE UNIQUE INDEX UX_users_nickname_not_withdrawn
    ON dbo.users(nickname) WHERE status <> 'WITHDRAWN';
COMMIT TRANSACTION;
