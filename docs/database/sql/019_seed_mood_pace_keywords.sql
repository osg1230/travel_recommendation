-- 여행 분위기(MOOD)·여행 속도(PACE) 조건 선택지를 추가한다(2026-11-16, docs/ai/취향_감성_반영_계획.md).
-- 테이블·컬럼은 바꾸지 않고 행만 추가한다. 이미 있는 코드는 건너뛰므로 다시 실행해도 된다. 앱 시작 시 자동 실행하지 않는다.
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF NOT EXISTS (SELECT 1 FROM dbo.keyword_categories WHERE category_code = 'MOOD')
    INSERT dbo.keyword_categories(category_code, category_name, sort_order) VALUES ('MOOD', N'여행 분위기', 6);
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'QUIET', N'한적한', N'{"value":"QUIET"}', 1 FROM dbo.keyword_categories c
WHERE c.category_code = 'MOOD' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'QUIET');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'LIVELY', N'활기찬', N'{"value":"LIVELY"}', 2 FROM dbo.keyword_categories c
WHERE c.category_code = 'MOOD' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'LIVELY');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'EMOTIONAL', N'감성적인', N'{"value":"EMOTIONAL"}', 3 FROM dbo.keyword_categories c
WHERE c.category_code = 'MOOD' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'EMOTIONAL');
IF NOT EXISTS (SELECT 1 FROM dbo.keyword_categories WHERE category_code = 'PACE')
    INSERT dbo.keyword_categories(category_code, category_name, sort_order) VALUES ('PACE', N'여행 속도', 7);
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'RELAXED', N'느긋하게', N'{"value":"RELAXED"}', 1 FROM dbo.keyword_categories c
WHERE c.category_code = 'PACE' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'RELAXED');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'NORMAL', N'보통', N'{"value":"NORMAL"}', 2 FROM dbo.keyword_categories c
WHERE c.category_code = 'PACE' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'NORMAL');
INSERT dbo.keywords(category_id, keyword_code, keyword_name, value_json, sort_order)
SELECT category_id, 'PACKED', N'알차게', N'{"value":"PACKED"}', 3 FROM dbo.keyword_categories c
WHERE c.category_code = 'PACE' AND NOT EXISTS (SELECT 1 FROM dbo.keywords k WHERE k.category_id = c.category_id AND k.keyword_code = 'PACKED');
COMMIT;
