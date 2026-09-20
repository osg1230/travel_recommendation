# TourAPI 신청과 서버 설정

## 현재 구현 범위

2026-09-20: TourAPI를 운영정보 출처로 선택했다. 서버 설정과 `TourApiClient.findOperatingInfo(contentId, contentTypeId)`를 추가했다. 관광지(12)·문화시설(14)·레포츠(28)·숙소(32)·음식점(39)의 소개정보 원문을 조회한다. DB 변경은 없다.

아직 기존 카카오/SYSTEM 장소와 TourAPI 콘텐츠의 동일 장소 연결, DB 적재, 일정 검증·화면 연결은 하지 않았다. 키를 설정해도 현재 일정 화면에 영업시간이 자동 반영되지는 않는다. 실제 응답 표본 확인 뒤 이 순서로 이어간다.

## 1. 활용신청

1. [공공데이터포털의 한국관광공사 국문 관광정보 서비스_GW](https://www.data.go.kr/data/15101578/openapi.do)를 연다.
2. 회원가입 또는 로그인 후 **활용신청**을 선택한다. 다른 관광 API가 아니라 이 국문 서비스에 신청한다.
3. 활용 목적에는 `대학교 한 학기 개인 프로젝트로 국내 여행 일정 추천 웹 서비스를 개발하며 관광지·음식점·숙소의 운영정보를 조회합니다.`처럼 실제 목적을 적는다.
4. 필요한 신청 항목을 입력하고 제출한다. 상세 페이지의 2026-09-20 안내 기준으로 무료이며 개발단계는 자동승인, 운영단계는 심의승인이다. 실제 승인 상태와 할당량은 본인 계정에서 확인한다.
5. 마이페이지의 OpenAPI 활용 내역에서 해당 서비스의 승인 상태와 인증키를 확인한다. Encoding/Decoding 키가 구분되어 있으면 **Decoding 키**를 사용한다. 서버 코드가 URL 인코딩을 한 번 수행하기 때문이다.

## 2. Windows에 키 저장

1. 시작 메뉴에서 **계정의 환경 변수 편집**을 검색한다.
2. **사용자 변수 → 새로 만들기**에서 변수 이름을 `TOUR_API_SERVICE_KEY`, 값을 발급받은 Decoding 인증키로 저장한다.
3. VS Code·터미널·Codex를 완전히 종료하고 다시 연다. 실행 중인 서버도 재시작한다. 기존 프로세스는 새 환경변수를 받지 못할 수 있다.
4. 새 PowerShell에서 다음 명령으로 값 대신 설정 여부만 확인한다.

```powershell
if ([string]::IsNullOrWhiteSpace($env:TOUR_API_SERVICE_KEY)) { '미설정' } else { '설정됨' }
```

인증키를 채팅·소스·Git에 넣지 않는다. `.env` 파일은 현재 프로젝트에서 자동으로 읽지 않는다. 데스크탑과 노트북은 각각 설정한다. `application.properties`의 `${TOUR_API_SERVICE_KEY:}`를 실제 키로 바꾸지 않는다.

## 3. 실제 응답 확인

[한국관광콘텐츠랩](https://api.visitkorea.or.kr/)의 OpenAPI 가이드 또는 공공데이터포털 상세 명세에서 키워드 검색으로 표본 장소의 `contentid`, `contenttypeid`를 확인한다. 카카오 장소 ID를 넣으면 안 된다. 실습 결과나 요청 URL을 공유할 때 인증키를 포함하지 않는다.

프로젝트 루트의 PowerShell에서 확인한 표본 ID와 유형을 지정한다. 아래 자리표시자는 실제 값으로 바꾼다.

```powershell
$env:RUN_TOUR_API_TESTS = 'true'
$env:TOUR_API_TEST_CONTENT_ID = '확인한_숫자_contentid'
$env:TOUR_API_TEST_CONTENT_TYPE = '12'
./mvnw.cmd '-Dtest=TourApiClientTests,TourApiLiveTests' test
Remove-Item Env:RUN_TOUR_API_TESTS
Remove-Item Env:TOUR_API_TEST_CONTENT_ID
Remove-Item Env:TOUR_API_TEST_CONTENT_TYPE
```

실제 테스트는 DB 없이 소개정보 API를 한 번 호출한다. 표본 존재와 응답 형식 확인이며 방문 당일 영업 여부 검증은 아니다. 별도 실행 플래그가 없으면 실제 테스트는 건너뛴다. Java 21이 프로젝트 기준이다.

## 오류 확인

| 코드 | 확인할 내용 |
| --- | --- |
| TOUR_NOT_CONFIGURED | 새 실행 프로세스에 사용자 환경변수가 전달됐는지 확인 |
| TOUR_AUTH | 키 종류·값, 국문 서비스 활용신청 승인 상태와 이용 기간 확인 |
| TOUR_QUOTA | 본인 계정의 일일·초당 호출 한도 확인 |
| TOUR_TIMEOUT / TOUR_UNAVAILABLE | 네트워크와 서비스 공지를 확인한 뒤 재시도 |
| TOUR_INPUT | TourAPI 숫자 콘텐츠 ID와 지원 유형인지 확인 |
| TOUR_RESPONSE | 공식 명세 변경·응답 형식 확인. 요청 URL이나 오류 원문을 로그에 추가하지 않음 |

## 명세와 처리 원칙

공공데이터포털 상세 페이지에 포함된 공식 Swagger를 2026-09-20 확인했다. 주소는 `https://apis.data.go.kr/B551011/KorService2/detailIntro2`, 요청 필수 항목은 `serviceKey`, `MobileOS`, `MobileApp`, `contentId`, `contentTypeId`다. 서버는 `MobileOS=WEB`, `MobileApp=travel_recommendation`, `_type=json`을 사용한다.

| 유형 | 시간 원문 | 휴무 원문 |
| --- | --- | --- |
| 관광지 12 | usetime | restdate |
| 문화시설 14 | usetimeculture | restdateculture |
| 레포츠 28 | usetimeleports | restdateleports |
| 음식점 39 | opentimefood | restdatefood |
| 숙소 32 | checkintime / checkouttime | 영업시간·휴무로 변환하지 않음 |

응답 콘텐츠 ID·유형을 요청과 대조하고 누락된 원문은 빈 값으로 유지한다. `fetchedAt`은 조회 시각이며 운영정보 자체의 갱신일이 아니다. 영업시간 원문을 받았다는 이유로 방문 가능이라고 판단하지 않는다. 이후 화면 출력에는 이스케이프를 적용하고 원문의 HTML을 그대로 실행하지 않는다.

연결 제한 3초·요청 제한 8초이며 자동 재시도하지 않는다. HTTP 오류·JSON 오류·게이트웨이 XML 오류를 공개 오류로 바꾸고 인증키가 들어가는 요청 URL·응답 원문·예외 원인은 기록하지 않는다.
