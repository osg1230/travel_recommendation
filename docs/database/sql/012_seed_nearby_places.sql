-- 2026-09-21 주변 지역 추천(SFR-07)용 여행지 3곳과 관광지 6곳.
-- 여행지는 지자체 공식 관광 사이트, 관광지는 한국관광공사 TourAPI(detailCommon2)에서 이름·주소·좌표를 확인했다.
-- 이 여행지는 첫 추천 후보(강릉·부산·경주)에 넣지 않고 주변 지역 추천에서만 쓴다(DestinationCandidateService의 지역 목록에 없음).
-- 가격·영업시간·예약 가능 여부는 저장하지 않는다. 기존 행을 바꾸지 않으며 재실행해도 중복 삽입하지 않는다.
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF OBJECT_ID('dbo.places', 'U') IS NULL
    THROW 50000, 'Apply 004 and 005 before this seed.', 1;

INSERT INTO dbo.places(place_type,source_provider,source_place_id,place_name,region,address,subtype,description,latitude,longitude,source_url,verified_at)
SELECT s.type,'SYSTEM',s.code,s.name,s.region,s.address,s.subtype,s.description,s.lat,s.lon,s.url,'2026-09-21T11:30:00'
FROM (VALUES
 ('DESTINATION',N'R2_SOKCHO',N'속초',N'강원특별자치도 속초시',NULL,NULL,N'설악산과 동해 바다, 항구 풍경을 함께 둘러볼 수 있는 지역입니다.',NULL,NULL,N'https://www.sokcho.go.kr/ct/tour'),
 ('DESTINATION',N'R2_YANGYANG',N'양양',N'강원특별자치도 양양군',NULL,NULL,N'해변과 사찰, 바닷가 절경을 둘러볼 수 있는 지역입니다.',NULL,NULL,N'https://tour.yangyang.go.kr/'),
 ('DESTINATION',N'R2_ULSAN',N'울산',N'울산광역시',NULL,NULL,N'바닷가 공원과 도심 속 생태 정원을 둘러볼 수 있는 지역입니다.',NULL,NULL,N'https://tour.ulsan.go.kr/'),
 ('ATTRACTION',N'R2_SC_YEONGGEUMJEONG',N'영금정',N'강원특별자치도 속초시',N'강원특별자치도 속초시 영금정로 43',N'바닷가·정자',N'속초등대 아래 바닷가의 너른 바위와 정자에서 파도와 바다 풍경을 볼 수 있습니다.',38.211901,128.601462,N'https://www.sokcho.go.kr/ct/tour'),
 ('ATTRACTION',N'R2_SC_BEACH',N'속초해수욕장',N'강원특별자치도 속초시',N'강원특별자치도 속초시 해오름로 186',N'해변',N'속초 시내와 가까운 해수욕장으로 모래사장과 바다 풍경을 즐길 수 있습니다.',38.190549,128.601933,N'https://www.sokcho.go.kr/ct/tour'),
 ('ATTRACTION',N'R2_YY_NAKSANSA',N'낙산사',N'강원특별자치도 양양군',N'강원특별자치도 양양군 강현면 낙산사로 100',N'사찰·문화유산',N'동해가 내려다보이는 오래된 사찰로 경내와 바닷가 풍경을 함께 둘러볼 수 있습니다.',38.124799,128.627327,N'https://tour.yangyang.go.kr/'),
 ('ATTRACTION',N'R2_YY_HAJODAE',N'하조대',N'강원특별자치도 양양군',N'강원특별자치도 양양군 현북면 하광정리',N'해안 절경·명승',N'기암괴석과 소나무 숲이 어우러진 해안 명승으로 데크길을 따라 정자까지 걸을 수 있습니다.',38.021100,128.735700,N'https://tour.yangyang.go.kr/'),
 ('ATTRACTION',N'R2_US_DAEWANGAM',N'대왕암공원',N'울산광역시',N'울산광역시 동구 등대로 95',N'공원·해안',N'송림 길을 지나 바닷가 바위와 등대를 볼 수 있는 해안 공원입니다.',35.490459,129.435275,N'https://daewangam.donggu.ulsan.kr/'),
 ('ATTRACTION',N'R2_US_TAEHWAGANG',N'태화강 국가정원',N'울산광역시',N'울산광역시 중구 샛강남길 5',N'정원·생태',N'태화강을 따라 대나무 숲과 계절 정원을 걸어서 둘러볼 수 있는 생태 정원입니다.',35.547800,129.296200,N'https://www.ulsan.go.kr/s/garden')
) s(type,code,name,region,address,subtype,description,lat,lon,url)
WHERE NOT EXISTS (SELECT 1 FROM dbo.places p WITH (UPDLOCK,HOLDLOCK)
    WHERE p.source_provider='SYSTEM' AND p.source_place_id=s.code);
COMMIT;
