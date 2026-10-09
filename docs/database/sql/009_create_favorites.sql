-- 008 적용 후 실행한다. 기존 장소·회원 데이터는 변경하지 않는다.
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF OBJECT_ID('dbo.favorites','U') IS NOT NULL
    THROW 50000, 'Favorites already exist. Review migration before proceeding.', 1;
CREATE TABLE dbo.favorites (
 favorite_id BIGINT IDENTITY PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES dbo.users(user_id),
 place_id BIGINT NOT NULL REFERENCES dbo.places(place_id),
 created_at DATETIME2(3) NOT NULL DEFAULT SYSDATETIME(),
 CONSTRAINT UQ_favorites_user_place UNIQUE(user_id, place_id)
);
CREATE INDEX IX_favorites_place ON dbo.favorites(place_id);
COMMIT;
GO
-- 여행지·관광지·숙소만 등록한다. 음식점과 비활성 장소는 등록할 수 없다.
CREATE TRIGGER dbo.TR_favorites_place_type ON dbo.favorites
AFTER INSERT, UPDATE
AS
BEGIN
    SET NOCOUNT ON;
    IF EXISTS (SELECT 1 FROM inserted i JOIN dbo.places p ON p.place_id=i.place_id
               WHERE p.place_type NOT IN ('DESTINATION','ATTRACTION','ACCOMMODATION') OR p.is_active=0)
    BEGIN
        THROW 50010, 'Favorites allow only active DESTINATION, ATTRACTION or ACCOMMODATION places.', 1;
    END
END;
GO
-- 등록된 장소의 유형을 허용되지 않는 값으로 바꾸면 즐겨찾기가 규칙을 벗어나므로 막는다.
CREATE TRIGGER dbo.TR_places_favorite_type ON dbo.places
AFTER UPDATE
AS
BEGIN
    SET NOCOUNT ON;
    IF UPDATE(place_type) AND EXISTS (SELECT 1 FROM inserted i JOIN dbo.favorites f ON f.place_id=i.place_id
               WHERE i.place_type NOT IN ('DESTINATION','ATTRACTION','ACCOMMODATION'))
    BEGIN
        THROW 50011, 'Place type change conflicts with existing favorites.', 1;
    END
END;
GO
