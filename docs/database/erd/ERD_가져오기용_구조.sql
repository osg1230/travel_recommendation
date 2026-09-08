-- ERD 작성용 SQL Server 구조 SQL. 실제 DB 실행 금지.
-- PK/FK/NULL/자료형만 포함. DEFAULT, UNIQUE, CHECK, 인덱스, IDENTITY, 서비스 규칙은 전체 정의서 참조.
-- 배포용 DDL이 아니며 SQL Server 및 ERDCloud 가져오기 실행은 미검증.

CREATE TABLE [users] (
    [user_id] BIGINT NOT NULL,
    [login_id] VARCHAR(50) NOT NULL,
    [password_hash] VARCHAR(255) NOT NULL,
    [nickname] NVARCHAR(50) NOT NULL,
    [role] VARCHAR(20) NOT NULL,
    [status] VARCHAR(20) NOT NULL,
    [created_at] DATETIME2(3) NOT NULL,
    [updated_at] DATETIME2(3) NOT NULL,
    [withdrawn_at] DATETIME2(3) NULL,
    CONSTRAINT [PK_users] PRIMARY KEY ([user_id])
);

CREATE TABLE [keyword_categories] (
    [category_id] BIGINT NOT NULL,
    [category_code] VARCHAR(40) NOT NULL,
    [category_name] NVARCHAR(50) NOT NULL,
    [sort_order] INT NOT NULL,
    [is_active] BIT NOT NULL,
    [created_at] DATETIME2(3) NOT NULL,
    [updated_at] DATETIME2(3) NOT NULL,
    CONSTRAINT [PK_keyword_categories] PRIMARY KEY ([category_id])
);

CREATE TABLE [keywords] (
    [keyword_id] BIGINT NOT NULL,
    [category_id] BIGINT NOT NULL,
    [keyword_code] VARCHAR(50) NOT NULL,
    [keyword_name] NVARCHAR(80) NOT NULL,
    [value_json] NVARCHAR(MAX) NOT NULL,
    [sort_order] INT NOT NULL,
    [is_active] BIT NOT NULL,
    [created_at] DATETIME2(3) NOT NULL,
    [updated_at] DATETIME2(3) NOT NULL,
    CONSTRAINT [PK_keywords] PRIMARY KEY ([keyword_id])
);

CREATE TABLE [recommendation_requests] (
    [request_id] BIGINT NOT NULL,
    [user_id] BIGINT NOT NULL,
    [input_mode] VARCHAR(10) NOT NULL,
    [original_text] NVARCHAR(2000) NULL,
    [conditions_json] NVARCHAR(MAX) NOT NULL,
    [region] NVARCHAR(100) NULL,
    [start_date] DATE NULL,
    [end_date] DATE NULL,
    [duration_days] SMALLINT NULL,
    [budget] DECIMAL(12,2) NULL,
    [accommodation_needed] BIT NULL,
    [interpretation_summary] NVARCHAR(1000) NULL,
    [created_at] DATETIME2(3) NOT NULL,
    [updated_at] DATETIME2(3) NOT NULL,
    CONSTRAINT [PK_recommendation_requests] PRIMARY KEY ([request_id])
);

CREATE TABLE [recommendation_candidates] (
    [candidate_id] BIGINT NOT NULL,
    [request_id] BIGINT NOT NULL,
    [place_id] BIGINT NOT NULL,
    [rank_no] TINYINT NOT NULL,
    [recommendation_reason] NVARCHAR(2000) NOT NULL,
    [place_snapshot_json] NVARCHAR(MAX) NOT NULL,
    [created_at] DATETIME2(3) NOT NULL,
    CONSTRAINT [PK_recommendation_candidates] PRIMARY KEY ([candidate_id])
);

CREATE TABLE [ai_jobs] (
    [job_id] UNIQUEIDENTIFIER NOT NULL,
    [user_id] BIGINT NULL,
    [guest_token_hash] BINARY(32) NULL,
    [request_id] BIGINT NULL,
    [job_type] VARCHAR(20) NOT NULL,
    [status] VARCHAR(32) NOT NULL,
    [attempt_count] TINYINT NOT NULL,
    [request_json] NVARCHAR(MAX) NOT NULL,
    [result_json] NVARCHAR(MAX) NULL,
    [validation_errors_json] NVARCHAR(MAX) NULL,
    [error_code] VARCHAR(60) NULL,
    [message] NVARCHAR(1000) NULL,
    [created_at] DATETIME2(3) NOT NULL,
    [started_at] DATETIME2(3) NULL,
    [completed_at] DATETIME2(3) NULL,
    [expires_at] DATETIME2(3) NOT NULL,
    [row_version] ROWVERSION NOT NULL,
    CONSTRAINT [PK_ai_jobs] PRIMARY KEY ([job_id])
);

CREATE TABLE [places] (
    [place_id] BIGINT NOT NULL,
    [place_type] VARCHAR(20) NOT NULL,
    [source_provider] VARCHAR(40) NOT NULL,
    [source_place_id] NVARCHAR(150) NOT NULL,
    [place_name] NVARCHAR(200) NOT NULL,
    [region] NVARCHAR(100) NOT NULL,
    [address] NVARCHAR(300) NULL,
    [latitude] DECIMAL(9,6) NULL,
    [longitude] DECIMAL(9,6) NULL,
    [subtype] NVARCHAR(80) NULL,
    [description] NVARCHAR(2000) NULL,
    [details_json] NVARCHAR(MAX) NULL,
    [source_url] NVARCHAR(1000) NULL,
    [map_url] NVARCHAR(1000) NULL,
    [is_active] BIT NOT NULL,
    [verified_at] DATETIME2(3) NOT NULL,
    [created_at] DATETIME2(3) NOT NULL,
    [updated_at] DATETIME2(3) NOT NULL,
    CONSTRAINT [PK_places] PRIMARY KEY ([place_id])
);

CREATE TABLE [itineraries] (
    [itinerary_id] BIGINT NOT NULL,
    [user_id] BIGINT NOT NULL,
    [request_id] BIGINT NULL,
    [destination_place_id] BIGINT NOT NULL,
    [title] NVARCHAR(150) NOT NULL,
    [start_date] DATE NOT NULL,
    [end_date] DATE NOT NULL,
    [transportation] VARCHAR(20) NOT NULL,
    [conditions_snapshot_json] NVARCHAR(MAX) NOT NULL,
    [created_at] DATETIME2(3) NOT NULL,
    [updated_at] DATETIME2(3) NOT NULL,
    [row_version] ROWVERSION NOT NULL,
    CONSTRAINT [PK_itineraries] PRIMARY KEY ([itinerary_id])
);

CREATE TABLE [itinerary_items] (
    [item_id] BIGINT NOT NULL,
    [itinerary_id] BIGINT NOT NULL,
    [day_no] SMALLINT NOT NULL,
    [sequence_no] SMALLINT NOT NULL,
    [item_type] VARCHAR(20) NOT NULL,
    [place_id] BIGINT NULL,
    [start_at] DATETIME2(3) NOT NULL,
    [end_at] DATETIME2(3) NOT NULL,
    [activity] NVARCHAR(1000) NOT NULL,
    [travel_minutes] INT NULL,
    [place_snapshot_json] NVARCHAR(MAX) NULL,
    [transport_json] NVARCHAR(MAX) NULL,
    [food_guide_json] NVARCHAR(MAX) NULL,
    [created_at] DATETIME2(3) NOT NULL,
    [updated_at] DATETIME2(3) NOT NULL,
    CONSTRAINT [PK_itinerary_items] PRIMARY KEY ([item_id])
);

CREATE TABLE [favorites] (
    [favorite_id] BIGINT NOT NULL,
    [user_id] BIGINT NOT NULL,
    [place_id] BIGINT NOT NULL,
    [created_at] DATETIME2(3) NOT NULL,
    CONSTRAINT [PK_favorites] PRIMARY KEY ([favorite_id])
);

CREATE TABLE [place_ratings] (
    [rating_id] BIGINT NOT NULL,
    [user_id] BIGINT NOT NULL,
    [place_id] BIGINT NOT NULL,
    [score] TINYINT NOT NULL,
    [created_at] DATETIME2(3) NOT NULL,
    [updated_at] DATETIME2(3) NOT NULL,
    CONSTRAINT [PK_place_ratings] PRIMARY KEY ([rating_id])
);

CREATE TABLE [rating_tags] (
    [tag_id] BIGINT NOT NULL,
    [tag_code] VARCHAR(40) NOT NULL,
    [tag_name] NVARCHAR(80) NOT NULL,
    [sort_order] INT NOT NULL,
    [is_active] BIT NOT NULL,
    [created_at] DATETIME2(3) NOT NULL,
    [updated_at] DATETIME2(3) NOT NULL,
    CONSTRAINT [PK_rating_tags] PRIMARY KEY ([tag_id])
);

CREATE TABLE [place_rating_tags] (
    [rating_id] BIGINT NOT NULL,
    [tag_id] BIGINT NOT NULL,
    [slot_no] TINYINT NOT NULL,
    CONSTRAINT [PK_place_rating_tags] PRIMARY KEY ([rating_id], [tag_id])
);

CREATE TABLE [operation_logs] (
    [log_id] BIGINT NOT NULL,
    [actor_user_id] BIGINT NULL,
    [actor_kind] VARCHAR(10) NOT NULL,
    [target_type] VARCHAR(20) NOT NULL,
    [target_id] BIGINT NOT NULL,
    [action_code] VARCHAR(50) NOT NULL,
    [before_status] VARCHAR(32) NULL,
    [after_status] VARCHAR(32) NULL,
    [reason] NVARCHAR(1000) NOT NULL,
    [outcome] VARCHAR(10) NOT NULL,
    [correlation_id] UNIQUEIDENTIFIER NULL,
    [created_at] DATETIME2(3) NOT NULL,
    CONSTRAINT [PK_operation_logs] PRIMARY KEY ([log_id])
);

CREATE TABLE [reservation_stock] (
    [stock_id] BIGINT NOT NULL,
    [place_id] BIGINT NOT NULL,
    [usage_start_at] DATETIME2(3) NOT NULL,
    [usage_end_at] DATETIME2(3) NOT NULL,
    [total_quantity] INT NOT NULL,
    [remaining_quantity] INT NOT NULL,
    [unit_price] DECIMAL(12,2) NOT NULL,
    [quantity_unit] VARCHAR(10) NOT NULL,
    [is_active] BIT NOT NULL,
    [created_at] DATETIME2(3) NOT NULL,
    [updated_at] DATETIME2(3) NOT NULL,
    [row_version] ROWVERSION NOT NULL,
    CONSTRAINT [PK_reservation_stock] PRIMARY KEY ([stock_id])
);

CREATE TABLE [virtual_reservations] (
    [reservation_id] BIGINT NOT NULL,
    [user_id] BIGINT NOT NULL,
    [stock_id] BIGINT NOT NULL,
    [itinerary_id] BIGINT NULL,
    [request_key] UNIQUEIDENTIFIER NOT NULL,
    [quantity] INT NOT NULL,
    [party_size] INT NOT NULL,
    [expected_amount] DECIMAL(12,2) NOT NULL,
    [status] VARCHAR(12) NOT NULL,
    [payment_required] BIT NOT NULL,
    [booking_snapshot_json] NVARCHAR(MAX) NOT NULL,
    [created_at] DATETIME2(3) NOT NULL,
    [updated_at] DATETIME2(3) NOT NULL,
    [canceled_at] DATETIME2(3) NULL,
    [row_version] ROWVERSION NOT NULL,
    CONSTRAINT [PK_virtual_reservations] PRIMARY KEY ([reservation_id])
);

CREATE TABLE [virtual_payments] (
    [payment_id] BIGINT NOT NULL,
    [reservation_id] BIGINT NOT NULL,
    [idempotency_key] UNIQUEIDENTIFIER NOT NULL,
    [request_hash] BINARY(32) NOT NULL,
    [amount] DECIMAL(12,2) NOT NULL,
    [status] VARCHAR(12) NOT NULL,
    [failure_code] VARCHAR(60) NULL,
    [created_at] DATETIME2(3) NOT NULL,
    [processed_at] DATETIME2(3) NULL,
    [refunded_at] DATETIME2(3) NULL,
    CONSTRAINT [PK_virtual_payments] PRIMARY KEY ([payment_id])
);

CREATE TABLE [reservation_logs] (
    [reservation_log_id] BIGINT NOT NULL,
    [reservation_id] BIGINT NOT NULL,
    [stock_id] BIGINT NOT NULL,
    [operation_id] UNIQUEIDENTIFIER NOT NULL,
    [event_type] VARCHAR(20) NOT NULL,
    [before_quantity] INT NOT NULL,
    [delta_quantity] INT NOT NULL,
    [after_quantity] INT NOT NULL,
    [before_status] VARCHAR(12) NULL,
    [after_status] VARCHAR(12) NOT NULL,
    [reason] NVARCHAR(500) NOT NULL,
    [created_at] DATETIME2(3) NOT NULL,
    CONSTRAINT [PK_reservation_logs] PRIMARY KEY ([reservation_log_id])
);

ALTER TABLE [keywords] ADD CONSTRAINT [FK_keywords_category_id] FOREIGN KEY ([category_id]) REFERENCES [keyword_categories] ([category_id]);
ALTER TABLE [recommendation_requests] ADD CONSTRAINT [FK_recommendation_requests_user_id] FOREIGN KEY ([user_id]) REFERENCES [users] ([user_id]);
ALTER TABLE [recommendation_candidates] ADD CONSTRAINT [FK_recommendation_candidates_request_id] FOREIGN KEY ([request_id]) REFERENCES [recommendation_requests] ([request_id]);
ALTER TABLE [recommendation_candidates] ADD CONSTRAINT [FK_recommendation_candidates_place_id] FOREIGN KEY ([place_id]) REFERENCES [places] ([place_id]);
ALTER TABLE [ai_jobs] ADD CONSTRAINT [FK_ai_jobs_user_id] FOREIGN KEY ([user_id]) REFERENCES [users] ([user_id]);
ALTER TABLE [ai_jobs] ADD CONSTRAINT [FK_ai_jobs_request_id] FOREIGN KEY ([request_id]) REFERENCES [recommendation_requests] ([request_id]);
ALTER TABLE [itineraries] ADD CONSTRAINT [FK_itineraries_user_id] FOREIGN KEY ([user_id]) REFERENCES [users] ([user_id]);
ALTER TABLE [itineraries] ADD CONSTRAINT [FK_itineraries_request_id] FOREIGN KEY ([request_id]) REFERENCES [recommendation_requests] ([request_id]);
ALTER TABLE [itineraries] ADD CONSTRAINT [FK_itineraries_destination_place_id] FOREIGN KEY ([destination_place_id]) REFERENCES [places] ([place_id]);
ALTER TABLE [itinerary_items] ADD CONSTRAINT [FK_itinerary_items_itinerary_id] FOREIGN KEY ([itinerary_id]) REFERENCES [itineraries] ([itinerary_id]);
ALTER TABLE [itinerary_items] ADD CONSTRAINT [FK_itinerary_items_place_id] FOREIGN KEY ([place_id]) REFERENCES [places] ([place_id]);
ALTER TABLE [favorites] ADD CONSTRAINT [FK_favorites_user_id] FOREIGN KEY ([user_id]) REFERENCES [users] ([user_id]);
ALTER TABLE [favorites] ADD CONSTRAINT [FK_favorites_place_id] FOREIGN KEY ([place_id]) REFERENCES [places] ([place_id]);
ALTER TABLE [place_ratings] ADD CONSTRAINT [FK_place_ratings_user_id] FOREIGN KEY ([user_id]) REFERENCES [users] ([user_id]);
ALTER TABLE [place_ratings] ADD CONSTRAINT [FK_place_ratings_place_id] FOREIGN KEY ([place_id]) REFERENCES [places] ([place_id]);
ALTER TABLE [place_rating_tags] ADD CONSTRAINT [FK_place_rating_tags_rating_id] FOREIGN KEY ([rating_id]) REFERENCES [place_ratings] ([rating_id]);
ALTER TABLE [place_rating_tags] ADD CONSTRAINT [FK_place_rating_tags_tag_id] FOREIGN KEY ([tag_id]) REFERENCES [rating_tags] ([tag_id]);
ALTER TABLE [operation_logs] ADD CONSTRAINT [FK_operation_logs_actor_user_id] FOREIGN KEY ([actor_user_id]) REFERENCES [users] ([user_id]);
ALTER TABLE [reservation_stock] ADD CONSTRAINT [FK_reservation_stock_place_id] FOREIGN KEY ([place_id]) REFERENCES [places] ([place_id]);
ALTER TABLE [virtual_reservations] ADD CONSTRAINT [FK_virtual_reservations_user_id] FOREIGN KEY ([user_id]) REFERENCES [users] ([user_id]);
ALTER TABLE [virtual_reservations] ADD CONSTRAINT [FK_virtual_reservations_stock_id] FOREIGN KEY ([stock_id]) REFERENCES [reservation_stock] ([stock_id]);
ALTER TABLE [virtual_reservations] ADD CONSTRAINT [FK_virtual_reservations_itinerary_id] FOREIGN KEY ([itinerary_id]) REFERENCES [itineraries] ([itinerary_id]);
ALTER TABLE [virtual_payments] ADD CONSTRAINT [FK_virtual_payments_reservation_id] FOREIGN KEY ([reservation_id]) REFERENCES [virtual_reservations] ([reservation_id]);
ALTER TABLE [reservation_logs] ADD CONSTRAINT [FK_reservation_logs_reservation_id] FOREIGN KEY ([reservation_id]) REFERENCES [virtual_reservations] ([reservation_id]);
ALTER TABLE [reservation_logs] ADD CONSTRAINT [FK_reservation_logs_stock_id] FOREIGN KEY ([stock_id]) REFERENCES [reservation_stock] ([stock_id]);
