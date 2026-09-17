-- 2026-09-18 공식 관광·업체 출처에서 이름과 주소를 확인한 초기 장소 12곳.
-- 좌표·가격·영업시간·예약 가능 여부는 검증하지 않아 저장하지 않는다.
-- 기존 행을 변경하지 않으며 재실행해도 중복 삽입하지 않는다. 출처와 한계는 docs/ai/지역별_장소_조회.md 참고.
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF OBJECT_ID('dbo.places', 'U') IS NULL
    THROW 50000, 'Apply 004 and 005 before this seed.', 1;
IF (SELECT COUNT(*) FROM dbo.places WHERE source_provider='SYSTEM'
    AND source_place_id IN (N'R1_GANGNEUNG',N'R1_BUSAN',N'R1_GYEONGJU') AND place_type='DESTINATION') <> 3
    THROW 50000, 'Initial destinations are missing. Apply 005 first.', 1;

INSERT INTO dbo.places(place_type,source_provider,source_place_id,place_name,region,address,subtype,description,source_url,verified_at)
SELECT s.type,'SYSTEM',s.code,s.name,s.region,s.address,s.subtype,s.description,s.url,'2026-09-17T15:25:00'
FROM (VALUES
 ('ATTRACTION',N'R1_GN_OJUKHEON',N'오죽헌·시립박물관',N'강원특별자치도 강릉시',N'강원특별자치도 강릉시 율곡로3139번길 24',N'박물관·문화유산',N'오죽헌과 강릉시립박물관을 함께 둘러볼 수 있는 문화유산 관람 장소입니다.',N'https://www.gn.go.kr/museum/contents.do?key=886'),
 ('ATTRACTION',N'R1_GN_GYEONGPODAE',N'경포대',N'강원특별자치도 강릉시',N'강원특별자치도 강릉시 경포로 365',N'누각·문화유산',N'경포호를 바라볼 수 있는 누각으로 역사와 호수 풍경을 살펴볼 수 있습니다.',N'https://english.visitkorea.or.kr/svc/contents/contentsView.do?vcontsId=110841'),
 ('ACCOMMODATION',N'R1_GN_SEAMARQ',N'씨마크 호텔',N'강원특별자치도 강릉시',N'강원특별자치도 강릉시 해안로406번길 2',N'호텔',N'강릉 해안로에 있는 호텔입니다. 객실 요금과 이용 가능 여부는 공식 출처에서 확인해 주세요.',N'https://booking.seamarqhotel.com/seamarq'),
 ('RESTAURANT',N'R1_GN_THE_RESTAURANT',N'씨마크 호텔 더 레스토랑',N'강원특별자치도 강릉시',N'강원특별자치도 강릉시 해안로406번길 2 씨마크 호텔 1층',N'호텔 레스토랑',N'씨마크 호텔 1층에 있는 식사 공간입니다. 메뉴와 식사 시간별 이용 조건을 확인해 주세요.',N'https://www.seamarqhotel.com/dining/the-restaurant'),
 ('ATTRACTION',N'R1_BS_HAEUNDAE',N'해운대해수욕장',N'부산광역시',N'부산광역시 해운대구 해운대해변로 264',N'해변',N'부산 해운대의 해변 풍경을 둘러볼 수 있는 장소입니다.',N'https://www.visitbusan.net/index.do?lang_cd=ko&menuCd=DOM_000000201001001000&uc_seq=373'),
 ('ATTRACTION',N'R1_BS_GAMCHEON',N'감천문화마을',N'부산광역시',N'부산광역시 사하구 감내2로 203',N'마을·문화',N'골목과 마을 풍경을 둘러보는 관광지입니다. 주민 생활공간을 배려하며 방문해 주세요.',N'https://www.visitbusan.net/index.do?lang_cd=ko&menuCd=DOM_000000501001001000&uc_seq=365'),
 ('ACCOMMODATION',N'R1_BS_SHILLA_STAY',N'신라스테이 해운대',N'부산광역시',N'부산광역시 해운대구 해운대로570번길 46',N'호텔',N'해운대 지역에 있는 호텔입니다. 객실 요금과 이용 가능 여부는 공식 출처에서 확인해 주세요.',N'https://www.shillahotels.com/ko/shillastay/haeundae/introduction/about.do'),
 ('RESTAURANT',N'R1_BS_AMSO_GALBI',N'해운대암소갈비집',N'부산광역시',N'부산광역시 해운대구 해운대해변로 333',N'갈비',N'해운대에 있는 갈비 음식점입니다. 메뉴와 방문 가능 시간은 공식 출처에서 확인해 주세요.',N'https://www.haeundaegalbi.com/'),
 ('ATTRACTION',N'R1_GJ_HWANGNIDAN',N'황리단길',N'경상북도 경주시',N'경상북도 경주시 포석로 1080',N'거리·문화',N'황남동 일대의 골목과 상점들을 둘러볼 수 있는 거리입니다.',N'https://www.gyeongju.go.kr/tour/page.do?area_uid=53&cmd=2&code_uid=1012&listType=&mnu_uid=2292&sortKwd=name&srchKwd='),
 ('ATTRACTION',N'R1_GJ_DONGGUNG',N'동궁과 월지',N'경상북도 경주시',N'경상북도 경주시 원화로 102',N'문화유산',N'신라의 궁궐 유적과 연못을 둘러볼 수 있는 관람 장소입니다.',N'https://search.gyeongju.go.kr/tour/page.do?area_uid=50&cmd=2&code_uid=1012&listType=&mnu_uid=2292&sortKwd=&srchKwd='),
 ('ACCOMMODATION',N'R1_GJ_LAHAN',N'라한셀렉트 경주',N'경상북도 경주시',N'경상북도 경주시 보문로 338',N'호텔',N'경주 보문로에 있는 호텔입니다. 객실 요금과 이용 가능 여부는 공식 출처에서 확인해 주세요.',N'https://www.lahanhotels.com/gyeongju/ko/dining.do'),
 ('RESTAURANT',N'R1_GJ_MARKET338',N'마켓338',N'경상북도 경주시',N'경상북도 경주시 보문로 338 라한셀렉트 경주 1층',N'호텔 다이닝',N'라한셀렉트 경주 1층에 있는 식사 공간입니다. 입점 음식점과 메뉴는 공식 출처에서 확인해 주세요.',N'https://lahanhotels.com/gyeongju/ko/dining/market338.do')
) s(type,code,name,region,address,subtype,description,url)
WHERE NOT EXISTS (SELECT 1 FROM dbo.places p WITH (UPDLOCK,HOLDLOCK)
    WHERE p.source_provider='SYSTEM' AND p.source_place_id=s.code);
COMMIT;
