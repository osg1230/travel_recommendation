-- 2026-11-26 광주를 여행지로 추가한다(014에서 "5개 구를 한 지역명으로 묶을 수 없어" 빼 두었던 곳).
-- 통합 뒤 광주의 주소는 "전남광주통합특별시 동구 …"처럼 시 이름 없이 구로 시작하므로, region은 '전남광주통합특별시 광주'로 적고
-- 5개 구(동구·서구·남구·북구·광산구)는 서버(Provinces)에서 이 지역으로 묶는다. 실제 행정구역 이름이 아니라 시스템 표기다.
-- 출처는 광주광역시 공식 관광 사이트이며 2026-11-26에 접속해 제목("광주관광")을 확인했다. 실시간 수집 데이터가 아니다.
-- subtype은 여행 취향과 맞춰 후보를 고르기 위한 시스템 분류이며 공식 정보가 아니다. 새 행만 넣고 재실행해도 중복되지 않는다.
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF OBJECT_ID('dbo.places', 'U') IS NULL
    THROW 50000, 'Apply 004 and 005 before this seed.', 1;

INSERT INTO dbo.places(place_type,source_provider,source_place_id,place_name,region,subtype,description,source_url,verified_at)
SELECT 'DESTINATION','SYSTEM',N'R4_GWANGJU',N'광주',N'전남광주통합특별시 광주',N'문화·역사,맛집,자연',
    N'무등산과 근대 골목, 문화 전시 공간과 지역 음식을 함께 즐길 수 있는 지역입니다.',N'https://tour.gwangju.go.kr/','2026-11-26T12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM dbo.places p WITH (UPDLOCK,HOLDLOCK)
    WHERE p.source_provider='SYSTEM' AND p.source_place_id=N'R4_GWANGJU');
COMMIT;
