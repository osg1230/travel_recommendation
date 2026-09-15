# 프로젝트 문서 안내

현재 기능 기준은 [요구분석서 적용 기준](requirements/적용기준.md)과 요구분석서 3.1입니다. 적용 기준에 명시한 차이는 해당 기준을 우선하고 나머지는 3.1 원본을 따릅니다. 이전 버전과 참고 이미지는 현재 구현 범위를 추가하는 근거로 사용하지 않습니다.

| 위치 | 내용 |
| --- | --- |
| [progress.md](progress.md) | 현재 진행 상황, 확인 결과, 다음 작업 |
| [requirements](requirements/) | 요구분석서 3.1 원본과 프로젝트 적용 기준 |
| [requirements/archive](requirements/archive/) | 이전 요구분석서와 최종본이 아닌 문서 |
| [database](database/) | 수정된 DB 테이블 정의서 |
| [database/archive](database/archive/) | 이전 DB 테이블 정의서 |
| [database/erd](database/erd/) | 저장된 ERD HTML과 연결 리소스 |
| [screens](screens/) | 화면 이미지, 화면 ID, 흐름도, 요구사항 추적표 |

## 먼저 확인할 자료

최신 DB 설계는 Markdown 정의서를 사용합니다. DB 설계 이름과 중복 컬럼 정리 결과는 [DB 검토 변경 내역](database/DB_검토_변경내역.md)과 [전체 정의서 정리본](database/DB_테이블_정의서.md)에서 확인합니다. 기존 DB Word 원본과 검토용 파일은 이후 설계 변경을 포함하지 않으며 검토용 파일의 페이지 모양도 미검증입니다.

- [요구분석서 적용 기준](requirements/적용기준.md) — 관리자 범위·후보 개수·일정 배치·남은 구현 항목
- [요구분석서 3.1 원본](requirements/창프2_요구분석서%203.1_일정배치방식반영_최종본.docx) — 전달받은 내용 그대로 보존
- [DB 테이블 정의서](database/DB_테이블_정의서.md) — 최신 설계
- [DB 테이블 정의서 Word 원본](database/DB%20테이블%20정의서_수정본.docx) — 이전 설계 참고
- [ERD](database/erd/ERD.md) — 현재 구조와 DB 정의서 연결
- [ERD HTML 저장본](database/erd/여행%20추천%20서비스DB%20ERD.html) — 이전 참고 자료
- [최종 화면 ID](screens/최종_화면_ID_정리.txt)
- [화면 흐름도](screens/화면_흐름도.md)
- [화면 요구사항 추적표](screens/화면_요구사항_추적표.md)

관리자는 회원 관리만 제공하고 키워드 데이터는 배포로 관리합니다. 닉네임은 탈퇴하지 않은 회원 사이에서 중복을 금지합니다. 자동 재요청·WebClient 전환은 요구사항을 유지한 미완료 항목이며 진행 문서에서 확인합니다. ERD HTML과 같은 위치의 `_files` 폴더는 한 묶음으로 이동해야 합니다.
