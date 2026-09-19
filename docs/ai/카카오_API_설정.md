# 카카오 API 설정

## 현재 단계

서버가 `KAKAO_REST_API_KEY` 환경변수를 읽고 `KakaoMapClient`에서 주소 좌표와 자동차·대중교통 경로의 소요시간을 조회한다. 2026-09-18 현재 PC에서 실제 Java 클라이언트 호출을 검증했다. 2026-09-19 [시간대별 일정 초안](시간대별_일정_초안.md)에 연결했으며 DB 스키마 변경은 없다.

입력 도로명 주소와 응답 주소가 정확히 일치하고 결과가 1개일 때만 국내 좌표를 반환한다. 초기 지역의 광역시·도 표기 차이만 정규화한다. 일정 생성기는 도로명·건물번호 뒤의 층·상호를 분리해 조회하며 원래 장소 주소는 스냅샷에 유지한다. 경로 소요시간은 초 단위로 받고 분 단위 표시 시 올림한다. 대중교통은 해당 경로 중 가장 짧은 소요시간을 사용한다. 클라이언트는 동일 좌표·경로 없음·잘못된 값을 오류로 처리하며, 일정 생성기의 같은 주소 이동은 API 조회와 구분한 계획상 여유시간으로 표시한다.

연결 제한 3초·요청 제한 8초이며 인증·권한·사용량·통신·시간 초과 오류를 구분한다. 자동 재호출과 오류 원문 저장은 하지 않는다. 지도 조회 시각을 반환하며 실제 영업 여부·입구 위치·여행 당일 이동시간·방문 가능성까지 검증하는 것은 아니다.

## 키 준비

1. [Kakao Developers](https://developers.kakao.com/)에서 프로젝트용 앱을 선택하거나 생성한다.
2. 앱의 플랫폼 키에서 **REST API 키**를 확인한다. 서버의 주소 조회·길찾기 요청에 사용할 키다.
3. Windows 시작 메뉴에서 **계정의 환경 변수 편집**을 검색한다.
4. 사용자 변수에 이름 `KAKAO_REST_API_KEY`, 값은 발급받은 REST API 키로 저장한다.
5. 서버를 실행할 터미널·VS Code를 새로 열고 서버를 재시작한다. Codex에서 실행한다면 Codex도 다시 열어야 새 환경변수가 상속된다.

이미 실행 중인 PowerShell에서는 다음 명령으로 사용자 변수를 현재 프로세스에 가져올 수 있다. 키 값을 출력하지 않는다.

```powershell
$env:KAKAO_REST_API_KEY = [Environment]::GetEnvironmentVariable('KAKAO_REST_API_KEY', 'User')
if ([string]::IsNullOrWhiteSpace($env:KAKAO_REST_API_KEY)) {
    Write-Output '카카오 REST API 키 미설정'
} else {
    Write-Output '카카오 REST API 키 설정됨 (API 인증 성공 여부는 별도 확인 필요)'
}
```

키를 채팅·소스 코드·HTML·스크린샷·Git에 넣지 않는다. 각 PC의 사용자 환경변수는 별도로 설정한다. REST API 키는 서버에서만 사용하고 화면에 전달하지 않는다.

## 이어서 확인할 항목

- 주소 검색 응답의 도로명 주소와 좌표를 기존 장소 데이터와 대조한다.
- 이동수단에 맞는 경로 응답의 거리·소요시간을 확인한다. 조회 시점의 예상 소요시간이며 여행 당일 도착 시간을 보장하지 않는다.
- 인증 실패·권한 제한·사용량 제한·경로 없음은 별도 안내하고 임의의 이동시간으로 바꾸지 않는다.
- 기간과 하루 시작/종료 시간을 확인받아 가까운 장소를 같은 날 배치하고 이동·체류 시간의 충돌을 검사한다.

## 검증

일반 `KakaoMapClientTests`는 로컬 응답 서버를 사용하며 실제 API를 호출하지 않는다. 주소 불일치·좌표 이상·초/분 변환·이동수단 구분·실패 응답과 추가 호출 차단을 검사한다. 실제 호출은 `RUN_KAKAO_TESTS=true`와 `KAKAO_REST_API_KEY`가 있을 때만 `KakaoMapLiveTests`에서 수행하며 API 사용량이 발생한다.

```powershell
. ./scripts/use-java21.ps1
$env:KAKAO_REST_API_KEY = [Environment]::GetEnvironmentVariable('KAKAO_REST_API_KEY', 'User')
$env:RUN_KAKAO_TESTS = 'true'
./mvnw.cmd '-Dtest=KakaoMapClientTests,KakaoMapLiveTests' test
Remove-Item Env:RUN_KAKAO_TESTS
```

2026-09-18 자동 6개와 실제 연동 1개, 총 7개 통과. 실제 연동은 오죽헌·경포대 주소 2개와 두 장소 간 자동차·대중교통 경로를 확인한다. 특정 시점 소요시간을 고정된 정답으로 테스트하지 않는다. 전체 회귀·부하·모든 초기 장소 주소 검증은 이번 실행 범위에 포함하지 않았다.

2026-09-18 확인한 공식 자료: [카카오모빌리티 시작하기](https://developers.kakaomobility.com/guide/navi-api/start), [자동차 길찾기](https://developers.kakaomobility.com/guide/navi-api/directions.html), [카카오맵 REST API](https://developers.kakao.com/docs/ko/kakaomap/rest-api). 실제 앱 권한과 API 응답은 키 설정 후 확인한다.
