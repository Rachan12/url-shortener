CREATE TABLE url_mapping (
    id BIGINT PRIMARY KEY,
    short_code VARCHAR(255) NOT NULL,
    long_url VARCHAR(2048) NOT NULL
);
