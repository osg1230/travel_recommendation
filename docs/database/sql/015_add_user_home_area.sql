-- 014 적용 후 실행한다. DB 정의서 1절 users에 home_area(주로 출발하는 곳, 선택)를 추가한다(2026-09-29).
-- 일정 입력의 출발지 기본값으로만 쓰며, 회원이 가입·내 정보에서 적고 고친다. 탈퇴하면 지운다.
-- 컬럼만 추가하고 기존 회원 데이터는 바꾸지 않는다(기존 회원은 NULL).
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF COL_LENGTH('dbo.users', 'home_area') IS NOT NULL
    THROW 50000, 'users.home_area already exists. Review migration before proceeding.', 1;

ALTER TABLE dbo.users ADD home_area NVARCHAR(60) NULL;

COMMIT TRANSACTION;
