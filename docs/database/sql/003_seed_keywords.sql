-- 기존 항목은 수정하지 않고 누락된 코드만 추가한다. 앱 시작 시 자동 실행하지 않는다.
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF NOT EXISTS (SELECT 1 FROM dbo.keyword_categories WHERE category_code = 'REGION')
    INSERT dbo.keyword_categories(category_code, category_name, sort_order) VALUES ('REGION', N'지역', 1);
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'SEOUL', N'서울', N'{"value":"SEOUL"}', 1 FROM dbo.keyword_categories c
WHERE c.category_code = 'REGION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'SEOUL');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'BUSAN', N'부산', N'{"value":"BUSAN"}', 2 FROM dbo.keyword_categories c
WHERE c.category_code = 'REGION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'BUSAN');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'DAEGU', N'대구', N'{"value":"DAEGU"}', 3 FROM dbo.keyword_categories c
WHERE c.category_code = 'REGION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'DAEGU');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'INCHEON', N'인천', N'{"value":"INCHEON"}', 4 FROM dbo.keyword_categories c
WHERE c.category_code = 'REGION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'INCHEON');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'GWANGJU', N'광주', N'{"value":"GWANGJU"}', 5 FROM dbo.keyword_categories c
WHERE c.category_code = 'REGION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'GWANGJU');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'DAEJEON', N'대전', N'{"value":"DAEJEON"}', 6 FROM dbo.keyword_categories c
WHERE c.category_code = 'REGION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'DAEJEON');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'ULSAN', N'울산', N'{"value":"ULSAN"}', 7 FROM dbo.keyword_categories c
WHERE c.category_code = 'REGION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'ULSAN');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'SEJONG', N'세종', N'{"value":"SEJONG"}', 8 FROM dbo.keyword_categories c
WHERE c.category_code = 'REGION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'SEJONG');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'GYEONGGI', N'경기', N'{"value":"GYEONGGI"}', 9 FROM dbo.keyword_categories c
WHERE c.category_code = 'REGION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'GYEONGGI');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'GANGWON', N'강원', N'{"value":"GANGWON"}', 10 FROM dbo.keyword_categories c
WHERE c.category_code = 'REGION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'GANGWON');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'CHUNGBUK', N'충북', N'{"value":"CHUNGBUK"}', 11 FROM dbo.keyword_categories c
WHERE c.category_code = 'REGION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'CHUNGBUK');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'CHUNGNAM', N'충남', N'{"value":"CHUNGNAM"}', 12 FROM dbo.keyword_categories c
WHERE c.category_code = 'REGION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'CHUNGNAM');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'JEONBUK', N'전북', N'{"value":"JEONBUK"}', 13 FROM dbo.keyword_categories c
WHERE c.category_code = 'REGION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'JEONBUK');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'JEONNAM', N'전남', N'{"value":"JEONNAM"}', 14 FROM dbo.keyword_categories c
WHERE c.category_code = 'REGION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'JEONNAM');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'GYEONGBUK', N'경북', N'{"value":"GYEONGBUK"}', 15 FROM dbo.keyword_categories c
WHERE c.category_code = 'REGION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'GYEONGBUK');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'GYEONGNAM', N'경남', N'{"value":"GYEONGNAM"}', 16 FROM dbo.keyword_categories c
WHERE c.category_code = 'REGION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'GYEONGNAM');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'JEJU', N'제주', N'{"value":"JEJU"}', 17 FROM dbo.keyword_categories c
WHERE c.category_code = 'REGION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'JEJU');
IF NOT EXISTS (SELECT 1 FROM dbo.keyword_categories WHERE category_code = 'SEASON')
    INSERT dbo.keyword_categories(category_code, category_name, sort_order) VALUES ('SEASON', N'계절', 2);
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'SPRING', N'봄', N'{"value":"SPRING"}', 1 FROM dbo.keyword_categories c
WHERE c.category_code = 'SEASON' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'SPRING');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'SUMMER', N'여름', N'{"value":"SUMMER"}', 2 FROM dbo.keyword_categories c
WHERE c.category_code = 'SEASON' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'SUMMER');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'AUTUMN', N'가을', N'{"value":"AUTUMN"}', 3 FROM dbo.keyword_categories c
WHERE c.category_code = 'SEASON' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'AUTUMN');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'WINTER', N'겨울', N'{"value":"WINTER"}', 4 FROM dbo.keyword_categories c
WHERE c.category_code = 'SEASON' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'WINTER');
IF NOT EXISTS (SELECT 1 FROM dbo.keyword_categories WHERE category_code = 'COMPANION')
    INSERT dbo.keyword_categories(category_code, category_name, sort_order) VALUES ('COMPANION', N'동행자', 3);
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'SOLO', N'혼자', N'{"value":"SOLO"}', 1 FROM dbo.keyword_categories c
WHERE c.category_code = 'COMPANION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'SOLO');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'FRIENDS', N'친구', N'{"value":"FRIENDS"}', 2 FROM dbo.keyword_categories c
WHERE c.category_code = 'COMPANION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'FRIENDS');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'COUPLE', N'연인', N'{"value":"COUPLE"}', 3 FROM dbo.keyword_categories c
WHERE c.category_code = 'COMPANION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'COUPLE');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'FAMILY', N'가족', N'{"value":"FAMILY"}', 4 FROM dbo.keyword_categories c
WHERE c.category_code = 'COMPANION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'FAMILY');
IF NOT EXISTS (SELECT 1 FROM dbo.keyword_categories WHERE category_code = 'THEME')
    INSERT dbo.keyword_categories(category_code, category_name, sort_order) VALUES ('THEME', N'여행 취향', 4);
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'NATURE', N'자연', N'{"value":"NATURE"}', 1 FROM dbo.keyword_categories c
WHERE c.category_code = 'THEME' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'NATURE');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'SEA', N'바다', N'{"value":"SEA"}', 2 FROM dbo.keyword_categories c
WHERE c.category_code = 'THEME' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'SEA');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'REST', N'휴식', N'{"value":"REST"}', 3 FROM dbo.keyword_categories c
WHERE c.category_code = 'THEME' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'REST');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'FOOD', N'맛집', N'{"value":"FOOD"}', 4 FROM dbo.keyword_categories c
WHERE c.category_code = 'THEME' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'FOOD');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'CULTURE', N'문화·역사', N'{"value":"CULTURE"}', 5 FROM dbo.keyword_categories c
WHERE c.category_code = 'THEME' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'CULTURE');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'ACTIVITY', N'체험·액티비티', N'{"value":"ACTIVITY"}', 6 FROM dbo.keyword_categories c
WHERE c.category_code = 'THEME' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'ACTIVITY');
IF NOT EXISTS (SELECT 1 FROM dbo.keyword_categories WHERE category_code = 'TRANSPORTATION')
    INSERT dbo.keyword_categories(category_code, category_name, sort_order) VALUES ('TRANSPORTATION', N'이동수단', 5);
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'CAR', N'자차', N'{"value":"CAR"}', 1 FROM dbo.keyword_categories c
WHERE c.category_code = 'TRANSPORTATION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'CAR');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'PUBLIC_TRANSIT', N'대중교통', N'{"value":"PUBLIC_TRANSIT"}', 2 FROM dbo.keyword_categories c
WHERE c.category_code = 'TRANSPORTATION' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'PUBLIC_TRANSIT');
COMMIT;
