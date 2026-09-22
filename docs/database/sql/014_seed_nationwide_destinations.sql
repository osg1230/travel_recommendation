-- 2026-09-22 전국 17개 시·도로 여행지 후보를 넓힌다(여행지 22곳 추가, 기존 6곳 포함 28곳).
-- 출처는 각 지자체·관광공사 공식 관광 사이트이며 2026-09-22에 접속해 제목을 확인했다. 실시간 수집 데이터가 아니다.
-- region은 현재 행정구역 이름이다. 2026-07-01 광주광역시·전라남도가 전남광주통합특별시로 통합되어
-- 여수·순천·담양은 '전남광주통합특별시'로 적는다(카카오 주소도 같은 이름을 쓴다). 옛 광주 도심(5개 구)은 한 지역명으로 묶을 수 없어 넣지 않았다.
-- subtype은 여행 취향(자연·바다·휴식·맛집·문화·역사·체험·액티비티)과 맞춰 후보를 고르기 위한 시스템 분류이며 공식 정보가 아니다.
-- 좌표·가격·이동시간은 저장하지 않는다. 새 행만 넣고, 기존 여행지는 subtype이 비어 있을 때만 분류를 채운다. 재실행해도 중복되지 않는다.
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF OBJECT_ID('dbo.places', 'U') IS NULL
    THROW 50000, 'Apply 004 and 005 before this seed.', 1;

INSERT INTO dbo.places(place_type,source_provider,source_place_id,place_name,region,subtype,description,source_url,verified_at)
SELECT 'DESTINATION','SYSTEM',s.code,s.name,s.region,s.subtype,s.description,s.url,'2026-09-22T12:00:00'
FROM (VALUES
 (N'R3_SEOUL',N'서울',N'서울특별시',N'문화·역사,맛집,체험·액티비티',N'고궁과 전통 골목, 도심 명소와 다양한 먹거리를 함께 둘러볼 수 있는 지역입니다.',N'https://korean.visitseoul.net/'),
 (N'R3_DAEGU',N'대구',N'대구광역시',N'맛집,문화·역사',N'근대 골목과 전통시장, 지역 음식 여행 정보를 살펴볼 수 있는 지역입니다.',N'https://tour.daegu.go.kr/'),
 (N'R3_INCHEON',N'인천',N'인천광역시',N'바다,문화·역사,맛집',N'섬과 바다, 개항장 거리와 차이나타운을 둘러볼 수 있는 지역입니다.',N'https://itour.incheon.go.kr/'),
 (N'R3_DAEJEON',N'대전',N'대전광역시',N'문화·역사,자연,체험·액티비티',N'과학 전시 시설과 수목원, 숲길 여행 정보를 살펴볼 수 있는 지역입니다.',N'https://daejeontour.co.kr/'),
 (N'R3_SEJONG',N'세종',N'세종특별자치시',N'자연,휴식',N'호수공원과 수목원 등 도심 속 자연을 걸어서 둘러볼 수 있는 지역입니다.',N'https://www.sejong.go.kr/tour.do'),
 (N'R3_SUWON',N'수원',N'경기도 수원시',N'문화·역사,맛집',N'수원화성과 행궁 주변 거리, 지역 음식을 함께 즐길 수 있는 지역입니다.',N'https://www.suwon.go.kr/web/visitsuwon/index.do'),
 (N'R3_GAPYEONG',N'가평',N'경기도 가평군',N'자연,체험·액티비티,휴식',N'북한강과 섬, 계곡에서 자연과 물놀이·레저를 즐길 수 있는 지역입니다.',N'https://www.gptour.go.kr/'),
 (N'R3_CHUNCHEON',N'춘천',N'강원특별자치도 춘천시',N'자연,맛집,체험·액티비티',N'호수와 섬, 닭갈비·막국수 같은 지역 음식을 즐길 수 있는 지역입니다.',N'https://www.chuncheon.go.kr/tour/'),
 (N'R3_PYEONGCHANG',N'평창',N'강원특별자치도 평창군',N'자연,체험·액티비티,휴식',N'산과 목장, 계절 축제와 레저 여행 정보를 살펴볼 수 있는 지역입니다.',N'https://tour.pc.go.kr/'),
 (N'R3_DANYANG',N'단양',N'충청북도 단양군',N'자연,체험·액티비티',N'남한강 절경과 동굴, 전망대와 레저 활동을 즐길 수 있는 지역입니다.',N'https://www.danyang.go.kr/tour/'),
 (N'R3_GONGJU',N'공주',N'충청남도 공주시',N'문화·역사',N'백제 역사 유적과 산성, 금강 주변을 둘러볼 수 있는 지역입니다.',N'https://www.gongju.go.kr/tour/'),
 (N'R3_TAEAN',N'태안',N'충청남도 태안군',N'바다,자연,휴식',N'해변과 해안 길, 서해 풍경을 즐길 수 있는 지역입니다.',N'https://www.taean.go.kr/tour.do'),
 (N'R3_JEONJU',N'전주',N'전북특별자치도 전주시',N'문화·역사,맛집',N'한옥마을과 전통 문화, 비빔밥 등 지역 음식을 즐길 수 있는 지역입니다.',N'https://tour.jeonju.go.kr/'),
 (N'R3_GUNSAN',N'군산',N'전북특별자치도 군산시',N'문화·역사,맛집,바다',N'근대 역사 거리와 섬, 서해 바다를 함께 둘러볼 수 있는 지역입니다.',N'https://www.gunsan.go.kr/tour'),
 (N'R3_YEOSU',N'여수',N'전남광주통합특별시 여수시',N'바다,맛집,휴식',N'밤바다와 섬, 해양 관광지와 해산물 음식을 즐길 수 있는 지역입니다.',N'https://www.yeosu.go.kr/tour'),
 (N'R3_SUNCHEON',N'순천',N'전남광주통합특별시 순천시',N'자연,휴식,문화·역사',N'순천만 습지와 정원, 오래된 사찰을 둘러볼 수 있는 지역입니다.',N'https://www.suncheon.go.kr/tour/'),
 (N'R3_DAMYANG',N'담양',N'전남광주통합특별시 담양군',N'자연,휴식',N'대나무 숲과 가로수길, 정자와 정원을 걸으며 쉴 수 있는 지역입니다.',N'https://www.damyang.go.kr/tour/index.damyang'),
 (N'R3_ANDONG',N'안동',N'경상북도 안동시',N'문화·역사,맛집',N'전통 마을과 서원, 지역 음식을 함께 살펴볼 수 있는 지역입니다.',N'https://www.andong.go.kr/portal/contents.do?mId=0612040000'),
 (N'R3_POHANG',N'포항',N'경상북도 포항시',N'바다,맛집',N'동해 해안과 해돋이 명소, 해산물 음식을 즐길 수 있는 지역입니다.',N'https://www.pohang.go.kr/phtour/'),
 (N'R3_TONGYEONG',N'통영',N'경상남도 통영시',N'바다,맛집,체험·액티비티',N'항구와 섬, 케이블카와 해산물 음식을 즐길 수 있는 지역입니다.',N'https://www.utour.go.kr/'),
 (N'R3_GEOJE',N'거제',N'경상남도 거제시',N'바다,자연',N'해안 절경과 섬, 바닷가 산책로를 둘러볼 수 있는 지역입니다.',N'https://tour.geoje.go.kr/'),
 (N'R3_JEJU',N'제주',N'제주특별자치도',N'바다,자연,휴식,체험·액티비티',N'오름과 해변, 올레길 등 섬의 자연과 레저를 즐길 수 있는 지역입니다.',N'https://www.visitjeju.net/kr')
) s(code,name,region,subtype,description,url)
WHERE NOT EXISTS (SELECT 1 FROM dbo.places p WITH (UPDLOCK,HOLDLOCK)
    WHERE p.source_provider='SYSTEM' AND p.source_place_id=s.code);

-- 기존 여행지(005·012)는 분류만 비어 있을 때 채운다. 다른 값은 바꾸지 않는다.
UPDATE p SET subtype=t.subtype
FROM dbo.places p JOIN (VALUES
 (N'R1_GANGNEUNG',N'바다,자연,맛집,휴식'),
 (N'R1_BUSAN',N'바다,맛집,문화·역사,체험·액티비티'),
 (N'R1_GYEONGJU',N'문화·역사,자연'),
 (N'R2_SOKCHO',N'바다,자연,맛집'),
 (N'R2_YANGYANG',N'바다,체험·액티비티,휴식'),
 (N'R2_ULSAN',N'바다,자연')
) t(code,subtype) ON p.source_provider='SYSTEM' AND p.source_place_id=t.code
WHERE p.place_type='DESTINATION' AND p.subtype IS NULL;
COMMIT;
