-- 010 적용 후 실행한다. DB 정의서 11~13절(장소 별점 평가·평가 태그 사전·평가 태그 연결)을 만든다.
-- 기존 장소·회원 데이터는 변경하지 않는다.
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF OBJECT_ID('dbo.place_ratings','U') IS NOT NULL OR OBJECT_ID('dbo.rating_tags','U') IS NOT NULL
    THROW 50000, 'Place rating tables already exist. Review migration before proceeding.', 1;

-- 회원당 장소별 한 건. 기존 평가가 있으면 새 행 대신 갱신한다. 제목·본문은 두지 않는다.
CREATE TABLE dbo.place_ratings (
 rating_id BIGINT IDENTITY PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES dbo.users(user_id),
 place_id BIGINT NOT NULL REFERENCES dbo.places(place_id),
 score TINYINT NOT NULL CONSTRAINT CK_place_ratings_score CHECK (score BETWEEN 1 AND 5),
 created_at DATETIME2(3) NOT NULL DEFAULT SYSDATETIME(),
 updated_at DATETIME2(3) NOT NULL DEFAULT SYSDATETIME(),
 CONSTRAINT UQ_place_ratings_user_place UNIQUE(user_id, place_id)
);
CREATE INDEX IX_place_ratings_place ON dbo.place_ratings(place_id);

-- 사전 정의 태그. 관리자 태그 관리 기능은 두지 않고 이 스크립트로만 관리한다.
CREATE TABLE dbo.rating_tags (
 tag_id BIGINT IDENTITY PRIMARY KEY,
 tag_code VARCHAR(40) NOT NULL CONSTRAINT UQ_rating_tags_code UNIQUE,
 tag_name NVARCHAR(80) NOT NULL CONSTRAINT UQ_rating_tags_name UNIQUE,
 sort_order INT NOT NULL DEFAULT 0 CONSTRAINT CK_rating_tags_sort CHECK (sort_order >= 0),
 is_active BIT NOT NULL DEFAULT 1,
 created_at DATETIME2(3) NOT NULL DEFAULT SYSDATETIME(),
 updated_at DATETIME2(3) NOT NULL DEFAULT SYSDATETIME()
);

-- 평가별 최대 3개. 슬롯 CHECK와 (rating_id, slot_no) UNIQUE로 3행 제한, 복합 PK로 중복 태그를 막는다.
CREATE TABLE dbo.place_rating_tags (
 rating_id BIGINT NOT NULL REFERENCES dbo.place_ratings(rating_id),
 tag_id BIGINT NOT NULL REFERENCES dbo.rating_tags(tag_id),
 slot_no TINYINT NOT NULL CONSTRAINT CK_place_rating_tags_slot CHECK (slot_no BETWEEN 1 AND 3),
 CONSTRAINT PK_place_rating_tags PRIMARY KEY(rating_id, tag_id),
 CONSTRAINT UQ_place_rating_tags_slot UNIQUE(rating_id, slot_no)
);
CREATE INDEX IX_place_rating_tags_tag ON dbo.place_rating_tags(tag_id);

INSERT INTO dbo.rating_tags(tag_code, tag_name, sort_order) VALUES
 ('VIEW', N'경치가 좋아요', 10),
 ('PHOTO', N'사진 찍기 좋아요', 20),
 ('HEALING', N'쉬어 가기 좋아요', 30),
 ('TASTY', N'음식이 맛있어요', 40),
 ('CLEAN', N'시설이 깨끗해요', 50),
 ('PRICE', N'가격이 적당해요', 60),
 ('ACCESS', N'찾아가기 편해요', 70),
 ('PARKING', N'주차가 편해요', 80),
 ('KIDS', N'아이와 가기 좋아요', 90),
 ('REVISIT', N'다시 가고 싶어요', 100);
COMMIT;
GO
-- 관광지·음식점·숙소만 평가한다. 여행지(지역)와 비활성 장소는 평가할 수 없다.
CREATE TRIGGER dbo.TR_place_ratings_place_type ON dbo.place_ratings
AFTER INSERT, UPDATE
AS
BEGIN
    SET NOCOUNT ON;
    IF EXISTS (SELECT 1 FROM inserted i JOIN dbo.places p ON p.place_id=i.place_id
               WHERE p.place_type NOT IN ('ATTRACTION','RESTAURANT','ACCOMMODATION') OR p.is_active=0)
    BEGIN
        THROW 50020, 'Ratings allow only active ATTRACTION, RESTAURANT or ACCOMMODATION places.', 1;
    END
END;
GO
