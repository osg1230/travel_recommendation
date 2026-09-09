# 여행 추천 서비스 ERD

회원 닉네임은 `status <> 'WITHDRAWN'`인 행에만 UNIQUE를 적용한다. ERD 구조에는 조건부 인덱스가 표현되지 않으므로 `../sql/001_create_users.sql`을 함께 확인한다. 기존 Word·ERDCloud 저장본은 이 회원 구현 변경을 포함하지 않는다.

18개 테이블의 PK/FK 관계이다. CHECK·UNIQUE 및 서비스 검증 규칙은 [전체 정의서](../DB_테이블_정의서.md)를 따른다.

```mermaid
erDiagram
    users {
        BIGINT user_id PK
        VARCHAR_50_ login_id
        VARCHAR_255_ password_hash
        NVARCHAR_50_ nickname
        VARCHAR_20_ role
        VARCHAR_20_ status
        DATETIME2_3_ created_at
        DATETIME2_3_ updated_at
        DATETIME2_3_ withdrawn_at
    }
    keyword_categories {
        BIGINT category_id PK
        VARCHAR_40_ category_code
        NVARCHAR_50_ category_name
        INT sort_order
        BIT is_active
        DATETIME2_3_ created_at
        DATETIME2_3_ updated_at
    }
    keywords {
        BIGINT keyword_id PK
        BIGINT category_id FK
        VARCHAR_50_ keyword_code
        NVARCHAR_80_ keyword_name
        NVARCHAR_MAX_ value_json
        INT sort_order
        BIT is_active
        DATETIME2_3_ created_at
        DATETIME2_3_ updated_at
    }
    recommendation_requests {
        BIGINT request_id PK
        BIGINT user_id FK
        VARCHAR_10_ input_mode
        NVARCHAR_2000_ original_text
        NVARCHAR_MAX_ conditions_json
        NVARCHAR_100_ region
        DATE start_date
        DATE end_date
        SMALLINT duration_days
        DECIMAL_12_2_ budget
        BIT accommodation_needed
        NVARCHAR_1000_ interpretation_summary
        DATETIME2_3_ created_at
        DATETIME2_3_ updated_at
    }
    recommendation_candidates {
        BIGINT candidate_id PK
        BIGINT request_id FK
        BIGINT place_id FK
        TINYINT rank_no
        NVARCHAR_2000_ recommendation_reason
        NVARCHAR_MAX_ place_snapshot_json
        DATETIME2_3_ created_at
    }
    ai_jobs {
        UNIQUEIDENTIFIER job_id PK
        BIGINT user_id FK
        BINARY_32_ guest_token_hash
        BIGINT request_id FK
        VARCHAR_20_ job_type
        VARCHAR_32_ status
        TINYINT attempt_count
        NVARCHAR_MAX_ request_json
        NVARCHAR_MAX_ result_json
        NVARCHAR_MAX_ validation_errors_json
        VARCHAR_60_ error_code
        NVARCHAR_1000_ message
        DATETIME2_3_ created_at
        DATETIME2_3_ started_at
        DATETIME2_3_ completed_at
        DATETIME2_3_ expires_at
        ROWVERSION row_version
    }
    places {
        BIGINT place_id PK
        VARCHAR_20_ place_type
        VARCHAR_40_ source_provider
        NVARCHAR_150_ source_place_id
        NVARCHAR_200_ place_name
        NVARCHAR_100_ region
        NVARCHAR_300_ address
        DECIMAL_9_6_ latitude
        DECIMAL_9_6_ longitude
        NVARCHAR_80_ subtype
        NVARCHAR_2000_ description
        NVARCHAR_MAX_ details_json
        NVARCHAR_1000_ source_url
        NVARCHAR_1000_ map_url
        BIT is_active
        DATETIME2_3_ verified_at
        DATETIME2_3_ created_at
        DATETIME2_3_ updated_at
    }
    itineraries {
        BIGINT itinerary_id PK
        BIGINT user_id FK
        BIGINT request_id FK
        BIGINT destination_place_id FK
        NVARCHAR_150_ title
        DATE start_date
        DATE end_date
        VARCHAR_20_ transportation
        NVARCHAR_MAX_ conditions_snapshot_json
        DATETIME2_3_ created_at
        DATETIME2_3_ updated_at
        ROWVERSION row_version
    }
    itinerary_items {
        BIGINT item_id PK
        BIGINT itinerary_id FK
        SMALLINT day_no
        SMALLINT sequence_no
        VARCHAR_20_ item_type
        BIGINT place_id FK
        DATETIME2_3_ start_at
        DATETIME2_3_ end_at
        NVARCHAR_1000_ activity
        INT travel_minutes
        NVARCHAR_MAX_ place_snapshot_json
        NVARCHAR_MAX_ transport_json
        NVARCHAR_MAX_ food_guide_json
        DATETIME2_3_ created_at
        DATETIME2_3_ updated_at
    }
    favorites {
        BIGINT favorite_id PK
        BIGINT user_id FK
        BIGINT place_id FK
        DATETIME2_3_ created_at
    }
    place_ratings {
        BIGINT rating_id PK
        BIGINT user_id FK
        BIGINT place_id FK
        TINYINT score
        DATETIME2_3_ created_at
        DATETIME2_3_ updated_at
    }
    rating_tags {
        BIGINT tag_id PK
        VARCHAR_40_ tag_code
        NVARCHAR_80_ tag_name
        INT sort_order
        BIT is_active
        DATETIME2_3_ created_at
        DATETIME2_3_ updated_at
    }
    place_rating_tags {
        BIGINT rating_id PK,FK
        BIGINT tag_id PK,FK
        TINYINT slot_no
    }
    operation_logs {
        BIGINT log_id PK
        BIGINT actor_user_id FK
        VARCHAR_10_ actor_kind
        VARCHAR_20_ target_type
        BIGINT target_id
        VARCHAR_50_ action_code
        VARCHAR_32_ before_status
        VARCHAR_32_ after_status
        NVARCHAR_1000_ reason
        VARCHAR_10_ outcome
        UNIQUEIDENTIFIER correlation_id
        DATETIME2_3_ created_at
    }
    reservation_stock {
        BIGINT stock_id PK
        BIGINT place_id FK
        DATETIME2_3_ usage_start_at
        DATETIME2_3_ usage_end_at
        INT total_quantity
        INT remaining_quantity
        DECIMAL_12_2_ unit_price
        VARCHAR_10_ quantity_unit
        BIT is_active
        DATETIME2_3_ created_at
        DATETIME2_3_ updated_at
        ROWVERSION row_version
    }
    virtual_reservations {
        BIGINT reservation_id PK
        BIGINT user_id FK
        BIGINT stock_id FK
        BIGINT itinerary_id FK
        UNIQUEIDENTIFIER request_key
        INT quantity
        INT party_size
        DECIMAL_12_2_ expected_amount
        VARCHAR_12_ status
        BIT payment_required
        NVARCHAR_MAX_ booking_snapshot_json
        DATETIME2_3_ created_at
        DATETIME2_3_ updated_at
        DATETIME2_3_ canceled_at
        ROWVERSION row_version
    }
    virtual_payments {
        BIGINT payment_id PK
        BIGINT reservation_id FK
        UNIQUEIDENTIFIER idempotency_key
        BINARY_32_ request_hash
        DECIMAL_12_2_ amount
        VARCHAR_12_ status
        VARCHAR_60_ failure_code
        DATETIME2_3_ created_at
        DATETIME2_3_ processed_at
        DATETIME2_3_ refunded_at
    }
    reservation_logs {
        BIGINT reservation_log_id PK
        BIGINT reservation_id FK
        BIGINT stock_id FK
        UNIQUEIDENTIFIER operation_id
        VARCHAR_20_ event_type
        INT before_quantity
        INT delta_quantity
        INT after_quantity
        VARCHAR_12_ before_status
        VARCHAR_12_ after_status
        NVARCHAR_500_ reason
        DATETIME2_3_ created_at
    }
    keyword_categories ||--o{ keywords : category_id
    users ||--o{ recommendation_requests : user_id
    recommendation_requests ||--o{ recommendation_candidates : request_id
    places ||--o{ recommendation_candidates : place_id
    users |o--o{ ai_jobs : user_id
    recommendation_requests |o--o{ ai_jobs : request_id
    users ||--o{ itineraries : user_id
    recommendation_requests |o--o{ itineraries : request_id
    places ||--o{ itineraries : destination_place_id
    itineraries ||--o{ itinerary_items : itinerary_id
    places |o--o{ itinerary_items : place_id
    users ||--o{ favorites : user_id
    places ||--o{ favorites : place_id
    users ||--o{ place_ratings : user_id
    places ||--o{ place_ratings : place_id
    place_ratings ||--o{ place_rating_tags : rating_id
    rating_tags ||--o{ place_rating_tags : tag_id
    users |o--o{ operation_logs : actor_user_id
    places ||--o{ reservation_stock : place_id
    users ||--o{ virtual_reservations : user_id
    reservation_stock ||--o{ virtual_reservations : stock_id
    itineraries |o--o{ virtual_reservations : itinerary_id
    virtual_reservations ||--o{ virtual_payments : reservation_id
    virtual_reservations ||--o{ reservation_logs : reservation_id
    reservation_stock ||--o{ reservation_logs : stock_id
```
