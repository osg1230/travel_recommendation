-- 017 적용 후 실행한다. 장소 평가(place_ratings)에 선택 입력 한줄평 comment를 더한다(2026-10-28, SFR-10 보완).
-- 화면에서는 100자까지 받고 앞뒤 공백을 지운 뒤 저장하며, 비우면 NULL이다. 다른 테이블·컬럼·기존 평가 행은 바꾸지 않는다.
-- 이미 적용된 DB(comment 컬럼이 있는 DB)에서는 중단한다.
SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF COL_LENGTH('dbo.place_ratings', 'comment') IS NOT NULL
    THROW 50000, 'place_ratings.comment already exists. 018 seems to be applied already.', 1;

ALTER TABLE dbo.place_ratings ADD comment NVARCHAR(200) NULL;

COMMIT TRANSACTION;
PRINT 'place_ratings.comment added';
