# DB 설계 검토 및 변경 내역

검토일: 2026-09-09

현재 기능 계획을 유지하면서 중복 저장 3곳과 의미가 불명확한 이름을 정리했다. 실제 DB 접속이나 스키마 변경은 실행하지 않았다. 현재 Entity와 Repository 구현이 없어 애플리케이션 변경은 없다.

## 확인할 파일

- [정리된 전체 정의서](DB_테이블_정의서.md): 이번 변경의 내용을 확인할 수 있는 문서
- `DB 테이블 정의서_정리본_검토용.docx`: 같은 내용을 반영한 Word 검토용 파일. 이 환경에 LibreOffice가 없어 페이지 렌더링 검증을 완료하지 못했다.
- `DB 테이블 정의서_수정본.docx`: 기존 원본 유지
- `archive/DB 테이블 정의서_2026-09-09_정리전.docx`: 작업 전 보관본
- [ERD](erd/ERD.md), [Mermaid 원본](erd/여행추천_ERD.mmd)
- [ERD 가져오기용 구조 SQL](erd/ERD_가져오기용_구조.sql): PK/FK·자료형·NULL 표현용. 배포용 DDL이 아니며 UNIQUE·CHECK·DEFAULT·IDENTITY·인덱스·서비스 규칙은 포함하지 않는다. 실제 DB에 실행하지 않는다. ERDCloud 가져오기 호환성은 미검증이다.

## 삭제한 컬럼

| 컬럼 | 이유와 대체 방법 |
| --- | --- |
| ai_jobs.current_step | status에 분석·검색·생성·검증 단계가 이미 들어 있다. 화면 문구는 status에서 만들고 별도 설명은 message에 둔다. |
| virtual_payments.test_method | 허용값이 TEST 하나뿐이다. 서버 상수로 처리하며 요청 해시에 필요한 수단 값도 같은 상수를 사용한다. |
| place_rating_tags.created_at | 태그별 선택 시각을 조회하는 요구가 없다. 평가 변경 시각은 place_ratings.updated_at으로 관리한다. 최대 3개 제한용 slot_no는 유지한다. |

## 이름 변경

| 기존 | 변경 | 이유 |
| --- | --- | --- |
| recommendation_history | recommendation_requests | 회원 추천 요청 한 건을 저장하는 테이블임을 명확히 함. 기존 추천 이력 기능은 유지 |
| history_id | request_id | 추천 요청 PK와 모든 FK 이름 통일 |
| recommendation_results | recommendation_candidates | 저장 일정 결과와 구분해 여행지 후보임을 표현 |
| result_id | candidate_id | 후보 식별자임을 표현 |
| admin_action_logs | operation_logs | 관리자 외 회원·시스템 처리도 기록하므로 역할에 맞게 변경 |
| reservation_inventory | reservation_stock | 가상 예약 수량을 관리하는 목적을 표현 |
| inventory_id | stock_id | 관련 PK/FK와 잠금 순서 설명 통일 |

한글명은 장소 정보, AI 생성 작업, 저장한 여행 일정, 평가 태그, 운영 처리 로그, 가상 예약 수량으로 다듬었다. 만료·검증·작업 종료 등 시각 컬럼의 설명도 구체화했다.

## 유지한 구조

18개 테이블은 모두 현재 요구사항과 연결돼 있어 삭제하지 않았다. 핵심 14개와 R3 4개 구분을 유지한다. 예약·결제 관련 테이블도 선택적 확장 계획의 일부이므로 보존한다.

ai_jobs는 비회원 임시 결과와 비동기 상태 관리에, recommendation_requests는 회원 추천 이력에 필요하다. 평가 태그 사전과 연결 테이블은 다중 태그·중복 방지에 필요하다. 스냅샷 JSON은 현재 장소 정보가 바뀌거나 원 추천 이력을 삭제해도 과거 저장 내용을 보존하기 위해 유지한다. ROWVERSION과 예약 수량 로그는 동시 수정·수량 변경 검증 목적이 있으므로 제거하지 않는다.

## 미해결 사항

- 닉네임 중복 거부 요구와 DB 제약이 일치하지 않는다. 단순 UNIQUE 추가는 탈퇴 시 모든 nickname을 ‘탈퇴회원’으로 바꾸는 기존 정책과 충돌하므로 이번 이름 정리에서 임의로 변경하지 않았다. 활성 계정에 한정한 유일성 또는 익명화 방식과 함께 결정해야 한다.
- 관리자 키워드 관리의 포함 여부는 AGENTS.md와 요구분석서가 다르다. 현재 계획 유지 결정에 따라 이번에는 관련 테이블을 제거하지 않았다.
- 날짜·운영시간·장소 유형·소유권·작업 상태 전이는 FK만으로 보장되지 않는다. 정의서의 서버 검증 규칙은 계속 적용한다.
- ERDCloud 저장 HTML은 canvas 중심의 웹페이지 저장본이며 편집 가능한 Snapshot 모델은 확인되지 않았다. 원 저장본과 연결 리소스는 보존했다. 온라인 ERDCloud 문서는 변경하지 않았다. 정확한 원본 편집에는 ERDCloud에서 내보낸 Snapshot 또는 편집 가능한 세션이 필요하다.

## 확인 결과

정리본은 18개 테이블과 25개 FK를 유지한다. 모든 FK의 대상 테이블·컬럼 존재, 삭제 대상 3개 컬럼의 제거, PK/FK 이름 변경을 검사했다. Word 정리본과 Markdown·구조 SQL·Mermaid는 같은 테이블 데이터에서 생성했다. 실제 SQL Server 실행, ERDCloud 가져오기, Word 페이지 모양 검증은 완료하지 않았다.
