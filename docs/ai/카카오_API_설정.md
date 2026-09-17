# 카카오 API 설정

## 현재 단계

서버가 `KAKAO_REST_API_KEY` 환경변수를 읽는 설정을 준비했다. 아직 좌표·경로 API 호출과 일정 생성 기능을 연결하지 않았으며, 키 설정만으로 기존 화면 동작이 달라지지는 않는다. DB 변경은 없다.

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

2026-09-18 확인한 공식 자료: [카카오모빌리티 시작하기](https://developers.kakaomobility.com/guide/navi-api/start), [자동차 길찾기](https://developers.kakaomobility.com/guide/navi-api/directions.html), [카카오맵 REST API](https://developers.kakao.com/docs/ko/kakaomap/rest-api). 실제 앱 권한과 API 응답은 키 설정 후 확인한다.
