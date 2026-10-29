# DB 테이블 정의서 정리본

> 현재 기준: 2026-10-09 · GitHub main(2026-10-09 객실 브랜치 병합 포함) · 가상 예약·결제를 포함한 18개 테이블을 구현 범위로 다룬다. 배포 SQL은 sql/001~018이다(017 한국 시각, 018 평가 한줄평). 객실 유형 SQL 016과 객실 기능 코드는 2026-10-09 main에 병합했다. 기존 Word 파일은 과거 문서이며 현재 기준은 이 Markdown과 아래 실DB 확인 기록이다.

### 현재 구현 및 실DB 확인

- 요구사항 기준은 요구분석서 3.1과 통합 변경 내역, AGENTS.md이다. 관리자는 회원 관리만 수행한다.
- SQL Server 2019(15.0.2000.5), 이 PC의 travel_recommendation DB에 Windows 인증으로 읽기 전용 접속했다. dbo 테이블 18개, 외래키 25개를 확인했다. 비활성화 또는 미검증 상태인 FK·CHECK는 0개다.
- 기존 정의서 190개 컬럼은 실제 DB와 이름·자료형·길이·정밀도·소수 자릿수·NULL 허용 여부가 일치했다. 실제 DB에 추가된 reservation_stock.room_type(VARCHAR(20), NULL 허용, 값이 있는 행 293개)은 사용자가 Claude와 구현한 가상 객실 기능이다. origin/feature/rename-reservation-payment-labels의 a491c3e 커밋에서 SQL 016과 구현을 확인하고 정의서·ERD에 반영했다. 해당 기능 코드는 main에 아직 병합되지 않았으므로 현재 main 코드와 실DB의 적용 범위는 다르다.
- 14개 테이블은 JPA Entity로, 예약·결제 관련 4개 테이블은 VirtualReservationService의 JDBC로 접근한다. Entity가 없다는 이유로 미구현으로 분류하지 않는다.
- PK/FK·인덱스·DEFAULT·CHECK 정의 전체의 배포 SQL 대조와 기능·동시성 테스트는 이번에 수행하지 않았다. 따라서 DB 전체 검증 완료로 보지 않는다. DB 데이터와 스키마는 변경하지 않았다.
- 아래 적용 메모는 날짜별 구현 이력이다. 당시의 미구현·이후 작업 설명은 현재 상태로 해석하지 않는다.

### 1.9 적용 메모: 내 일정 저장 (2026-09-20)

- 8·9절 정의서의 컬럼·CHECK·UNIQUE를 그대로 [008 SQL](sql/008_create_itineraries.sql)로 생성했다. `itineraries`, `itinerary_items` 두 테이블만 추가하며 기존 테이블·컬럼·관계는 변경하지 않는다. row_version은 SQL Server가 생성한다.
- 저장 대상은 COMPLETED 상태이고 날짜가 확정된 ITINERARY 작업의 result_json이다. 날짜 미확정 초안은 저장하지 않고 ai_jobs에만 임시 보관한다. 저장은 회원만 가능하며 본인 작업인지 확인한다.
- `conditions_snapshot_json`은 저장 근거를 남기기 위해 schemaVersion, 원본 작업 ID(jobId), 지역(placeId·이름·지역명), 하루 시작·종료 시각·이동수단·체류시간·일차 수, 확정 조건 스냅샷을 담는다. schemaVersion 2부터 저장(또는 수정안 적용) 당시의 조건 확인 결과(conditionChecks: 항목·상태·내용)와 조회한 참고 가격(prices: 장소별 가격·출처·확인 수준·조회 시각)을 함께 담는다. 사용자가 정한 조건(constraints: 필수 방문 장소·하루 방문 수·예산 기준 등)이 있으면 함께 담아, 원래 초안이 만료·삭제된 뒤 수정용 초안을 다시 만들 때 같은 조건을 쓴다. 수정안을 적용하면 그 수정안 기준으로 다시 쓰며 이전 가격은 남기지 않는다. 컬럼 구조는 바뀌지 않는다. 같은 jobId를 다시 저장하면 새 행을 만들지 않고 저장해 둔 일정을 보여 준다.
- `itinerary_items`는 초안의 일차·순서를 day_no·sequence_no로, 시각을 날짜와 합친 KST 값으로 저장한다. activity는 유형명과 장소 이름이고 `place_snapshot_json`에 이름·안내·지도 링크·출처 링크·이동시간 조회 시각을 보관한다. TRANSIT 항목은 도착 장소의 place_id와 travel_minutes를 저장한다. transport_json·food_guide_json은 아직 사용하지 않는다.
- 이동수단은 초안이 만드는 CAR·TRANSIT만 저장한다. 수정은 제목(title)만 허용하고 시각·장소·순서는 바꾸지 않는다. 삭제는 세부 항목과 기본정보를 같은 트랜잭션에서 제거한다.
- 저장은 운영시간·휴무·예산·예약 가능 여부를 검증한다는 뜻이 아니다. 저장 당시 초안을 그대로 보관하며 화면에도 확인 필요 안내를 유지한다.

### 1.10 적용 메모: 즐겨찾기 (2026-09-20)

- 10절 정의서대로 [009 SQL](sql/009_create_favorites.sql)로 `favorites`(favorite_id·user_id·place_id·created_at, U1(user_id, place_id))만 추가했다. 다른 테이블·컬럼·관계는 변경하지 않는다.
- 허용 유형은 DESTINATION·ATTRACTION·ACCOMMODATION이다. RESTAURANT와 비활성 장소는 등록하지 않는다. 등록 서비스에서 먼저 확인하고, `TR_favorites_place_type` 트리거가 직접 INSERT·UPDATE도 막는다. `TR_places_favorite_type` 트리거는 즐겨찾기가 있는 장소의 place_type을 허용되지 않는 값으로 바꾸지 못하게 한다.
- 등록은 회원만 가능하고 본인 행만 조회·삭제한다. 같은 장소를 다시 등록해도 U1과 서비스 확인으로 새 행을 만들지 않는다. 일정 즐겨찾기는 만들지 않는다.
- 목록은 등록 당시 스냅샷이 아니라 현재 `places` 정보를 읽어 이름·지역·주소·출처/지도 링크를 보여 준다. 장소가 비활성이 되면 목록에서 빼고 등록 행은 남긴다. 영업시간·가격은 즐겨찾기에 저장하지 않는다.
### 1.11 적용 메모: 관리자 회원 관리 (2026-09-20)

- 14절 정의서대로 [010 SQL](sql/010_create_operation_logs.sql)로 `operation_logs`만 추가했다. `users`를 포함한 기존 테이블·컬럼·관계는 변경하지 않는다. 대상이 삭제돼도 기록을 남기도록 `target_id`에는 FK를 걸지 않는다.
- 관리자 회원 관리(SFR-15-01~03)는 검색·목록, 상세 조회, 상태 변경만 제공한다. 상태는 ACTIVE와 RESTRICTED 사이에서만 바꾸며 WITHDRAWN 전환과 회원 삭제는 이 기능에 없다. 관리자 계정과 탈퇴 회원은 상태 변경 대상이 아니다.
- 성공한 상태 변경은 `users.status` 수정과 같은 트랜잭션에서 `operation_logs`에 남긴다. actor_kind=ADMIN, target_type=USER, action_code=MEMBER_STATUS_CHANGE, outcome=SUCCESS이며 before/after 상태와 관리자가 입력한 사유를 보관한다. 실패는 기록하지 않고 화면 안내로 끝낸다(R3 실패 기록은 이후 범위).
- 기록에는 이메일·실명·입력 원문·금융정보를 넣지 않는다. 대상과 처리자는 식별자로만 남기고 화면에 보일 때만 아이디를 찾는다. 사유 입력란에도 개인정보를 적지 않도록 안내한다.
- 보관 기간 1년은 아직 자동 삭제로 구현하지 않았다. 별도 운영 이력 조회 화면도 만들지 않고 회원 상세에 최근 10건만 표시한다.
### 1.12 적용 메모: 주변 지역 추천 데이터 (2026-09-21)

- [012 SQL](sql/012_seed_nearby_places.sql)은 기존 `places`에 SYSTEM 행 9개(여행지 속초·양양·울산, 지역별 관광지 2곳)만 추가한다. 테이블·컬럼·관계·제약 변경은 없다. 관광지는 TourAPI에서 확인한 좌표를 저장하고, 여행지는 좌표 없이 공식 관광 사이트를 출처로 둔다.
- 새 여행지는 첫 추천 후보(강릉·부산·경주)에 들어가지 않고 일정 초안의 주변 지역 추천(SFR-07)에서만 쓴다. 지역 간 이동시간을 재는 기준 터미널은 테이블을 만들지 않고 `NearbyRegionService`의 상수로 둔다.

### 1.13 적용 메모: 가상 예약·결제 (2026-09-22)

- [013 SQL](sql/013_create_virtual_reservations.sql)로 15~18절 테이블(`reservation_stock`·`virtual_reservations`·`virtual_payments`·`reservation_logs`)을 정의서 컬럼·제약 그대로 만들었다. UF1은 `WHERE status='SUCCESS'` 필터 UNIQUE 인덱스라 스크립트에 `SET QUOTED_IDENTIFIER ON`을 둔다. 다른 테이블은 바꾸지 않았다.
- 시스템 시드 수량: SYSTEM 숙소 3곳(씨마크 호텔·신라스테이 해운대·라한셀렉트 경주, 객실 5실, 15:00 입실~다음 날 11·12시 퇴실)과 유료 관광시설 2곳(오죽헌·시립박물관 200명, 동궁과 월지 300명, 하루 이용)의 2026-10-01~2026-12-31 매일 구간, 460행이다. 카카오 캐시 장소는 24시간 뒤 사라지므로 넣지 않았다.
- 단위 가격: 숙소는 여기어때 2026-11-18(수) 1박·2인 룸온리 판매가(2026-09-22 확인) 참고가 319,000·352,000·200,000원, 시설은 성인 개인 관람료 3,000원이다. 출처와 객실당 최대 인원(2·2·4명)은 코드 상수 `ReservationCatalog`에 두고 화면에 밝힌다. 동궁과 월지 관람료는 경주시 페이지를 직접 열지 못해 검색 결과 기준이다.
- 결제수단은 TEST 상수이며 화면에서 성공·실패(테스트)를 고른다. 금액은 서버가 `quantity × unit_price`로 계산한다. 요청 해시는 SHA-256(예약번호·금액·수단)이다.
- 상태 전이 거절(409)과 예기치 않은 예외로 인한 롤백은 별도 트랜잭션으로 `operation_logs`에 actor_kind=MEMBER, target_type=RESERVATION/PAYMENT, outcome=FAILED로 남긴다. 성공 처리는 `reservation_logs`에만 남긴다. R3 관리자 조회 화면은 만들지 않았다.
- 일정 삭제는 `virtual_reservations.itinerary_id`를 NULL로 바꾼 뒤 일정을 지운다. 탈퇴는 점유 중 수량을 취소 절차로 한 번 복구한 뒤 로그·결제·예약을 지운다.
- 주변 지역 추천 결과는 새 테이블 없이 일정 작업(`ai_jobs.result_json`)의 `nearby`에 조회 시점 값으로 저장한다. 저장 일정(`itineraries`)에는 옮기지 않는다.

- 본문의 적용 범위 문단은 관리자가 키워드 카테고리·키워드를 관리한다고 적고 있으나, 현재 개발 규칙(AGENTS.md 6절)과 3.1 통합 변경 내역은 관리자 범위를 회원 관리로 한정하고 키워드 CRUD를 추가하지 않는다. 키워드 테이블은 조회용으로 유지한다.

### 1.14 적용 메모: 회원 출발지 (2026-09-29)

- [015 SQL](sql/015_add_user_home_area.sql)로 `users.home_area NVARCHAR(60) NULL`을 추가했다. 이유: 일정을 만들 때마다 출발지를 적거나 휴대폰 위치 권한을 허용하지 않아도 되게, 회원이 가입·내 정보에서 적은 '주로 출발하는 곳'을 일정 입력 출발지 기본값으로 쓴다. 화면에서 고친 값은 그 일정에만 쓰고 이 컬럼은 바꾸지 않는다.
- 선택 입력이고 자유 형식(예: 서울 송파구, 대전역, 도로명 주소)이다. 카카오 길찾기 출발지 조회에만 쓰며 GPT 요청·로그에 넣지 않는다. 탈퇴하면 NULL로 지운다. 기존 회원은 NULL이며 일정 입력은 예전처럼 빈 칸으로 시작한다.
- 영향: 다른 PC의 DB에도 015를 적용해야 한다(적용하지 않으면 `ddl-auto=validate` 테스트와 실행이 컬럼 없음으로 실패한다). 다른 테이블·관계 변경은 없다.

### 1.15 적용 메모: 숙소 객실 종류 (2026-10-05)

- [016 SQL](sql/016_add_reservation_room_type.sql)로 `reservation_stock.room_type VARCHAR(20) NULL`을 추가하고 U1을 (place_id, usage_start_at, usage_end_at, room_type)으로 바꿨다. 이유: 예약 대상이 시드 숙소 3곳뿐이라 대부분의 일정에서 예약을 체험할 수 없어, 숙소는 모두 예약할 수 있게 하고 객실 종류(싱글·스탠다드 더블·스탠다드 트윈·디럭스 더블·패밀리·스위트)별로 수량을 둔다. 실제 호텔 객실·요금을 가져온 것이 아니라 이름만 쓰는 체험용 값이다.
- 기존 데이터: 숙소 수량 276행은 `STANDARD_DOUBLE`로 채웠고(가격·수량 그대로), 관광시설 184행은 NULL이다. 예약·결제·이력 행은 바꾸지 않았다.
- 숙소 객실 행은 시드로 넣지 않고 서버가 필요할 때 만든다(객실 종류마다 하루 3실, 내일부터 1년 안). 기준가·배수는 코드(`RoomType`, `VirtualReservationService.ensureRooms`)에 있고 [기능 문서](../ai/가상_예약_결제.md)에 적었다. 카카오로 찾은 숙소도 `places` 행이 남아 있어 그대로 참조한다.
- 영향: 다른 PC의 DB에도 016을 적용해야 한다(적용하지 않으면 예약 화면과 저장 일정의 예약 버튼이 동작하지 않는다. 저장 일정 상세 자체는 열린다). 다른 테이블·관계 변경은 없다. ERD의 컬럼 목록에는 아직 반영하지 않았다.

### 1.15 적용 메모: 저장 시각 기준을 한국 시각으로 변경 (2026-10-10)

- [017 SQL](sql/017_convert_timestamps_to_kst.sql)로 생성·수정·처리 시각의 DEFAULT를 SYSUTCDATETIME()에서 SYSDATETIME()으로 바꾸고, 이미 UTC로 저장된 datetime2 값에 9시간을 더했다. 이용 시각(itinerary_items.start_at/end_at, reservation_stock.usage_start_at/usage_end_at)은 처음부터 한국 시각이라 바꾸지 않았다. JSON 안의 절대 시각(Instant)도 그대로다.
- 이유: 국내에서만 쓰는 서비스라 저장 값과 화면 값을 같은 한국 시각으로 두어 이해하기 쉽게 하기 위해서다(사용자 결정). 자바는 KoreaTime.now()로 시각을 만들고, 예약 SQL의 SYSUTCDATETIME()도 SYSDATETIME()으로 바꿨다. 화면 변환(TimeText)은 한국 시각 LocalDateTime을 그대로 보여 준다.
- 영향: 다른 PC의 DB에도 017을 적용해야 한다(백업 후 실행, 두 번 실행하면 중단된다). 테이블·컬럼·관계 변경은 없고 DEFAULT 정의와 기존 값만 바뀐다. 배포 SQL 001~013의 DEFAULT도 SYSDATETIME()으로 고쳐 새 DB는 처음부터 한국 시각이다.

### 1.16 적용 메모: 장소 평가 한줄평 (2026-10-28)

- [018 SQL](sql/018_add_rating_comment.sql)로 `place_ratings.comment NVARCHAR(200) NULL`을 추가했다. 다른 테이블·컬럼·관계와 기존 평가 행은 바꾸지 않는다. 두 번 실행하면 중단된다.
- 이유: 사용자 요청(2026-10-28)으로 별점·태그 평가에 선택 입력 한줄평을 더하고, 다른 회원의 최근 평가(별점·태그·한줄평)를 장소별로 보여 주기 위해서다(SFR-10 보완). 화면은 100자까지 받고 서버가 앞뒤 공백을 지운 뒤 비어 있으면 NULL로 저장한다. 본문·제목 컬럼은 여전히 두지 않는다.
- 영향: 다른 PC의 DB에도 017 다음에 018을 적용해야 한다. 적용하지 않은 DB로 새 코드를 실행하면 평가 저장·조회에서 comment 컬럼이 없어 오류가 난다. 관리자 한줄평 삭제 기능은 없고 부적절한 글은 회원 이용 제한으로 대응한다.

### 1.8 적용 메모: 외부 장소 캐시

- 기존 places의 source_provider=KAKAO, source_place_id와 복합 유일 키를 사용한다. 이름·주소·유형·지역·위경도·출처/지도 URL·verified_at·updated_at을 저장/갱신하며 새 컬럼·관계·SQL은 없다.
- API 조회 후 지역 행 잠금 아래 한 트랜잭션으로 반영한다. SYSTEM과 비활성 행을 덮어쓰지 않는다. 카카오 후보는 verified_at 기준 24시간 이내만 조회에 포함하며 오래된 행을 자동 삭제하지 않는다.
- ITINERARY 후보 스냅샷에 nullable longitude·latitude·sourceProvider를 포함한다. 기존 스냅샷은 좌표가 없으면 주소 조회를 사용한다. 관리자 장소 CRUD는 추가하지 않는다. [외부 장소 조회](../ai/외부_장소_조회.md)를 따른다.

### 1.7 적용 메모: 임시 일정 작업

- `ai_jobs.job_type=ITINERARY`의 request_json은 부모 추천 작업 ID, 선택한 지역/장소 후보, 확정 조건, 하루 시작·종료 시간, 교통수단과 체류시간을 보관한다.
- 2026-09-23: 수정안의 `revision`에 `unavailablePlaces`(문장 수정 요청에 이름이 나왔지만 교체 후보에 없어 비슷한 곳으로 바꾼 장소 이름, 최대 3개)를 추가했다. 이전 작업 JSON에 없으면 빈 목록으로 읽는다. 테이블·컬럼 변경은 없다.
- 2026-09-24: 추천 작업 결과(`ai_jobs.result_json`)의 추천 항목에 `centerPlaces`(GPT가 추천 이유와 함께 적은 장소 이름, 최대 3개)를, 일정 작업 request_json의 `constraints`에 `centerPlaceIds`(날마다 일정의 중심으로 삼을 관광지 ID)를 추가했다. 이전 JSON에 없으면 빈 목록으로 읽는다. 테이블·컬럼 변경은 없다.
- 2026-09-22: 실행용 조건 스냅샷에 `directRecommendation`(후보 조회 후 추천 자동 연결)과 `travelPurpose`(개인정보를 제외하도록 추출한 활동 목적)를 추가했다. 이전 JSON의 누락 필드는 false/null로 읽는다. 일정 단계에서 보완한 기간·숙박은 해당 일정 작업 조건에만 저장하며 부모 추천은 변경하지 않는다. 기존 JSON 컬럼을 사용하므로 테이블·컬럼 변경 SQL은 없다.
- 2026-09-22: 추천 작업의 조건 스냅샷(`ai_jobs.request_json`)에 `purposeSearch`를 추가했다. 목적 시설 검색 상태(FOUND·NONE·SKIPPED·FAILED), AI가 만든 시설 검색어, 찾은 시설(저장한 관광지 `place_id`, 여행지 `place_id`, 이름·주소·분류·카카오 링크), 안내 문구, 조회 시각을 담는다. 찾은 시설은 기존 `places` 행(KAKAO, ATTRACTION)으로 저장하며 테이블·컬럼 변경은 없다. 같은 날 `purposeSearch.themes`(목적과 가까운 여행지 분류 이름 목록)를 추가했다. 이전 스냅샷에서 없으면 빈 목록으로 읽는다.
- result_json은 지역·교통수단·생성일시·날짜별 항목 목록이다. 항목은 유형(VISIT/MEAL/TRANSIT/STAY/FREE), 장소 ID, 이름, 시작/종료 시각, 안내, 좌표 지도 링크, 출처 링크, API 이동 초·조회일시(해당 시)를 포함한다. 화면은 ‘일정 초안’으로 표시하며 COMPLETED는 계산 완료를 뜻한다. 영업/휴무·예산·예약 검증 완료를 의미하지 않는다.
- 회원 user_id/request_id 또는 비회원 guest_token_hash와 expires_at은 부모 작업에서 이어받는다. 추천·일정 조회/큐는 job_type을 검사한다. 임시 초안을 itineraries나 itinerary_items에 복사하지 않으며 저장 기능은 이후 단계다.
- 기존 상태와 CHECK를 그대로 사용한다. 자동 AI 생성·자기수정 루프는 아직 연결하지 않았으며 이번 배치는 서버 규칙으로 1회 수행한다. 구체적인 동작과 미구현 범위는 [일정 초안 구현 문서](../ai/시간대별_일정_초안.md)를 따른다. 요구분석서 3.1 전체와 하단 설계를 모두 구현한 것으로 보지 않는다.

DB 테이블 정의서

AI 기반 국내 여행 일정 추천 서비스

버전 1.0  |  작성일 2026년 9월 8일  |  SQL Server 기준

본 문서는 여행 추천·일정 저장·평가·운영·가상 예약·결제를 위한 18개 테이블을 정의한다. 요청 조건은 recommendation_requests에 통합하며 ai_jobs와 place_rating_tags를 유지한다.

기준 문서  창프2_요구분석서 3.0_관리자범위축소_최종본.docx  |  요구분석서 수정일 2026년 8월 19일

적용 범위와 표기

관리자는 회원 상태를 관리한다. 관리자 키워드·장소·숙소·음식점 CRUD 및 타인 평가 관리는 제공하지 않는다. 가상 예약·결제는 구현된 정식 기능이며 실제 시설 예약이나 실제 금전 결제는 수행하지 않는다.

NULL의 N은 필수, Y는 허용을 뜻한다. PK·FK·UNIQUE의 -는 해당 제약 없음이다. U1·U2·P1·UF1은 각 테이블 내부의 제약 그룹이며 여러 열에 같은 코드가 있으면 복합 제약이다. PK는 별도 UNIQUE 표시 없이 유일성을 포함한다.

KST_NOW는 SYSDATETIME()(서버 현지 시각 = 한국 시각)이다. 2026-10-10 SQL 017로 UTC 저장(SYSUTCDATETIME)에서 한국 시각 저장으로 바꿨다. DEFAULT는 INSERT 시만 적용되므로 updated_at은 서비스가 갱신한다. IDENTITY 및 ROWVERSION은 DEFAULT가 아닌 SQL Server 생성 속성이다. CHECK의 열거값은 해당 열 IN (...)으로 구현하며 NULL 허용 열은 값이 존재할 때 검사한다.

JSON은 ISJSON(컬럼)=1을 뜻한다. 필수 속성·스키마·값 범위는 서버 검증기로 검사한다. FK 대상은 테이블.컬럼으로 표시했다. 교차 테이블 조건·상태 전이·권한은 CHECK만으로 보장되지 않으므로 명시된 트랜잭션·서비스·트리거를 함께 구현한다.

전체 테이블 목록

| 번호 | 테이블명 | 한글명 | 요구사항 및 범위 |
| --- | --- | --- | --- |
| 1 | users | 사용자 | DAR-01 / R1·R2 |
| 2 | keyword_categories | 여행 조건 카테고리 | DAR-07 / R1 |
| 3 | keywords | 여행 조건 키워드 | DAR-07 / R1 |
| 4 | recommendation_requests | 추천 요청 및 이력 | DAR-02·09 / R1·R2 |
| 5 | recommendation_candidates | 추천 여행지 후보 | DAR-03·09 / R1·R2 |
| 6 | ai_jobs | AI 비동기 생성 작업 | DAR-09·QUR-03 / R1 |
| 7 | places | 검증된 장소 공통 정보 | DAR-03·04·10 / R1·R2 |
| 8 | itineraries | 저장 여행 일정 | DAR-05 / R2 |
| 9 | itinerary_items | 세부 일정 항목 | DAR-06·10 / R2 |
| 10 | favorites | 장소 즐겨찾기 | DAR-14 / R2 |
| 11 | place_ratings | 장소 별점 평가 | DAR-08 / R2 |
| 12 | rating_tags | 평가 태그 사전 | DAR-08 / R2 |
| 13 | place_rating_tags | 평가 태그 연결 | DAR-08 / R2 |
| 14 | operation_logs | 운영 처리 이력 | DAR-13 / 회원 관리 및 예약·결제 실패 기록 |
| 15 | reservation_stock | 가상 예약 가능 수량 | DAR-11 / 가상 예약·결제 |
| 16 | virtual_reservations | 가상 예약 | DAR-11 / 가상 예약·결제 |
| 17 | virtual_payments | 가상 결제 | DAR-12 / 가상 예약·결제 |
| 18 | reservation_logs | 예약 수량 변경 이력 | DAR-11 / 가상 예약·결제 |

1 사용자

| 테이블명 | 한글명 | 설명 |
| --- | --- | --- |
| users | 사용자 | 회원과 관리자를 같은 계정 구조로 관리한다. |

관련 요구사항 및 구현 범위  DAR-01 / R1·R2

| 컬럼명 | 자료형 | NULL | PK | FK | UNIQUE | DEFAULT | CHECK | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| user_id | BIGINT | N | Y | - | - | - | - | IDENTITY(1,1) 자동 증가 |
| login_id | VARCHAR(50) | N | - | - | U1 | - | - | 사용자가 로그인할 때 사용하는 아이디 |
| password_hash | VARCHAR(255) | N | - | - | - | - | - | 단방향 비밀번호 해시 |
| nickname | NVARCHAR(50) | N | - | - | UF1 | - | - | 탈퇴하지 않은 회원끼리 중복 금지 |
| role | VARCHAR(20) | N | - | - | - | 'MEMBER' | MEMBER, ADMIN | - |
| status | VARCHAR(20) | N | - | - | - | 'ACTIVE' | ACTIVE, RESTRICTED, WITHDRAWN | - |
| created_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 생성 시각 |
| updated_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 최종 수정 시각 |
| withdrawn_at | DATETIME2(3) | Y | - | - | - | - | - | 처리 시각 |
| home_area | NVARCHAR(60) | Y | - | - | - | - | - | 주로 출발하는 곳(선택, 자유 입력). 일정 입력 출발지 기본값, 탈퇴 시 NULL (2026-09-29, SQL 015) |

제약과 처리 규칙

U1(login_id). 로그인 아이디는 앞뒤 공백을 제거한 뒤 저장하고, 로그인·중복 검사에도 같은 정규화 기준을 적용한다. nickname은 사용자 닉네임이다. password_hash만 저장하며 비밀번호 평문 저장은 금지한다.

UF1(nickname)은 `WHERE status <> 'WITHDRAWN'` 필터 UNIQUE 인덱스다. ACTIVE와 RESTRICTED 회원은 닉네임 중복을 허용하지 않는다. 탈퇴 회원은 공통 익명 닉네임을 사용할 수 있다. 이는 요구분석서의 닉네임 중복 거부와 기존 탈퇴 익명화 정책을 함께 만족시키는 구현 기준이다. 아이디와 닉네임은 앞뒤 공백을 제거하고 DB의 동일한 collation으로 조회·중복 검사를 수행한다. 대소문자 비교는 실제 DB collation을 따른다.

회원 테이블 배포 SQL: [001_create_users.sql](sql/001_create_users.sql). 기존 테이블이 있으면 중단하며 DROP이나 자동 재생성을 하지 않는다. Entity는 `User`, enum은 `UserRole`과 `UserStatus`, Repository는 `UserRepository`다. 생성·수정 시각은 JPA 콜백에서 한국 시각(`KoreaTime`)으로 기록하고 원시 SQL 삽입에는 DB 기본값이 적용된다. 자동 스키마 변경 설정은 계속 none으로 유지한다.

CHECK: (status = WITHDRAWN AND withdrawn_at IS NOT NULL) OR (status <> WITHDRAWN AND withdrawn_at IS NULL). 문자열 값은 SQL에서 작은따옴표로 감싼다.

관리자는 ACTIVE ↔ RESTRICTED만 변경한다. 본인 제한 및 관리자에 의한 탈퇴·계정 삭제는 거부한다. 탈퇴는 회원 본인 절차에서만 허용한다.

2 여행 조건 카테고리

| 테이블명 | 한글명 | 설명 |
| --- | --- | --- |
| keyword_categories | 여행 조건 카테고리 | 여행 조건 선택 화면의 카테고리와 표시 순서를 관리한다. |

관련 요구사항 및 구현 범위  DAR-07 / R1

| 컬럼명 | 자료형 | NULL | PK | FK | UNIQUE | DEFAULT | CHECK | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| category_id | BIGINT | N | Y | - | - | - | - | IDENTITY(1,1) 자동 증가 |
| category_code | VARCHAR(40) | N | - | - | U1 | - | - | REGION, PERIOD 등 안정적인 코드 |
| category_name | NVARCHAR(50) | N | - | - | U2 | - | - | - |
| sort_order | INT | N | - | - | - | 0 | >= 0 | - |
| is_active | BIT | N | - | - | - | 1 | - | - |
| created_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 생성 시각 |
| updated_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 최종 수정 시각 |

제약과 처리 규칙

U1(category_code), U2(category_name). 관리자만 추가·수정·삭제·비활성화할 수 있다.

소속 키워드가 있으면 물리 삭제를 제한하고 비활성화를 사용한다. 코드 변경은 조건 해석에 영향을 주므로 서비스에서 차단한다.

3 여행 조건 키워드

| 테이블명 | 한글명 | 설명 |
| --- | --- | --- |
| keywords | 여행 조건 키워드 | 카테고리별 선택 항목과 구조화 값을 저장한다. |

관련 요구사항 및 구현 범위  DAR-07 / R1

| 컬럼명 | 자료형 | NULL | PK | FK | UNIQUE | DEFAULT | CHECK | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| keyword_id | BIGINT | N | Y | - | - | - | - | IDENTITY(1,1) 자동 증가 |
| category_id | BIGINT | N | - | keyword_categories.category_id | U1,U2 | - | - | 참조 식별자 |
| keyword_code | VARCHAR(50) | N | - | - | U1 | - | - | - |
| keyword_name | NVARCHAR(80) | N | - | - | U2 | - | - | - |
| value_json | NVARCHAR(MAX) | N | - | - | - | - | JSON | 조건 해석 값과 단위 |
| sort_order | INT | N | - | - | - | 0 | >= 0 | - |
| is_active | BIT | N | - | - | - | 1 | - | - |
| created_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 생성 시각 |
| updated_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 최종 수정 시각 |

제약과 처리 규칙

U1(category_id, keyword_code), U2(category_id, keyword_name). 동일 카테고리 안에서 중복을 금지한다.

사용된 키워드는 비활성화를 우선한다. 과거 요청은 ID와 명칭·값 스냅샷을 함께 보존하므로 명칭 변경의 영향을 받지 않는다.

### 3.1 키워드 조회 구현 기준 (2026-09-11)

- 2·3절의 컬럼·관계는 그대로 구현했다. [002_create_keywords.sql](sql/002_create_keywords.sql)은 두 테이블 중 하나라도 있으면 중단한다. 기존 테이블을 삭제·초기화하지 않는다.
- [003_seed_keywords.sql](sql/003_seed_keywords.sql)은 카테고리 5개와 기존 입력 화면의 선택지 33개를 코드 기준으로 추가한다. 재실행 시 존재하는 항목의 이름·활성 상태·값을 덮어쓰지 않는다.
- 화면 필드와 카테고리 코드: region→REGION, season→SEASON, companion→COMPANION, themes→THEME, transportation→TRANSPORTATION. 기간·예산은 기존 숫자·날짜 입력을 유지한다.
- 초기 value_json은 `{"value":"SEOUL"}`처럼 안정적인 키워드 코드를 담는 객체이다. 코드·표시명 매핑은 초기 데이터 SQL에 있다. 좌표나 외부 관광 API 코드로 간주하지 않는다. 자연어 조건 분석 시 이 값의 해석과 확정 조건 검증을 연결한다.
- 두 테이블 모두 활성인 항목만 표시 순서·ID 순으로 조회한다. 제출값은 키워드 ID이며 카테고리별 허용 목록을 다시 확인한다. 검증 당시 ID·카테고리 코드·이름·value_json을 세션에 함께 보관한다. 추천 요청 DB 저장은 아직 하지 않는다.
- Entity는 선택지 조회에 사용하며 등록·수정 API는 제공하지 않는다. 초기 생성 시간은 SQL 기본값으로 기록한다. (2026-10-28 관리자 키워드 관리 구현으로 3.2가 대체)
- 원본 문서의 관리자 키워드 관리 표현과 AGENTS.md의 회원 관리 제한은 아직 불일치한다. 이번 구현은 사용자용 선택지 조회만 수행하며 관리자 키워드 화면은 만들지 않는다. (2026-10-28 사용자 결정으로 AGENTS.md 6절에 키워드 관리를 넣어 불일치를 해소했다. 3.2 참고)

### 3.2 관리자 키워드 관리 구현 기준 (2026-10-28)

- 2·3절의 테이블·컬럼·제약은 바꾸지 않았다. 관리자 화면(`/admin/keywords`)에서 `keyword_categories`의 `category_name`·`sort_order`·`is_active`와 `keywords`의 `keyword_name`·`sort_order`·`is_active`를 고치고 `updated_at`을 갱신한다.
- 카테고리는 추가·삭제하지 않는다. 조건 입력 화면의 질문 5개(REGION·SEASON·COMPANION·THEME·TRANSPORTATION)가 코드에 고정돼 있기 때문이다. `category_code`는 바꾸지 않는다(2절 규칙).
- 키워드는 물리 삭제하지 않고 `is_active = 0`으로 사용 중지한다(3절 "사용된 키워드는 비활성화를 우선"). 저장된 추천 요청의 `conditions_json.selected_keywords`가 keyword_id를 참조한다. `keyword_code`·`value_json`은 바꾸지 않는다.
- 새 키워드는 COMPANION·THEME에만 추가한다. `keyword_code`는 영문으로 시작하는 영문 대문자·숫자·밑줄 2~50자이고, `value_json`은 초기 데이터와 같은 `{"value":"코드"}`이다. U1·U2(같은 카테고리 안 코드·이름 중복)는 서비스에서 먼저 검사하고, 동시에 저장해 DB 제약에 걸리면 중복 안내를 보여 준다.
- REGION·SEASON·TRANSPORTATION과 THEME 기본 6개(NATURE·SEA·REST·FOOD·CULTURE·ACTIVITY)는 추천 규칙이 이름·값을 그대로 쓰므로 이름 변경과 추가를 서비스에서 막고 표시 순서·사용 여부만 바꾼다.
- 키워드 변경은 `operation_logs`에 남기지 않는다. 14절 `target_type` CHECK가 USER·RESERVATION·PAYMENT만 허용해, 기록하려면 제약을 바꿔야 하므로 이번 범위에 넣지 않았다.

4 추천 요청 및 이력

| 테이블명 | 한글명 | 설명 |
| --- | --- | --- |
| recommendation_requests | 추천 요청 및 이력 | travel_requests를 통합한 회원의 추천 요청 단위이다. 여러 후보는 recommendation_candidates에 저장한다. |

관련 요구사항 및 구현 범위  DAR-02·09 / R1·R2

| 컬럼명 | 자료형 | NULL | PK | FK | UNIQUE | DEFAULT | CHECK | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| request_id | BIGINT | N | Y | - | - | - | - | IDENTITY(1,1) 자동 증가 |
| user_id | BIGINT | N | - | users.user_id | - | - | - | 참조 식별자 |
| input_mode | VARCHAR(10) | N | - | - | - | - | TEXT, KEYWORD | - |
| original_text | NVARCHAR(2000) | Y | - | - | - | - | - | 자연어 원문 |
| conditions_json | NVARCHAR(MAX) | N | - | - | - | - | JSON | 확정 조건과 선택 키워드 스냅샷 |
| region | NVARCHAR(100) | Y | - | - | - | - | - | NULL이면 지역 상관없음 |
| start_date | DATE | Y | - | - | - | - | - | - |
| end_date | DATE | Y | - | - | - | - | - | - |
| duration_days | SMALLINT | Y | - | - | - | - | > 0 | - |
| budget | DECIMAL(12,2) | Y | - | - | - | - | >= 0 | 원화 총 예산 상한 |
| accommodation_needed | BIT | Y | - | - | - | - | - | NULL 미확정, 0 불필요, 1 필요 |
| interpretation_summary | NVARCHAR(1000) | Y | - | - | - | - | - | 조건 해석 및 보완 근거 |
| created_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 생성 시각 |
| updated_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 최종 수정 시각 |

제약과 처리 규칙

CHECK: 날짜는 둘 다 NULL 또는 둘 다 존재하고 end_date >= start_date. 날짜가 있으면 duration_days = DATEDIFF(day,start_date,end_date)+1이며 NULL을 허용하지 않는다. TEXT 입력은 original_text 필수이다.

conditions_json 필수 구조: schema_version, season, companion, theme[], transportation, party_size, include_nearby, selected_keywords[{keyword_id, category_code, name, value}]. 미선택은 null 또는 빈 배열로 저장하고 화면에서 상관없음으로 표시한다.

기간만 입력한 경우 날짜는 NULL로 두고 duration_days를 사용한다. 구조화 열과 JSON에 같은 조건을 중복 저장하지 않는다. 회원 이력 삭제 시 저장 일정은 유지한다. 비회원 요청은 이 테이블에 영구 저장하지 않고 ai_jobs.request_json과 result_json으로 임시 관리한다.

5 추천 여행지 후보

| 테이블명 | 한글명 | 설명 |
| --- | --- | --- |
| recommendation_candidates | 추천 여행지 후보 | 회원 요청에 대한 검증된 여행지 후보와 추천 이유를 저장한다. |

관련 요구사항 및 구현 범위  DAR-03·09 / R1·R2

| 컬럼명 | 자료형 | NULL | PK | FK | UNIQUE | DEFAULT | CHECK | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| candidate_id | BIGINT | N | Y | - | - | - | - | IDENTITY(1,1) 자동 증가 |
| request_id | BIGINT | N | - | recommendation_requests.request_id | U1,U2 | - | - | 참조 식별자 |
| place_id | BIGINT | N | - | places.place_id | U2 | - | - | 참조 식별자 |
| rank_no | TINYINT | N | - | - | U1 | - | BETWEEN 1 AND 5 | - |
| recommendation_reason | NVARCHAR(2000) | N | - | - | - | - | - | - |
| place_snapshot_json | NVARCHAR(MAX) | N | - | - | - | - | JSON | 추천 당시 이름·지역·설명·출처 |
| created_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 생성 시각 |

제약과 처리 규칙

U1(request_id, rank_no), U2(request_id, place_id). 일반 목표는 후보 3~5개지만 특정 지역 조건과 R1 지원 범위를 우선한다. 2026-09-12 구현은 조회한 실제 후보 1~5개를 각각 한 번씩 추천하며 현재 R1에서는 최대 3개다. 특정 지역 지정은 해당 지역 1개, 미지정은 활성인 초기 3개 지역을 비교한다. 후보 수를 채우려고 조건 밖 지역을 추가하지 않는다. 완료 트랜잭션에서 입력 후보와 결과의 ID 집합·개수가 정확히 같은지 검증한다. rank_no의 DB CHECK는 1~5를 유지한다.

place_id는 DESTINATION 유형만 허용하며 서비스 또는 트리거에서 검증한다. 과거 상세는 스냅샷을 표시하고 현재 비활성 장소임을 별도로 안내한다.

6 AI 비동기 생성 작업

| 테이블명 | 한글명 | 설명 |
| --- | --- | --- |
| ai_jobs | AI 비동기 생성 작업 | 회원·비회원 추천 및 일정 생성의 jobId, 소유권, 처리 단계와 결과를 영속 관리한다. |

관련 요구사항 및 구현 범위  DAR-09·QUR-03 / R1

| 컬럼명 | 자료형 | NULL | PK | FK | UNIQUE | DEFAULT | CHECK | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| job_id | UNIQUEIDENTIFIER | N | Y | - | - | NEWID() | - | API jobId |
| user_id | BIGINT | Y | - | users.user_id | - | - | - | 참조 식별자 |
| guest_token_hash | BINARY(32) | Y | - | - | - | - | - | 비회원 임시 세션 토큰 해시 |
| request_id | BIGINT | Y | - | recommendation_requests.request_id | - | - | - | 참조 식별자 |
| job_type | VARCHAR(20) | N | - | - | - | - | RECOMMENDATION, ITINERARY | - |
| status | VARCHAR(32) | N | - | - | - | 'QUEUED' | J1 | - |
| attempt_count | TINYINT | N | - | - | - | 0 | BETWEEN 0 AND 3 | - |
| request_json | NVARCHAR(MAX) | N | - | - | - | - | JSON | 작업 실행 입력과 선택 후보 스냅샷 |
| result_json | NVARCHAR(MAX) | Y | - | - | - | - | JSON | 검증 완료 결과 및 임시 일정 |
| validation_errors_json | NVARCHAR(MAX) | Y | - | - | - | - | JSON | 오류 코드·충돌 구간·수정 안내 |
| error_code | VARCHAR(60) | Y | - | - | - | - | - | - |
| message | NVARCHAR(1000) | Y | - | - | - | - | - | 안전한 사용자 안내 |
| created_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 생성 시각 |
| started_at | DATETIME2(3) | Y | - | - | - | - | - | 작업 실행 시작 시각 |
| completed_at | DATETIME2(3) | Y | - | - | - | - | - | 작업 완료 또는 실패 시각 |
| expires_at | DATETIME2(3) | N | - | - | - | - | - | 임시 작업 입력과 결과의 보존 만료 시각 |
| row_version | ROWVERSION | N | - | - | - | - | - | 동시 갱신 감지, 시각 아님 |

제약과 처리 규칙

J1: QUEUED, ANALYZING_CONDITIONS, SEARCHING_CANDIDATES, CANDIDATES_READY, RECOMMENDATION_QUEUED, RANKING_CANDIDATES, GENERATING_SCHEDULE, VALIDATING_SCHEDULE, COMPLETED, FAILED.

CHECK: user_id와 guest_token_hash 중 정확히 하나만 존재. 비회원이면 request_id IS NULL. CHECK: 종료 상태는 completed_at 필수, 그 외는 NULL. COMPLETED는 result_json 필수, FAILED는 error_code 필수.

회원 recommendation 작업은 request_id 필수로 생성하고 소유자가 같은지 검사한다. 일정 작업은 원 이력이 삭제되어도 request_json으로 실행할 수 있다. 비회원은 expires_at 이후 작업 입력·결과를 삭제한다.

attempt_count는 최초 AI 생성 포함 최대 3회이며 재생성은 최대 2회이다. 작업 실행 제한·재시작 복구·상태 전이는 뒤의 설계 원칙을 따른다. 진행 문구는 status를 화면 문구에 매핑하고, 별도 설명은 message를 사용한다. current_step은 중복 저장하지 않는다.

### 6.1 후보 조회·작업 생성 구현 기준 (2026-09-11)

이 절은 후보 조회 단계 구현 기록이며 GPT 추천 연결의 최신 기준은 6.2절이다.

- 요구사항 SFR-02-04·SFR-03-01·DAR-02·03·09의 단계별 구현이다. 확정 조건으로 작업과 실제 지역 후보를 준비하며 GPT의 최종 추천·이유 생성은 다음 구현이다.
- 최종 추천 완료로 오인하지 않도록 `CANDIDATES_READY`를 J1에 추가했다. 컬럼·FK 변경은 없고 status CHECK 허용값만 추가했다. `QUEUED → SEARCHING_CANDIDATES → CANDIDATES_READY` 또는 `FAILED`로 처리한다. 자연어 분석은 확정 전에 끝나므로 다시 실행하지 않는다.
- 준비 상태는 GPT 실행 전 대기 지점이다. result_json에는 검증한 지역 후보 스냅샷 배열이 필수이고 completed_at은 NULL이다. 화면 폴링을 멈추며 자동 GPT 실행을 하지 않는다. 최종 COMPLETED 및 후보 3~5개 제약은 이 단계에 적용하지 않으며 recommendation_candidates에는 저장하지 않는다.
- R1 도시 3개(강릉·부산·경주)의 공식 관광 출처를 확인한 SYSTEM 데이터를 places에 추가한다. 특정 지역 또는 도 단위 별칭이면 해당 도시만, 미지정이면 3개를 조회한다. 지원하지 않는 상세 지역·복수 지역은 임의로 넓히지 않고 실패 안내한다. 주변 지역 이동 가능성·예산·기간·취향 적합도는 아직 판단하지 않는다.
- 회원은 동일 트랜잭션으로 recommendation_requests와 ai_jobs를 생성하고 비회원은 ai_jobs만 생성한다. 구조화 열과 conditions_json은 중복 조건을 저장하지 않는다. 실행용 request_json은 확정 DTO 전체 스냅샷이다. 기존 테이블·데이터 초기화는 하지 않는다.
- 로그인 사용자는 현재 ACTIVE 상태와 user_id, 비회원은 세션의 무작위 32바이트 토큰 SHA-256 해시로 매 조회 시 소유권을 검사한다. 토큰 원문은 DB·URL에 저장하지 않는다. UUID만 알아서는 조회할 수 없다.
- 확정 토큰과 로그인 주체별 중복 제출은 현재 세션에서 동일 jobId를 반환한다. 실패 후 명시적 재조회는 새 작업이다. 서로 다른 세션 간 중복은 별도 요청으로 처리한다.
- JSON POST 응답은 DB 커밋 후 202+Location+jobId, JavaScript 없는 HTML 폼은 303으로 GET 화면에 이동한다. 상태 조회는 2초 간격이며 준비·실패 시 멈춘다. API에는 원문·소유자·토큰 해시를 반환하지 않는다.
- 현재 실행기는 단일 서버에서 순차 실행한다(동시 1개, 전체 상한 5개 이하). DB 조회 트랜잭션 제한 15초, 큐 대기 60초 초과는 실패 처리한다. 앱 시작 시 실행 중 단계는 실패로 복구하고 QUEUED는 다시 확인한다. 준비된 스냅샷은 유지한다. 향후 외부 API·GPT 연결 시 전체 실행 제한과 최대 5개 동시 처리 정책을 확장 검증한다.
- 임시 조회 만료 기본 24시간, 설정 `travel.jobs.retention-hours`(1~168시간)이다. 만료 작업은 소유자도 조회 불가하고 비회원 작업은 1분 주기 및 시작 시 삭제한다. 회원 추천 이력은 유지한다. 세션이 먼저 끝나면 비회원 작업에 접근할 수 없다.
- `row_version`은 SQL Server 생성값으로 읽고 상태 변경은 행 잠금으로 보호한다. 단위 테스트에서는 자동 실행기를 끄고 작업 처리를 명시적으로 실행한다. 운영은 하나의 앱 인스턴스를 기준으로 한다.
- 배포 SQL: [004_create_recommendation_jobs.sql](sql/004_create_recommendation_jobs.sql), [005_seed_destinations.sql](sql/005_seed_destinations.sql). 004는 대상 테이블 중 하나라도 있으면 중단한다. 005는 코드가 없는 도시만 추가하고 재실행 시 기존 데이터와 검증 시각을 덮어쓰지 않는다.

### 6.2 GPT 추천·결과 저장 구현 기준 (2026-09-12)

- [006_create_recommendation_results.sql](sql/006_create_recommendation_results.sql)로 5절 테이블과 제약을 추가하고 J1에 RECOMMENDATION_QUEUED·RANKING_CANDIDATES를 허용한다. 기존 데이터·컬럼·FK를 변경하거나 초기화하지 않는다. 006은 대상 결과 테이블이 이미 있으면 중단한다.
- CANDIDATES_READY 화면의 명시적 POST → RECOMMENDATION_QUEUED → RANKING_CANDIDATES → COMPLETED 또는 FAILED이다. 후보 조회만으로 유료 요청을 시작하지 않는다. 상태 조회는 두 새 상태에서도 계속 폴링한다.
- 유료 호출 전에 짧은 트랜잭션에서 행 잠금과 시도 번호 증가를 커밋한다. 외부 요청은 트랜잭션 밖에서 수행하고 완료 시 새 트랜잭션으로 동일 작업·시도 번호·상태·소유자의 ACTIVE 여부·만료·후보 활성 상태를 재검사한다. 늦은 응답은 새 시도를 덮어쓰지 않는다.
- 같은 작업 최대 3회, 실패 후 수동 재시도만 허용한다. 제출한 이전 시도 번호가 현재와 다르면 상태만 반환한다. 중복·완료 후 POST는 추가 API 호출이나 후보 중복 저장을 만들지 않는다.
- API 요청은 연결 제한 5초, 응답 제한 25초, 출력 상한 1,800토큰이다. 추천 큐에 넣은 started_at부터 60초를 넘기면 실행·완료를 거부한다. 단일 실행기는 순차 처리하며 부하 상황의 95% 20초 응답 목표를 보장한 것은 아니다.
- 후보 입력은 지역명·소개와 확정 조건으로 제한한다. 원문·계정·토큰·키워드 ID는 AI에 전송하지 않는다. 모델은 기존 OPENAI_MODEL 설정을 공유하며 기본 gpt-6-luna이다. Structured Outputs에 허용 ID와 정확한 배열 길이를 넣고 서버에서 다시 ID·개수·중복·이유 형식·길이를 검사한다.
- result_json은 준비·실패 상태에서 후보 배열, COMPLETED에서는 순위·지역 스냅샷·이유 배열이다. 실패한 AI 원문은 저장하지 않는다. 상태를 기준으로 역직렬화한다. 회원은 recommendation_candidates 저장과 작업 완료가 같은 트랜잭션이며 비회원은 임시 작업 결과만 저장한다.
- source_url·지역명·확인일은 GPT 출력이 아니라 서버의 검증된 스냅샷을 사용한다. AI 이유의 사실성·적합성을 완전히 보장하는 검증은 아니므로 화면에 AI 작성과 실제 비용·운영·교통 정보의 미검증 한계를 안내한다. 상세 일정 생성과 회원 추천 이력 목록 UI는 아직 없다.

7 검증된 장소 공통 정보

| 테이블명 | 한글명 | 설명 |
| --- | --- | --- |
| places | 검증된 장소 공통 정보 | 외부 관광정보·지도정보 또는 시스템 조회·동기화로 확인한 여행지·관광지·숙소·음식점을 공통 형식으로 저장·캐시한다. |

관련 요구사항 및 구현 범위  DAR-03·04·10 / R1·R2

| 컬럼명 | 자료형 | NULL | PK | FK | UNIQUE | DEFAULT | CHECK | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| place_id | BIGINT | N | Y | - | - | - | - | IDENTITY(1,1) 자동 증가 |
| place_type | VARCHAR(20) | N | - | - | - | - | DESTINATION, ATTRACTION, ACCOMMODATION, RESTAURANT | - |
| source_provider | VARCHAR(40) | N | - | - | U1 | - | - | 외부 제공자 또는 SYSTEM |
| source_place_id | NVARCHAR(150) | N | - | - | U1 | - | - | 제공자 내 고유 ID |
| place_name | NVARCHAR(200) | N | - | - | - | - | - | - |
| region | NVARCHAR(100) | N | - | - | - | - | - | - |
| address | NVARCHAR(300) | Y | - | - | - | - | - | - |
| latitude | DECIMAL(9,6) | Y | - | - | - | - | BETWEEN -90 AND 90 | - |
| longitude | DECIMAL(9,6) | Y | - | - | - | - | BETWEEN -180 AND 180 | - |
| subtype | NVARCHAR(80) | Y | - | - | - | - | - | 문화·여가·체험·숙소 세부유형 |
| description | NVARCHAR(2000) | Y | - | - | - | - | - | - |
| details_json | NVARCHAR(MAX) | Y | - | - | - | - | JSON | 테마, 운영시간, 휴무, 음식명·분류, 숙소유형 등 |
| source_url | NVARCHAR(1000) | Y | - | - | - | - | - | - |
| map_url | NVARCHAR(1000) | Y | - | - | - | - | - | - |
| is_active | BIT | N | - | - | - | 1 | - | - |
| verified_at | DATETIME2(3) | N | - | - | - | - | - | 출처에서 장소 정보를 마지막으로 확인한 시각 |
| created_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 생성 시각 |
| updated_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 최종 수정 시각 |

제약과 처리 규칙

U1(source_provider, source_place_id). 좌표는 둘 다 NULL 또는 둘 다 입력한다. 물리적 장소와 지역 단위 DESTINATION을 구분한다.

관리자는 여행지·관광지·숙소·음식점을 직접 등록·수정·삭제하지 않으며 관리자 직접 CRUD API를 제공하지 않는다. 수집·동기화 서비스만 쓰기 권한을 가진다. 참조 중인 장소는 삭제보다 비활성화를 우선한다.

details_json의 foods[]는 음식명·분류·설명·출처를 보관한다. 실제 음식점이 없으면 일정의 음식 안내 스냅샷으로 대체하고 가짜 RESTAURANT 행을 생성하지 않는다.

2026-09-20 운영정보 연결: 기존 `details_json`에 선택 객체 `tourApiOperating`을 사용한다. 구조는 `{name, address, info:{contentId, contentTypeId, hours, closedDays, checkIn, checkOut, parking, fetchedAt}}`이다. parking은 2026-10-12에 추가한 주차 가능 여부·요금 안내 원문이며, 그 전에 저장된 운영정보에는 없을 수 있다(없으면 정보 없음으로 본다). name/address는 연결 당시 내부 장소 이름·주소, info는 한국관광공사 소개정보 원문과 UTC 조회 시각이다. fetchedAt은 제공자 정보 수정일이나 여행 당일 운영 보장이 아니다. 테이블·컬럼·관계 추가는 없으며 다른 JSON 필드는 보존한다. 운영정보 갱신은 updated_at만 갱신하고 장소 존재 확인 verified_at은 바꾸지 않는다.

2026-09-22 참고 가격 보관: 기존 `details_json`에 선택 객체 `referencePrice`를 사용한다(테이블·컬럼 변경 없음). 구조는 `{name, address, quote:{placeId, name, kind, status, priceText, minWon, maxWon, basis, sourceUrl, checkedAt}}`이다. 일정 초안의 '참고 가격 찾기'가 AI 웹 검색으로 새로 찾은 결과를 장소별로 보관하고, 다른 초안에서 같은 장소를 찾을 때 이름·주소·종류(숙소/음식점)가 같고 검색 후 7일(찾지 못한 결과는 24시간) 안이면 다시 검색하지 않는다. 다른 키(`tourApiOperating` 등)는 보존하며, 운영정보와 가격 저장은 모두 장소 행을 잠그고 쓴다.

2026-09-24 장소 사진 보관: 기존 `details_json`에 선택 객체 `tourApiPhoto`를 사용한다(테이블·컬럼 변경 없음). 구조는 `{name, address, photo:{url, copyright, checkedAt}}`이다. 일정 초안을 만들 때 일정에 들어간 관광지를 한국관광공사 키워드 검색으로 찾아, 이름(제목의 괄호 설명·앞 지역명 제외)이 같고 좌표 300m 안(좌표가 없으면 도로명 주소 일치)인 곳이 한 곳일 때 대표 사진 주소와 저작권 유형(Type1·Type3만)을 저장한다. 찾지 못하면 url 없이 기록한다. 찾은 사진은 30일, 못 찾은 기록은 7일 재사용하며 이름·주소가 바뀌면 쓰지 않는다. 화면에는 '사진: 한국관광공사' 출처를 함께 표시한다.

2026-10-30 대표 관광지 목록·후보 순서: 여행지 행(`place_type='DESTINATION'`)의 기존 `details_json`에 선택 객체 `landmarks`를 사용한다(테이블·컬럼 변경 없음). 구조는 `{name, names[], popular, checkedAt}`이다. names는 한국관광공사 '관광지별 연관 관광지 정보'(티맵 내비게이션 이동 데이터)로 정한 인기 관광지 순서(최대 20곳)에 GPT가 제안한 대표 관광지 이름을 이어 붙인 최대 30개 검색어이며, 화면에 그대로 보이지 않고 카카오에서 그 지역의 실제 장소로 확인된 곳만 관광지(KAKAO) 행으로 저장된다. popular는 연관 관광지를 받았는지이며, true면 30일, false면 1일 재사용한다. name(만들 당시 여행지 이름)이 바뀌면 쓰지 않는다. 관광지(KAKAO) 행의 `details_json.kakaoRank`(후보 순서, 0부터)는 이 대표 관광지 순서를 먼저, "○○ 명소" 검색·사찰·성당 순서를 그 뒤에 기록한다.

조회 후 24시간, 동일한 이름·주소에만 운영정보를 재사용한다. 기존 필드 없음·읽기 실패는 미확인이다. ai_jobs의 일정 request_json 내 PlaceCandidate에 nullable operatingInfo를 추가하며 이전 작업 JSON의 누락 필드는 null로 읽는다. 완료 직전에 DB 운영정보와 입력 스냅샷이 다른 경우 PLACES_CHANGED로 실패시킨다. 원문·확인 범위는 결과 항목 note에 포함하며 해석 불가 예외를 검증 완료로 표시하지 않는다. 상세 기준은 [TourAPI 설정·연결](../ai/TourAPI_설정.md)을 따른다.

### 7.1 지역 내 장소 조회 (2026-09-18)

요구분석서 3.1의 SFR-03-06·SFR-04-02와 대조하여 추천 지역 선택과 관광지·숙소·음식점 조회를 연결했다. [007 SQL](sql/007_seed_local_places.sql)은 강릉·부산·경주의 실제 장소 12곳을 SYSTEM 식별자로 추가하며 재실행 시 기존 행을 변경하지 않는다. 지역 단위 DESTINATION과 물리 장소를 구분하고 기존 `region`의 정확한 일치로 조회한다. 열·관계·제약 변경은 없다.

확인 범위는 이름·주소·유형·소개와 출처이며 좌표·가격·영업시간은 저장하지 않았다. 선택 지역은 조회 URL로 전달하며 회원 추천 이력의 조건을 덮어쓰거나 별도 선택 저장 열을 만들지 않는다. 일정 생성 시의 입력 스냅샷은 후속 단계다. 출처·권한·화면·숙박 제외 기준은 [지역별 장소 조회](../ai/지역별_장소_조회.md)를 따른다. 이 대조는 요구분석서 3.1 전체의 DB 정합성 확인을 의미하지 않는다.

8 저장 여행 일정

| 테이블명 | 한글명 | 설명 |
| --- | --- | --- |
| itineraries | 저장 여행 일정 | 회원이 저장한 일정의 기본정보와 원래 생성 조건을 보관한다. |

관련 요구사항 및 구현 범위  DAR-05 / R2

| 컬럼명 | 자료형 | NULL | PK | FK | UNIQUE | DEFAULT | CHECK | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| itinerary_id | BIGINT | N | Y | - | - | - | - | IDENTITY(1,1) 자동 증가 |
| user_id | BIGINT | N | - | users.user_id | - | - | - | 참조 식별자 |
| request_id | BIGINT | Y | - | recommendation_requests.request_id | - | - | - | 참조 식별자 |
| destination_place_id | BIGINT | N | - | places.place_id | - | - | - | 참조 식별자 |
| title | NVARCHAR(150) | N | - | - | - | - | - | - |
| start_date | DATE | N | - | - | - | - | - | - |
| end_date | DATE | N | - | - | - | - | - | - |
| transportation | VARCHAR(20) | N | - | - | - | - | CAR, TRANSIT, WALK, MIXED, ANY | - |
| conditions_snapshot_json | NVARCHAR(MAX) | N | - | - | - | - | JSON | 저장 당시 조건과 지역 요약 |
| created_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 생성 시각 |
| updated_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 최종 수정 시각 |
| row_version | ROWVERSION | N | - | - | - | - | - | 수정 충돌 검출 |

제약과 처리 규칙

CHECK end_date >= start_date. destination_place_id의 유형은 DESTINATION으로 검사한다. request_id가 있으면 본인 이력인지 검증한다.

날짜 미확정 생성 결과는 ai_jobs.result_json에 임시 보관한다. 날짜 확정 후 저장한다. 일정 삭제는 상세 항목을 같은 트랜잭션에서 제거한다.

9 세부 일정 항목

| 테이블명 | 한글명 | 설명 |
| --- | --- | --- |
| itinerary_items | 세부 일정 항목 | 관광·식사·이동·숙박·자유 시간을 시간 순서로 저장한다. |

관련 요구사항 및 구현 범위  DAR-06·10 / R2

| 컬럼명 | 자료형 | NULL | PK | FK | UNIQUE | DEFAULT | CHECK | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| item_id | BIGINT | N | Y | - | - | - | - | IDENTITY(1,1) 자동 증가 |
| itinerary_id | BIGINT | N | - | itineraries.itinerary_id | U1 | - | - | 참조 식별자 |
| day_no | SMALLINT | N | - | - | U1 | - | > 0 | - |
| sequence_no | SMALLINT | N | - | - | U1 | - | > 0 | - |
| item_type | VARCHAR(20) | N | - | - | - | - | VISIT, MEAL, TRANSIT, STAY, FREE | - |
| place_id | BIGINT | Y | - | places.place_id | - | - | - | 참조 식별자 |
| start_at | DATETIME2(3) | N | - | - | - | - | - | 방문 시작 현지 시각 KST |
| end_at | DATETIME2(3) | N | - | - | - | - | - | 방문 종료 현지 시각 KST |
| activity | NVARCHAR(1000) | N | - | - | - | - | - | - |
| travel_minutes | INT | Y | - | - | - | - | >= 0 | 직전 항목에서 이동 예상 분 |
| place_snapshot_json | NVARCHAR(MAX) | Y | - | - | - | - | JSON | 장소명·위치·추천 근거 |
| transport_json | NVARCHAR(MAX) | Y | - | - | - | - | JSON | 이동수단·출발 및 도착 터미널·출도착 시각·소요 시간·출처·조회 기준일·참고 정보 여부 |
| food_guide_json | NVARCHAR(MAX) | Y | - | - | - | - | JSON | 음식명·분류·설명·지도 검색 링크 |
| created_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 생성 시각 |
| updated_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 최종 수정 시각 |

제약과 처리 규칙

U1(itinerary_id, day_no, sequence_no). CHECK end_at > start_at. 날짜 범위, day_no 일치, 시간 중복, 이동 가능성, 운영시간·휴무는 일정 전체 검증기로 확인한다.

VISIT·STAY는 place_id 필수이다. MEAL은 실제 음식점이 확인되면 places의 RESTAURANT를 참조한다. 음식점이 없거나 지역 대표 음식·간편 음식 유형만 안내하면 food_guide_json에 스냅샷을 저장하고 임의의 음식점 행을 만들지 않는다. FREE·TRANSIT도 place_id NULL을 허용한다. 체류 시간은 시작·종료 차이로 계산한다.

교통 정보는 itinerary_items.transport_json에 저장하고 예상 이동 시간은 필요 시 travel_minutes를 사용한다. 미래 일정의 시외버스 정보에는 조회 기준일, 참고 정보 여부, 출발·도착 터미널과 소요 시간을 포함한다. 종료일 다음날 00시까지의 마지막 구간은 허용한다.

10 장소 즐겨찾기

| 테이블명 | 한글명 | 설명 |
| --- | --- | --- |
| favorites | 장소 즐겨찾기 | 회원별 여행지·관광지·숙소 즐겨찾기만 저장한다. |

관련 요구사항 및 구현 범위  DAR-14 / R2

| 컬럼명 | 자료형 | NULL | PK | FK | UNIQUE | DEFAULT | CHECK | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| favorite_id | BIGINT | N | Y | - | - | - | - | IDENTITY(1,1) 자동 증가 |
| user_id | BIGINT | N | - | users.user_id | U1 | - | - | 참조 식별자 |
| place_id | BIGINT | N | - | places.place_id | U1 | - | - | 참조 식별자 |
| created_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 생성 시각 |

제약과 처리 규칙

U1(user_id, place_id)로 중복 등록을 차단한다.

허용 유형은 DESTINATION, ATTRACTION, ACCOMMODATION이다. RESTAURANT와 일정은 등록할 수 없다. 타 테이블 유형 제한은 등록 서비스와 DB 트리거에서 검증하며 places.place_type 변경도 동일 검증한다.

11 장소 별점 평가

| 테이블명 | 한글명 | 설명 |
| --- | --- | --- |
| place_ratings | 장소 별점 평가 | 회원당 장소별 한 건의 별점 평가와 선택 입력 한줄평을 저장한다. |

관련 요구사항 및 구현 범위  DAR-08 / R2

| 컬럼명 | 자료형 | NULL | PK | FK | UNIQUE | DEFAULT | CHECK | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| rating_id | BIGINT | N | Y | - | - | - | - | IDENTITY(1,1) 자동 증가 |
| user_id | BIGINT | N | - | users.user_id | U1 | - | - | 참조 식별자 |
| place_id | BIGINT | N | - | places.place_id | U1 | - | - | 참조 식별자 |
| score | TINYINT | N | - | - | - | - | BETWEEN 1 AND 5 | - |
| comment | NVARCHAR(200) | Y | - | - | - | - | - | 한줄평(선택, SQL 018). 화면 최대 100자, 앞뒤 공백 제거, 비우면 NULL |
| created_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 생성 시각 |
| updated_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 최종 수정 시각 |

제약과 처리 규칙

U1(user_id, place_id). 기존 평가가 있으면 새 행 대신 갱신한다(별점·태그·한줄평 모두 새 입력으로 바뀐다). 태그 0개, 한줄평 없음도 유효하다.

제목·본문·공개 상태·관리자 검수 컬럼을 두지 않는다. 한줄평은 한 줄 문장만 두는 선택 입력이다. 회원 본인만 변경·삭제하고 관리자는 자신의 평가에만 일반 회원 권한을 사용한다. 관리자 한줄평 삭제 기능은 없고 부적절한 한줄평은 회원 이용 제한(users.status)으로 대응한다.

다른 회원에게는 장소별 최근 평가 최대 5건(updated_at 내림차순)의 별점·태그·한줄평·수정 시각을 보여 주며, 평가자는 users.nickname의 앞 글자만 남기고 가린다(예: 김**). 로그인 ID와 전체 닉네임은 보여 주지 않는다.

평균은 AVG(CAST(score AS DECIMAL(5,2))), 평가 수는 COUNT로 조회한다. 태그 조인 전에 별점을 집계해 중복 계산을 방지한다.

12 평가 태그 사전

| 테이블명 | 한글명 | 설명 |
| --- | --- | --- |
| rating_tags | 평가 태그 사전 | 사전 정의된 평가 태그의 허용값을 보관한다. |

관련 요구사항 및 구현 범위  DAR-08 / R2

| 컬럼명 | 자료형 | NULL | PK | FK | UNIQUE | DEFAULT | CHECK | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| tag_id | BIGINT | N | Y | - | - | - | - | IDENTITY(1,1) 자동 증가 |
| tag_code | VARCHAR(40) | N | - | - | U1 | - | - | - |
| tag_name | NVARCHAR(80) | N | - | - | U2 | - | - | - |
| sort_order | INT | N | - | - | - | 0 | >= 0 | - |
| is_active | BIT | N | - | - | - | 1 | - | - |
| created_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 생성 시각 |
| updated_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 최종 수정 시각 |

제약과 처리 규칙

U1(tag_code), U2(tag_name). 시스템 초기 데이터 또는 배포로 관리하며 별도 관리자 태그 CRUD 기능을 추가하지 않는다.

신규 선택은 활성 태그만 허용한다. 비활성 태그는 과거 선택 표시·집계에 남기고 참조 중인 태그의 삭제를 제한한다.

13 평가 태그 연결

| 테이블명 | 한글명 | 설명 |
| --- | --- | --- |
| place_rating_tags | 평가 태그 연결 | 장소 평가와 태그의 N 대 M 관계를 유지하며 최대 3개 선택을 제한한다. |

관련 요구사항 및 구현 범위  DAR-08 / R2

| 컬럼명 | 자료형 | NULL | PK | FK | UNIQUE | DEFAULT | CHECK | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| rating_id | BIGINT | N | P1 | place_ratings.rating_id | U1 | - | - | - |
| tag_id | BIGINT | N | P1 | rating_tags.tag_id | - | - | - | - |
| slot_no | TINYINT | N | - | - | U1 | - | BETWEEN 1 AND 3 | 선택 슬롯 1~3 |

제약과 처리 규칙

P1 복합 PK(rating_id, tag_id), U1(rating_id, slot_no). 슬롯 CHECK와 UNIQUE를 함께 적용하면 평가별 최대 3행과 중복 태그 금지가 DB에서 보장된다. 연결 생성 시각은 사용하지 않으므로 별도 created_at을 저장하지 않고 평가 변경 시각은 place_ratings.updated_at으로 관리한다.

별점 갱신 및 태그 교체는 부모 평가 행을 잠그고 한 트랜잭션에서 처리한다. 태그 빈도는 해당 place_id의 평가와 연결 행을 조인하여 집계한다.

14 운영 처리 이력

| 테이블명 | 한글명 | 설명 |
| --- | --- | --- |
| operation_logs | 운영 처리 이력 | 회원 상태 변경과 예약·결제 상태 전이 실패를 1년간 기록한다. |

관련 요구사항 및 구현 범위  DAR-13 / 회원 관리 및 예약·결제 실패 기록

| 컬럼명 | 자료형 | NULL | PK | FK | UNIQUE | DEFAULT | CHECK | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| log_id | BIGINT | N | Y | - | - | - | - | IDENTITY(1,1) 자동 증가 |
| actor_user_id | BIGINT | Y | - | users.user_id | - | - | - | 참조 식별자 |
| actor_kind | VARCHAR(10) | N | - | - | - | - | ADMIN, MEMBER, SYSTEM | - |
| target_type | VARCHAR(20) | N | - | - | - | - | USER, RESERVATION, PAYMENT | - |
| target_id | BIGINT | N | - | - | - | - | - | 논리 참조 식별자, FK 없음 |
| action_code | VARCHAR(50) | N | - | - | - | - | - | - |
| before_status | VARCHAR(32) | Y | - | - | - | - | - | - |
| after_status | VARCHAR(32) | Y | - | - | - | - | - | - |
| reason | NVARCHAR(1000) | N | - | - | - | - | - | - |
| outcome | VARCHAR(10) | N | - | - | - | - | SUCCESS, FAILED | - |
| correlation_id | UNIQUEIDENTIFIER | Y | - | - | - | - | - | 실패 요청 추적 ID |
| created_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 생성 시각 |

제약과 처리 규칙

대상 데이터가 탈퇴로 삭제되어도 로그는 보존해야 하므로 target_id에는 FK를 걸지 않는다. 이메일·이름·입력 원문·금융정보는 기록하지 않는다.

성공한 회원 상태 변경은 원 변경과 같은 트랜잭션에 기록한다. 실패 로그는 본 트랜잭션 롤백 이후 별도 트랜잭션으로 기록한다. 시스템 실패는 actor_user_id NULL을 허용한다.

관리자에게 예약·결제 상태 변경 권한을 부여하지 않으며 별도 예약·결제 운영 화면도 제공하지 않는다.

15 가상 예약 가능 수량

| 테이블명 | 한글명 | 설명 |
| --- | --- | --- |
| reservation_stock | 가상 예약 가능 수량 | 숙소·유료 관광시설의 이용 구간별 가상 수량을 관리한다. |

관련 요구사항 및 구현 범위  DAR-11 / 가상 예약·결제

| 컬럼명 | 자료형 | NULL | PK | FK | UNIQUE | DEFAULT | CHECK | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stock_id | BIGINT | N | Y | - | - | - | - | IDENTITY(1,1) 자동 증가 |
| place_id | BIGINT | N | - | places.place_id | U1 | - | - | 참조 식별자 |
| usage_start_at | DATETIME2(3) | N | - | - | U1 | - | - | - |
| usage_end_at | DATETIME2(3) | N | - | - | U1 | - | - | - |
| total_quantity | INT | N | - | - | - | - | >= 0 | - |
| remaining_quantity | INT | N | - | - | - | - | 0 이상 total_quantity 이하 | - |
| unit_price | DECIMAL(12,2) | N | - | - | - | - | >= 0 | 가상 단위 가격 KRW |
| quantity_unit | VARCHAR(10) | N | - | - | - | - | ROOM, PERSON | 숙소 객실 또는 시설 인원 |
| room_type | VARCHAR(20) | Y | - | - | U1 | - | SINGLE, STANDARD_DOUBLE, STANDARD_TWIN, DELUXE_DOUBLE, FAMILY, SUITE (ROOM일 때만) | 체험용 객실 종류. 시설(PERSON)은 NULL (2026-10-05 추가, SQL 016) |
| is_active | BIT | N | - | - | - | 1 | - | - |
| created_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 생성 시각 |
| updated_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 최종 수정 시각 |
| row_version | ROWVERSION | N | - | - | - | - | - | - |

제약과 처리 규칙

U1(place_id, usage_start_at, usage_end_at, room_type). CHECK usage_end_at > usage_start_at. 대상 유형·유료 시설 여부는 시스템 데이터로 검증한다.

시드 수량(숙소 3곳·관광시설 2곳) 외에, 회원이 저장 일정이나 예약 화면을 열 때 서버가 그 숙소·날짜의 객실 종류별 행과 입장권을 끊는 분류의 관광시설 행을 만든다(2026-10-05, 1.15절·[기능 문서](../ai/가상_예약_결제.md)). 관리자 직접 장소 CRUD 또는 실제 재고 연동은 제공하지 않는다. 하나의 숙소 재고 행은 객실 종류 하나의 고정 숙박 패키지 구간(1박)으로 정의하고 같은 객실 종류의 겹치는 구간을 생성하지 않는다.

16 가상 예약

| 테이블명 | 한글명 | 설명 |
| --- | --- | --- |
| virtual_reservations | 가상 예약 | 회원의 가상 예약과 수량 점유 상태를 저장한다. |

관련 요구사항 및 구현 범위  DAR-11 / 가상 예약·결제

| 컬럼명 | 자료형 | NULL | PK | FK | UNIQUE | DEFAULT | CHECK | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| reservation_id | BIGINT | N | Y | - | - | - | - | IDENTITY(1,1) 자동 증가 |
| user_id | BIGINT | N | - | users.user_id | - | - | - | 참조 식별자 |
| stock_id | BIGINT | N | - | reservation_stock.stock_id | - | - | - | 참조 식별자 |
| itinerary_id | BIGINT | Y | - | itineraries.itinerary_id | - | - | - | 참조 식별자 |
| request_key | UNIQUEIDENTIFIER | N | - | - | U1 | - | - | 예약 생성 중복 방지 키 |
| quantity | INT | N | - | - | - | - | > 0 | 점유 수량 |
| party_size | INT | N | - | - | - | - | > 0 | 이용 인원 |
| expected_amount | DECIMAL(12,2) | N | - | - | - | - | >= 0 | - |
| status | VARCHAR(12) | N | - | - | - | 'PENDING' | PENDING, CONFIRMED, CANCELED | - |
| payment_required | BIT | N | - | - | - | 1 | - | - |
| booking_snapshot_json | NVARCHAR(MAX) | N | - | - | - | - | JSON | 장소·이용일시·단위가격 스냅샷 |
| created_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 생성 시각 |
| updated_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 최종 수정 시각 |
| canceled_at | DATETIME2(3) | Y | - | - | - | - | - | 가상 예약 취소 시각 |
| row_version | ROWVERSION | N | - | - | - | - | - | - |

제약과 처리 규칙

U1(request_key). 예약번호는 reservation_id를 사용한다. 사용자가 준 금액을 신뢰하지 않고 quantity × unit_price로 서버에서 산출한다.

CHECK: CANCELED일 때만 canceled_at 존재. 날짜·시간은 stock_id와 스냅샷으로 보관한다. 변경 시 재고 참조·스냅샷·금액을 함께 갱신한다.

PENDING은 이미 수량을 점유한다. 결제가 없으면 생성 트랜잭션 안에서 CONFIRMED로 확정한다. 회원·일정 소유권과 재고 단위에 맞는 인원·수량을 검증한다.

17 가상 결제

| 테이블명 | 한글명 | 설명 |
| --- | --- | --- |
| virtual_payments | 가상 결제 | 멱등 요청 단위의 가상 결제 성공·실패·환불 상태를 보관한다. |

관련 요구사항 및 구현 범위  DAR-12 / 가상 예약·결제

| 컬럼명 | 자료형 | NULL | PK | FK | UNIQUE | DEFAULT | CHECK | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| payment_id | BIGINT | N | Y | - | - | - | - | IDENTITY(1,1) 자동 증가 |
| reservation_id | BIGINT | N | - | virtual_reservations.reservation_id | UF1 | - | - | 참조 식별자 |
| idempotency_key | UNIQUEIDENTIFIER | N | - | - | U1 | - | - | - |
| request_hash | BINARY(32) | N | - | - | - | - | - | 예약·금액·수단 요청 내용 해시 |
| amount | DECIMAL(12,2) | N | - | - | - | - | >= 0 | - |
| status | VARCHAR(12) | N | - | - | - | 'READY' | READY, SUCCESS, FAILED, REFUNDED | - |
| failure_code | VARCHAR(60) | Y | - | - | - | - | - | - |
| created_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 생성 시각 |
| processed_at | DATETIME2(3) | Y | - | - | - | - | - | 결제 성공 또는 실패 처리 시각 |
| refunded_at | DATETIME2(3) | Y | - | - | - | - | - | 가상 환불 처리 시각 |

제약과 처리 규칙

U1(idempotency_key). UF1은 reservation_id에 대한 필터 UNIQUE INDEX WHERE status = 'SUCCESS'이다. 다른 멱등키로 동시에 요청해도 성공 결제는 예약당 한 건만 허용한다.

동일 키·동일 request_hash는 최초 결과 반환, 동일 키·다른 내용은 거부한다. FAILED 재시도는 새 키를 발급한다. 환불 예약은 CANCELED이므로 새 결제를 허용하지 않는다. 결제수단은 TEST로 고정하므로 test_method 컬럼 없이 서버 상수로 처리하며 요청 해시의 수단 값에도 같은 상수를 사용한다.

CHECK: READY이면 processed_at NULL, 그 외는 필수. REFUNDED일 때만 refunded_at 존재. FAILED는 failure_code 필수. 취소는 SUCCESS → REFUNDED로 표현한다. 실제 금융정보 컬럼은 없다.

18 예약 수량 변경 이력

| 테이블명 | 한글명 | 설명 |
| --- | --- | --- |
| reservation_logs | 예약 수량 변경 이력 | 커밋된 예약 생성·변경·취소와 재고 증감을 함께 기록한다. |

관련 요구사항 및 구현 범위  DAR-11 / 가상 예약·결제

| 컬럼명 | 자료형 | NULL | PK | FK | UNIQUE | DEFAULT | CHECK | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| reservation_log_id | BIGINT | N | Y | - | - | - | - | IDENTITY(1,1) 자동 증가 |
| reservation_id | BIGINT | N | - | virtual_reservations.reservation_id | - | - | - | 참조 식별자 |
| stock_id | BIGINT | N | - | reservation_stock.stock_id | - | - | - | 참조 식별자 |
| operation_id | UNIQUEIDENTIFIER | N | - | - | - | - | - | 동일 변경의 로그 묶음 |
| event_type | VARCHAR(20) | N | - | - | - | - | CREATE, CHANGE, CANCEL | - |
| before_quantity | INT | N | - | - | - | - | >= 0 | 변경 전 잔여 수량 |
| delta_quantity | INT | N | - | - | - | - | - | 재고 증감값, 차감은 음수 |
| after_quantity | INT | N | - | - | - | - | >= 0 | 변경 후 잔여 수량 |
| before_status | VARCHAR(12) | Y | - | - | - | - | - | - |
| after_status | VARCHAR(12) | N | - | - | - | - | PENDING, CONFIRMED, CANCELED | - |
| reason | NVARCHAR(500) | N | - | - | - | - | - | - |
| created_at | DATETIME2(3) | N | - | - | - | KST_NOW | - | 생성 시각 |

제약과 처리 규칙

CHECK after_quantity = before_quantity + delta_quantity. before_status가 존재하면 PENDING, CONFIRMED, CANCELED 중 하나이다.

성공 변경과 같은 트랜잭션에서 기록한다. 날짜 변경은 구 재고 복구·신 재고 차감을 같은 operation_id의 두 행으로 기록한다. 롤백 실패 기록은 operation_logs를 사용한다.

테이블 간 관계 요약

| 부모 테이블 및 키 | 자식 테이블 및 FK | 관계 | 참조 성격 |
| --- | --- | --- | --- |
| keyword_categories.category_id | keywords.category_id | 1 : 0..N | 필수 참조 |
| users.user_id | recommendation_requests.user_id | 1 : 0..N | 필수 참조 |
| recommendation_requests.request_id | recommendation_candidates.request_id | 1 : 0..N | 필수 참조 |
| places.place_id | recommendation_candidates.place_id | 1 : 0..N | 필수 참조 |
| users.user_id | ai_jobs.user_id | 1 : 0..N | 선택 참조 |
| recommendation_requests.request_id | ai_jobs.request_id | 1 : 0..N | 선택 참조 |
| users.user_id | itineraries.user_id | 1 : 0..N | 필수 참조 |
| recommendation_requests.request_id | itineraries.request_id | 1 : 0..N | 선택 참조 |
| places.place_id | itineraries.destination_place_id | 1 : 0..N | 필수 참조 |
| itineraries.itinerary_id | itinerary_items.itinerary_id | 1 : 0..N | 필수 참조 |
| places.place_id | itinerary_items.place_id | 1 : 0..N | 선택 참조 |
| users.user_id | favorites.user_id | 1 : 0..N | 필수 참조 |
| places.place_id | favorites.place_id | 1 : 0..N | 필수 참조 |
| users.user_id | place_ratings.user_id | 1 : 0..N | 필수 참조 |
| places.place_id | place_ratings.place_id | 1 : 0..N | 필수 참조 |
| place_ratings.rating_id | place_rating_tags.rating_id | 1 : 0..N | 필수 참조 |
| rating_tags.tag_id | place_rating_tags.tag_id | 1 : 0..N | 필수 참조 |
| users.user_id | operation_logs.actor_user_id | 1 : 0..N | 선택 참조 |
| places.place_id | reservation_stock.place_id | 1 : 0..N | 필수 참조 |
| users.user_id | virtual_reservations.user_id | 1 : 0..N | 필수 참조 |
| reservation_stock.stock_id | virtual_reservations.stock_id | 1 : 0..N | 필수 참조 |
| itineraries.itinerary_id | virtual_reservations.itinerary_id | 1 : 0..N | 선택 참조 |
| virtual_reservations.reservation_id | virtual_payments.reservation_id | 1 : 0..N | 필수 참조 |
| virtual_reservations.reservation_id | reservation_logs.reservation_id | 1 : 0..N | 필수 참조 |
| reservation_stock.stock_id | reservation_logs.stock_id | 1 : 0..N | 필수 참조 |

관계는 부모 한 행에 연결 가능한 자식 수를 뜻한다. place_ratings와 rating_tags는 place_rating_tags를 통해 N:M이며 평가 한 건의 연결 수는 0~3이다. virtual_reservations와 virtual_payments는 전체 시도 기준 1:N, SUCCESS 행 기준 1:0..1이다.

삭제와 참조 유지

FK는 기본 ON DELETE NO ACTION / ON UPDATE NO ACTION으로 생성한다. 대규모 자동 CASCADE 대신 서비스 트랜잭션에서 자식부터 삭제하여 SQL Server 다중 경로 문제와 의도하지 않은 손실을 방지한다.

이력 삭제는 연결된 일정 및 작업의 request_id를 NULL로 바꾼 뒤 recommendation_candidates와 recommendation_requests를 삭제한다. 실행 중 추천 작업은 종료 또는 취소 처리를 먼저 확정하고 삭제하여 이력이 재생성되지 않게 한다. 저장 일정의 조건·장소 스냅샷은 유지한다.

일정 삭제는 virtual_reservations.itinerary_id를 NULL로 바꾸고 itinerary_items → itineraries 순으로 삭제한다. 평가 삭제는 place_rating_tags → place_ratings 순으로 처리한다. 장소·태그는 참조 중이면 비활성화한다.

설계 원칙

요구사항과 물리 설계의 구분

요구분석서의 동작과 범위는 유지한다. 컬럼 길이, JSON 스냅샷, 평가 슬롯, 인덱스, 재고 구간과 결제 완료 후 변경 제한은 본 정의서의 구현 설계 선택이다. R1은 강릉·부산·경주를 초기 데이터 범위로 사용하고 추가 지역도 같은 구조로 확장한다.

정규화와 검증된 장소

요청 1건과 후보 여러 건을 분리하고 사용자별 즐겨찾기·평가 중복을 UNIQUE로 차단한다. 키워드·장소의 과거 표시값은 스냅샷에 보존한다. JSON 내부 키워드 ID는 FK가 아닌 출처 식별자이며 신규 선택 시 존재·활성 여부를 검사한다. AI가 만든 미검증 장소는 places와 최종 일정에 넣지 않는다. 숙소와 음식점은 각각 places의 ACCOMMODATION과 RESTAURANT로 관리한다. 교통·음식 안내는 itinerary_items의 JSON을 사용하며 transport_info, accommodations, restaurants 같은 별도 테이블은 추가하지 않는다. recommendation_candidates.place_snapshot_json과 itinerary_items.place_snapshot_json은 당시 장소 정보를 보존한다.

소유권과 권한

개인 데이터 API는 모든 조회·수정·삭제마다 로그인 사용자와 소유자를 비교한다. jobId만으로 권한을 부여하지 않으며 비회원은 임시 토큰 해시도 확인한다. 관리자는 일반 회원으로서 자신의 데이터에만 접근하고 별도 운영 권한은 회원 상태 관리로 한정한다. 세션·로그아웃 처리는 Spring 세션 저장소에서 수행하며 별도 세션 테이블은 본 업무 스키마에 포함하지 않는다.

시간과 금액

생성·수정·작업 처리 시각은 한국 시각(KST)으로 저장하고 화면에도 그대로 보여 준다(2026-10-10부터. 그 전에는 UTC로 저장하고 화면에서 바꿨다). 여행·예약 이용 시각도 KST 현지 시각이다. JSON 안의 조회 시각(checkedAt 등)은 시간대가 붙은 절대 시각이라 그대로 둔다. 모든 금액은 KRW DECIMAL이며 FLOAT를 사용하지 않는다. ROWVERSION은 동시 수정 검출용 값이며 일시로 해석하지 않는다.

AI 작업 실행과 상태 전이

작업 생성은 DB에 QUEUED를 커밋한 후 3초 이내 202와 jobId를 반환한다. 추천은 QUEUED → ANALYZING_CONDITIONS → SEARCHING_CANDIDATES → COMPLETED로, 일정은 후보 검색 뒤 GENERATING_SCHEDULE → VALIDATING_SCHEDULE → COMPLETED로 처리한다. 검증 실패는 최대 2회 GENERATING_SCHEDULE로 되돌아가며 총 AI 생성 시도는 3회이다. 모든 비종료 단계는 FAILED로 종료할 수 있다.

AI 완료와 복구

추천 완료는 후보 저장과 COMPLETED 변경을 같은 트랜잭션으로 처리한다. 일정은 검증된 임시 결과를 result_json에 저장한 뒤 완료로 바꾸고 회원이 저장할 때 itineraries로 복사한다. 실행기 전체 동시 실행은 최대 5개로 제한한다. 단일 실행기 운영을 기준으로 서버 재시작 때 미종료 실행 작업을 FAILED와 복구 안내로 정리하며 QUEUED는 다시 실행한다. 다중 실행기 도입 시 전역 실행 슬롯과 lease가 필요하다.

AI 시간 제한과 비회원 데이터

최초 작업의 95%는 20초 이내, 재생성 포함 전체는 최대 60초 또는 설정 시간 안에 종료한다. 상태 조회는 2초 이내 응답하며 클라이언트는 기본 2초 간격으로 조회하고 종료 상태에서 멈춘다. expires_at은 별도의 임시 데이터 보존 만료이며 작업 실행 제한시간과 구분한다. 비회원 임시 보존은 운영 설정값으로 관리하고 계정 이력으로 장기 저장하지 않는다. 오류 로그에는 원문을 남기지 않는다.

탈퇴와 보존

탈퇴 시 즉시 로그인·기존 세션을 차단하고 실행 중 AI 작업이 다시 저장하지 못하게 한다. 일정·즐겨찾기·이력·가상 데이터·평가와 태그 연결·회원 작업을 삭제한다. 점유 중 가상 수량은 취소 절차로 한 번 복구한 뒤 로그·결제·예약을 삭제한다. 계정 식별정보와 비밀번호 해시는 30일 이내 익명화한다. login_id는 재사용 불가능한 고유 대체 문자열, nickname은 탈퇴회원, password_hash는 로그인 불가능한 무작위 값으로 교체한다.

운영 로그와 접근

users의 비식별 PK 행은 로그 참조용으로 유지하며 개인정보를 남기지 않는다. operation_logs는 1년 보존 후 삭제한다. 로그의 reason에는 최소한의 사유 코드·설명만 저장한다. API 인증키·비밀번호 원문·금융정보는 어떤 JSON이나 로그에도 저장하지 않는다.

가상 예약·결제 트랜잭션과 상태 전이

예약 생성은 remaining_quantity >= 요청 수량 조건을 포함한 UPDATE로 수량을 차감한다. 영향 행이 1일 때만 예약과 수량 로그를 생성하고 함께 커밋한다. 0이면 매진 처리한다. 같은 request_key 충돌로 실패한 차감도 롤백한 뒤 기존 예약을 반환한다.

예약은 PENDING → CONFIRMED, PENDING → CANCELED, CONFIRMED → CANCELED만 허용한다. 취소는 현재 상태를 조건으로 변경하고 성공한 한 요청만 수량을 복구한다. PENDING에서 결제 미사용이면 바로 CONFIRMED가 된다. CANCELED는 종료 상태이다.

결제는 READY → SUCCESS 또는 FAILED, SUCCESS → REFUNDED만 허용한다. FAILED·REFUNDED는 종료 상태이다. 예약 행 잠금과 현재 PENDING 상태 확인을 먼저 수행하여 다른 멱등키의 동시 결제도 직렬화한다. SUCCESS와 예약 CONFIRMED는 같은 트랜잭션으로 확정하고 예상된 실패는 FAILED·PENDING으로 커밋한다. 예기치 않은 예외는 전체 롤백한다.

결제된 예약 취소는 SUCCESS → REFUNDED, 예약 CANCELED, 재고 복구를 하나의 트랜잭션으로 처리한다. 환불 재요청은 기존 상태를 반환하고 다시 복구하지 않는다. READY만 있는 예약 취소 시 해당 시도는 FAILED와 RESERVATION_CANCELED 사유로 종료한다.

일시·인원 변경은 PENDING 또는 결제 미사용 CONFIRMED만 허용한다. 성공 결제가 있는 예약은 취소·환불 후 새 예약을 생성하도록 한다. 구 재고 복구, 신 재고 차감, 금액·스냅샷·로그 갱신을 한 트랜잭션으로 처리한다. 여러 재고를 잠글 때 stock_id 순서를 통일한다.

권장 인덱스와 구현 검증

PK·UNIQUE 외에 모든 FK에 조회용 인덱스를 검토한다. 주요 인덱스는 recommendation_requests(user_id, created_at DESC), itineraries(user_id, updated_at DESC), ai_jobs(status, created_at), ai_jobs(expires_at), ai_jobs(user_id, created_at), place_ratings(place_id) INCLUDE(score), place_rating_tags(tag_id, rating_id), operation_logs(created_at), virtual_reservations(user_id, created_at), reservation_logs(reservation_id, created_at)이다.

회원 간 접근 차단, 비회원 jobId 유출 시 접근 차단, AI 재시작·최대 3회·시간 제한, 후보 3~5개 검증, 즐겨찾기 음식점 차단, 0~3 태그 허용 및 4번째 태그 차단, 동일 장소 평가 갱신, 이력 삭제 후 일정 유지, 탈퇴와 로그 보존을 확인한다. 가상 예약·결제 검증 시 20개 병렬 요청에서 초과 예약·중복 성공 결제·부분 저장 0건을 검증한다.
