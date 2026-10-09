-- DB 정의서 2·3절. 기존 테이블이 있으면 중단하며 자동 재생성하지 않는다.
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF OBJECT_ID(N'dbo.keyword_categories', N'U') IS NOT NULL
    OR OBJECT_ID(N'dbo.keywords', N'U') IS NOT NULL
BEGIN
    ROLLBACK;
    THROW 50001, N'키워드 테이블이 이미 있습니다. 기존 설계를 확인하세요.', 1;
END;
CREATE TABLE dbo.keyword_categories (
    category_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_keyword_categories PRIMARY KEY,
    category_code VARCHAR(40) NOT NULL CONSTRAINT UQ_keyword_categories_code UNIQUE,
    category_name NVARCHAR(50) NOT NULL CONSTRAINT UQ_keyword_categories_name UNIQUE,
    sort_order INT NOT NULL CONSTRAINT DF_keyword_categories_sort DEFAULT 0,
    is_active BIT NOT NULL CONSTRAINT DF_keyword_categories_active DEFAULT 1,
    created_at DATETIME2(3) NOT NULL CONSTRAINT DF_keyword_categories_created DEFAULT SYSDATETIME(),
    updated_at DATETIME2(3) NOT NULL CONSTRAINT DF_keyword_categories_updated DEFAULT SYSDATETIME(),
    CONSTRAINT CK_keyword_categories_sort CHECK (sort_order >= 0)
);
CREATE TABLE dbo.keywords (
    keyword_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_keywords PRIMARY KEY,
    category_id BIGINT NOT NULL CONSTRAINT FK_keywords_category REFERENCES dbo.keyword_categories(category_id),
    keyword_code VARCHAR(50) NOT NULL,
    keyword_name NVARCHAR(80) NOT NULL,
    value_json NVARCHAR(MAX) NOT NULL,
    sort_order INT NOT NULL CONSTRAINT DF_keywords_sort DEFAULT 0,
    is_active BIT NOT NULL CONSTRAINT DF_keywords_active DEFAULT 1,
    created_at DATETIME2(3) NOT NULL CONSTRAINT DF_keywords_created DEFAULT SYSDATETIME(),
    updated_at DATETIME2(3) NOT NULL CONSTRAINT DF_keywords_updated DEFAULT SYSDATETIME(),
    CONSTRAINT UQ_keywords_code UNIQUE (category_id, keyword_code),
    CONSTRAINT UQ_keywords_name UNIQUE (category_id, keyword_name),
    CONSTRAINT CK_keywords_json CHECK (ISJSON(value_json) = 1),
    CONSTRAINT CK_keywords_sort CHECK (sort_order >= 0)
);
COMMIT;
