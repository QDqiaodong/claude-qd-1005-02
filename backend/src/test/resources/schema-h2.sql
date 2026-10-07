-- 回归测试专用 DDL：H2 MySQL 兼容模式，与生产 schema.sql 的表/列/索引保持一致。
-- H2 不支持 ENGINE / CHARSET 子句；UTC_TIMESTAMP() 绑定到已编译的测试辅助方法
-- （H2 CREATE ALIAS ... FOR，无需在 SQL 内嵌 Java 源码）。
SET MODE MySQL;
CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR "com.utility.tunnel.testsupport.H2Functions.utcTimestamp";

CREATE TABLE tunnel_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    seg_code VARCHAR(32) NOT NULL UNIQUE,
    seg_name VARCHAR(64),
    shape VARCHAR(16),
    length_m DECIMAL(10,2) NOT NULL,
    capacity_slots INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME
);

CREATE TABLE pipeline (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    segment_id BIGINT NOT NULL,
    line_type VARCHAR(16) NOT NULL,
    diameter_mm INT,
    pressure_level VARCHAR(16),
    status VARCHAR(16) NOT NULL,
    created_at DATETIME
);
CREATE INDEX idx_pipeline_segment ON pipeline (segment_id);

CREATE TABLE patrol_point (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    segment_id BIGINT NOT NULL,
    point_code VARCHAR(32) NOT NULL,
    location VARCHAR(128),
    last_patrol_at DATETIME,
    created_at DATETIME
);
CREATE INDEX idx_patrol_segment ON patrol_point (segment_id);

CREATE TABLE facility (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    segment_id BIGINT NOT NULL,
    facility_name VARCHAR(64) NOT NULL,
    facility_type VARCHAR(16) NOT NULL,
    location VARCHAR(128),
    status VARCHAR(16) NOT NULL,
    created_at DATETIME,
    updated_at DATETIME(3),
    CONSTRAINT uk_facility_segment_name UNIQUE (segment_id, facility_name)
);
CREATE INDEX idx_facility_segment ON facility (segment_id);

CREATE TABLE risk_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    segment_id BIGINT NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    source_id BIGINT NOT NULL,
    severity VARCHAR(16) NOT NULL,
    description VARCHAR(512) NOT NULL,
    found_at DATETIME NOT NULL,
    status VARCHAR(16) NOT NULL,
    handle_note VARCHAR(512),
    resolved_by VARCHAR(64),
    resolved_at DATETIME,
    created_at DATETIME
);
CREATE INDEX idx_risk_segment ON risk_event (segment_id);
CREATE INDEX idx_risk_status ON risk_event (status);
