# 프로젝트 문서 안내

설계 자료는 종류별로 모으고 이전 버전은 각 `archive` 폴더에 보관합니다. 폴더 정리는 문서 내용이나 기능 범위를 확정한 작업이 아닙니다.

| 위치 | 내용 |
| --- | --- |
| [progress.md](progress.md) | 현재 진행 상황, 확인 결과, 다음 작업 |
| [requirements](requirements/) | 관리자 범위 축소 요구분석서 |
| [requirements/archive](requirements/archive/) | 이전 요구분석서와 최종본이 아닌 문서 |
| [database](database/) | 수정된 DB 테이블 정의서 |
| [database/archive](database/archive/) | 이전 DB 테이블 정의서 |
| [database/erd](database/erd/) | 저장된 ERD HTML과 연결 리소스 |
| [screens](screens/) | 화면 이미지, 화면 ID, 흐름도, 요구사항 추적표 |

## 먼저 확인할 자료

DB 설계 이름과 중복 컬럼 정리 결과는 [DB 검토 변경 내역](database/DB_검토_변경내역.md)과 [전체 정의서 정리본](database/DB_테이블_정의서.md)에서 확인합니다. 기존 Word 원본은 보존되어 있으며 새 Word 파일은 페이지 모양 미검증인 검토용입니다.

- [요구분석서 3.1 수정본](requirements/창프2_요구분석서%203.1_취향탐색_일정상세화.docx) — 취향 탐색·조건 확인·부분 재추천 반영, 페이지 모양 미검증
- [요구분석서 3.1 변경 내역](requirements/요구분석서_3.1_변경내역.md)
- [요구분석서 3.0 원본](requirements/창프2_요구분석서%203.0_관리자범위축소_최종본.docx)
- [DB 테이블 정의서 수정본](database/DB%20테이블%20정의서_수정본.docx)
- [ERD HTML](database/erd/여행%20추천%20서비스DB%20ERD.html)
- [최종 화면 ID](screens/최종_화면_ID_정리.txt)
- [화면 흐름도](screens/화면_흐름도.md)
- [화면 요구사항 추적표](screens/화면_요구사항_추적표.md)

관리자 키워드 관리 범위, 닉네임 중복 정책, 추적표의 이전 명세 참조 등 미해결 사항은 진행 문서에 기록되어 있습니다. ERD HTML과 같은 위치의 `_files` 폴더는 한 묶음으로 이동해야 합니다.
