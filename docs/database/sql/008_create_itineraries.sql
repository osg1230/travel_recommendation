-- 006·007 적용 후 실행한다. 기존 장소·요청·작업 데이터는 변경하지 않는다.
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF OBJECT_ID('dbo.itineraries','U') IS NOT NULL
    THROW 50000, 'Saved itineraries already exist. Review migration before proceeding.', 1;
CREATE TABLE dbo.itineraries (
 itinerary_id BIGINT IDENTITY PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES dbo.users(user_id),
 request_id BIGINT NULL REFERENCES dbo.recommendation_requests(request_id),
 destination_place_id BIGINT NOT NULL REFERENCES dbo.places(place_id),
 title NVARCHAR(150) NOT NULL,
 start_date DATE NOT NULL,
 end_date DATE NOT NULL,
 transportation VARCHAR(20) NOT NULL CHECK(transportation IN ('CAR','TRANSIT','WALK','MIXED','ANY')),
 conditions_snapshot_json NVARCHAR(MAX) NOT NULL CHECK(ISJSON(conditions_snapshot_json)=1),
 created_at DATETIME2(3) NOT NULL DEFAULT SYSDATETIME(),
 updated_at DATETIME2(3) NOT NULL DEFAULT SYSDATETIME(),
 row_version ROWVERSION NOT NULL,
 CONSTRAINT CK_itineraries_dates CHECK(end_date >= start_date)
);
CREATE INDEX IX_itineraries_user ON dbo.itineraries(user_id, start_date DESC);
CREATE TABLE dbo.itinerary_items (
 item_id BIGINT IDENTITY PRIMARY KEY,
 itinerary_id BIGINT NOT NULL REFERENCES dbo.itineraries(itinerary_id),
 day_no SMALLINT NOT NULL CHECK(day_no > 0),
 sequence_no SMALLINT NOT NULL CHECK(sequence_no > 0),
 item_type VARCHAR(20) NOT NULL CHECK(item_type IN ('VISIT','MEAL','TRANSIT','STAY','FREE')),
 place_id BIGINT NULL REFERENCES dbo.places(place_id),
 start_at DATETIME2(3) NOT NULL,
 end_at DATETIME2(3) NOT NULL,
 activity NVARCHAR(1000) NOT NULL,
 travel_minutes INT NULL CHECK(travel_minutes >= 0),
 place_snapshot_json NVARCHAR(MAX) NULL CHECK(place_snapshot_json IS NULL OR ISJSON(place_snapshot_json)=1),
 transport_json NVARCHAR(MAX) NULL CHECK(transport_json IS NULL OR ISJSON(transport_json)=1),
 food_guide_json NVARCHAR(MAX) NULL CHECK(food_guide_json IS NULL OR ISJSON(food_guide_json)=1),
 created_at DATETIME2(3) NOT NULL DEFAULT SYSDATETIME(),
 updated_at DATETIME2(3) NOT NULL DEFAULT SYSDATETIME(),
 CONSTRAINT CK_itinerary_items_time CHECK(end_at > start_at),
 CONSTRAINT UQ_itinerary_items_order UNIQUE(itinerary_id, day_no, sequence_no)
);
CREATE INDEX IX_itinerary_items_place ON dbo.itinerary_items(place_id);
COMMIT;
