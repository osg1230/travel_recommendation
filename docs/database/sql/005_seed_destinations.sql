-- 공식 관광 사이트에서 2026-09-11 확인한 R1 지역. 실시간 수집 데이터가 아니다.
-- 좌표·가격·이동시간은 확인하지 않아 저장하지 않는다. 재실행해도 기존 행을 덮어쓰지 않는다.
SET XACT_ABORT ON;
BEGIN TRANSACTION;
INSERT INTO dbo.places(place_type,source_provider,source_place_id,place_name,region,description,source_url,verified_at)
SELECT 'DESTINATION','SYSTEM',s.code,s.name,s.region,s.description,s.url,'2026-09-11T12:06:27'
FROM (VALUES
 (N'R1_GANGNEUNG',N'강릉',N'강원특별자치도 강릉시',N'바다와 커피, 자연·문화 여행 정보를 살펴볼 수 있는 지역입니다.',N'https://visitgangneung.net/'),
 (N'R1_BUSAN',N'부산',N'부산광역시',N'해변과 도심 명소, 음식·문화 여행 정보를 살펴볼 수 있는 지역입니다.',N'https://www.visitbusan.net/kr/index.do'),
 (N'R1_GYEONGJU',N'경주',N'경상북도 경주시',N'역사 유산과 도보·자전거 여행 정보를 살펴볼 수 있는 지역입니다.',N'https://gyeongju.go.kr/tour/index.do')
) s(code,name,region,description,url)
WHERE NOT EXISTS (SELECT 1 FROM dbo.places p WITH (UPDLOCK,HOLDLOCK) WHERE p.source_provider='SYSTEM' AND p.source_place_id=s.code);
COMMIT;
