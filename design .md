# URL Shortener - System Design Document

## Table of Contents
1. [High-Level Design (HLD)](#high-level-design-hld)
2. [Low-Level Design (LLD)](#low-level-design-lld)
3. [Design Patterns](#design-patterns)
4. [Scalability Considerations](#scalability-considerations)
5. [Trade-offs and Design Decisions](#trade-offs-and-design-decisions)

---

# High-Level Design (HLD)

## 1. System Overview

### 1.1 Purpose
A production-grade URL shortener service that converts long URLs into short, shareable codes with high availability, low latency, and horizontal scalability.

### 1.2 Key Requirements

#### Functional Requirements
- Shorten long URLs to unique short codes
- Redirect short URLs to original long URLs
- High availability (99.9% uptime)
- Low latency redirects (<100ms)

#### Non-Functional Requirements
- **Scalability**: Horizontal scaling via database sharding
- **Performance**: Sub-millisecond cache lookups
- **Reliability**: ACID compliance for data consistency
- **Uniqueness**: Guaranteed unique IDs across distributed systems

### 1.3 Capacity Estimation

Assuming moderate scale:
- **Writes**: 1000 URL shortenings/second
- **Reads**: 10,000 redirects/second (10:1 read/write ratio)
- **Storage**: 1 billion URLs × 500 bytes = 500GB
- **Cache**: 20% hot data (80/20 rule) = 100GB Redis cache

---

## 2. System Architecture

### 2.1 High-Level Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                         Client Layer                             │
│                     (Web/Mobile/API Users)                       │
└──────────────────────────────┬──────────────────────────────────┘
                               │
                               │ HTTP/HTTPS
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Load Balancer                               │
│              (nginx/HAProxy - future enhancement)                │
└──────────────────────────────┬──────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                   Application Layer                              │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │        Spring Boot Application (Stateless)               │   │
│  │                                                           │   │
│  │  ┌──────────────────┐  ┌──────────────────┐             │   │
│  │  │   Controller     │  │   Service Layer   │             │   │
│  │  │   - REST API     │→ │   - Business      │             │   │
│  │  │   - Validation   │  │     Logic         │             │   │
│  │  └──────────────────┘  └──────────────────┘             │   │
│  │                                                           │   │
│  │  ┌──────────────────┐  ┌──────────────────┐             │   │
│  │  │   Utilities      │  │   Repository      │             │   │
│  │  │   - Snowflake ID │  │   - Data Access   │             │   │
│  │  │   - Base62       │  │     (JPA)         │             │   │
│  │  └──────────────────┘  └──────────────────┘             │   │
│  └─────────────────────────────────────────────────────────┘   │
└────────────────┬─────────────────────────┬──────────────────────┘
                 │                         │
        ┌────────▼─────────┐      ┌────────▼────────┐
        │  Cache Layer     │      │   Data Layer    │
        └──────────────────┘      └─────────────────┘
                 │                         │
                 ▼                         ▼
┌──────────────────────────┐  ┌──────────────────────────────────┐
│      Redis Cache         │  │   Database Sharding Layer        │
│                          │  │                                  │
│  - Key: shortCode       │  │  ┌────────────────────────────┐  │
│  - Value: longUrl       │  │  │  ShardRoutingDataSource    │  │
│  - TTL: 1 day           │  │  │  (Dynamic Routing)         │  │
│  - In-memory storage    │  │  └──────────┬─────────────────┘  │
│                          │  │             │                    │
└──────────────────────────┘  │  ┌──────────▼──────────┐        │
                               │  │  ThreadLocal Context │        │
                               │  │  (Shard Key Storage)│        │
                               │  └──────────┬──────────┘        │
                               │             │                    │
                               │  ┌──────────▼──────────────────┐│
                               │  │   HikariCP Connection Pools ││
                               │  └──────────┬──────────────────┘│
                               └─────────────┼───────────────────┘
                                             │
                          ┌──────────────────┴───────────────────┐
                          │                                      │
                ┌─────────▼────────┐              ┌──────────────▼─────────┐
                │ PostgreSQL       │              │ PostgreSQL             │
                │ Shard 0          │              │ Shard 1                │
                │                  │              │                        │
                │ - url_mapping    │              │ - url_mapping          │
                │   table          │              │   table                │
                │ - Even timestamp │              │ - Odd timestamp        │
                │   IDs            │              │   IDs                  │
                └──────────────────┘              └────────────────────────┘
```

### 2.2 Component Description

#### Application Layer
- **REST Controller**: Handles HTTP requests/responses
- **Service Layer**: Business logic, ID generation, shard routing
- **Repository Layer**: Data access abstraction (Spring Data JPA)
- **Utility Components**: Snowflake ID generator, Base62 encoder

#### Cache Layer
- **Redis**: In-memory cache for fast lookups
- **Strategy**: Cache-aside (lazy loading)
- **TTL**: 1 day to prevent stale data

#### Data Layer
- **Database Sharding**: 2 PostgreSQL instances (horizontally partitioned)
- **Routing**: Dynamic routing based on timestamp modulo
- **Connection Pooling**: HikariCP for efficient connection management

---

## 3. Data Flow

### 3.1 URL Shortening Flow (Write Path)

```
┌──────────────────────────────────────────────────────────────────┐
│ Step 1: Client Request                                            │
│ POST /shorten                                                     │
│ Body: "https://www.example.com/very/long/url"                    │
└──────────────────────┬───────────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────────┐
│ Step 2: Controller receives and validates                        │
│ - Validate URL format                                             │
│ - Forward to service layer                                        │
└──────────────────────┬───────────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────────┐
│ Step 3: Generate Unique ID (Snowflake)                           │
│ - Generate 64-bit ID: [41-bit timestamp][10-bit nodeID][12-bit seq]│
│ - Example: 123456789012345                                       │
└──────────────────────┬───────────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────────┐
│ Step 4: Encode ID to Base62                                      │
│ - Convert 123456789012345 → "abc123XYZ"                          │
│ - URL-friendly, compact representation                            │
└──────────────────────┬───────────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────────┐
│ Step 5: Determine Database Shard                                 │
│ - Extract timestamp from Snowflake ID: id >> 22                  │
│ - Calculate shard: shardKey = timestamp % 2                      │
│ - Result: 0 (shard0) or 1 (shard1)                              │
└──────────────────────┬───────────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────────┐
│ Step 6: Set Shard Context                                        │
│ - Store "shard0" or "shard1" in ThreadLocal                      │
│ - Thread-safe isolation                                           │
└──────────────────────┬───────────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────────┐
│ Step 7: Save to Database                                         │
│ - JPA repository.save(urlMapping)                                │
│ - Routing DataSource reads ThreadLocal                           │
│ - Routes to correct shard (shard0 or shard1)                    │
│ - INSERT INTO url_mapping VALUES (...)                           │
└──────────────────────┬───────────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────────┐
│ Step 8: Clear ThreadLocal Context                                │
│ - Remove shard key from ThreadLocal                              │
│ - Prevent memory leaks                                            │
└──────────────────────┬───────────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────────┐
│ Step 9: Cache in Redis                                           │
│ - SET "abc123XYZ" = "https://www.example.com/very/long/url"     │
│ - TTL: 86400 seconds (1 day)                                     │
└──────────────────────┬───────────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────────┐
│ Step 10: Return Response                                         │
│ Response: "http://localhost:8080/abc123XYZ"                      │
└──────────────────────────────────────────────────────────────────┘
```

### 3.2 URL Redirect Flow (Read Path)

```
┌──────────────────────────────────────────────────────────────────┐
│ Step 1: Client Request                                            │
│ GET /abc123XYZ                                                    │
└──────────────────────┬───────────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────────┐
│ Step 2: Controller extracts short code                           │
│ - shortCode = "abc123XYZ"                                        │
│ - Forward to service layer                                        │
└──────────────────────┬───────────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────────┐
│ Step 3: Check Redis Cache                                        │
│ - GET "abc123XYZ"                                                │
│ - Cache hit? → Return immediately (fast path)                    │
│ - Cache miss? → Continue to database (slow path)                 │
└────────────┬─────────────────────────────┬──────────────────────┘
             │ Cache Hit                   │ Cache Miss
             ▼                             ▼
┌────────────────────────┐   ┌─────────────────────────────────────┐
│ Step 4a: Return URL    │   │ Step 4b: Decode Short Code          │
│ - longUrl from cache   │   │ - Base62 decode → Snowflake ID      │
│ - Skip database        │   │ - "abc123XYZ" → 123456789012345     │
└────────────┬───────────┘   └─────────────┬───────────────────────┘
             │                             │
             │               ┌─────────────▼───────────────────────┐
             │               │ Step 5: Calculate Shard             │
             │               │ - timestamp = id >> 22              │
             │               │ - shardKey = timestamp % 2          │
             │               └─────────────┬───────────────────────┘
             │                             │
             │               ┌─────────────▼───────────────────────┐
             │               │ Step 6: Set Shard Context           │
             │               │ - ThreadLocal.set("shard0")         │
             │               └─────────────┬───────────────────────┘
             │                             │
             │               ┌─────────────▼───────────────────────┐
             │               │ Step 7: Query Database              │
             │               │ - findByShortCode("abc123XYZ")      │
             │               │ - Routes to correct shard           │
             │               └─────────────┬───────────────────────┘
             │                             │
             │               ┌─────────────▼───────────────────────┐
             │               │ Step 8: Clear ThreadLocal           │
             │               │ - ThreadLocal.remove()              │
             │               └─────────────┬───────────────────────┘
             │                             │
             │               ┌─────────────▼───────────────────────┐
             │               │ Step 9: Cache Result (Cache Warming)│
             │               │ - SET "abc123XYZ" = longUrl         │
             │               │ - TTL: 1 day                        │
             │               └─────────────┬───────────────────────┘
             │                             │
             └─────────────────────────────┘
                             │
┌────────────────────────────▼──────────────────────────────────────┐
│ Step 10: Return HTTP 302 Redirect                                │
│ - Location: https://www.example.com/very/long/url                │
│ - Or 404 Not Found if URL doesn't exist                          │
└───────────────────────────────────────────────────────────────────┘
```

---

## 4. Database Design

### 4.1 Database Schema

```sql
-- Table: url_mapping (exists in BOTH shard0 and shard1)

CREATE TABLE url_mapping (
    id          BIGINT PRIMARY KEY,           -- Snowflake ID (unique across all shards)
    short_code  VARCHAR(11) UNIQUE NOT NULL,  -- Base62 encoded ID
    long_url    VARCHAR(2048) NOT NULL,       -- Original URL
    created_at  TIMESTAMP DEFAULT NOW()       -- Creation timestamp
);

-- Indexes
CREATE UNIQUE INDEX idx_short_code ON url_mapping(short_code);
CREATE INDEX idx_created_at ON url_mapping(created_at);
```

### 4.2 Sharding Strategy

#### Horizontal Sharding (Range-Based)
- **Sharding Key**: Timestamp component of Snowflake ID
- **Algorithm**: `shardKey = (timestamp % 2)`
- **Distribution**: Time-based partitioning ensures even distribution

#### Shard Allocation
```
Shard 0: timestamp % 2 == 0 (even timestamps)
Shard 1: timestamp % 2 == 1 (odd timestamps)
```

#### Example Distribution
```
Time: 2024-01-01 00:00:00.000 → timestamp % 2 = 0 → shard0
Time: 2024-01-01 00:00:00.001 → timestamp % 2 = 1 → shard1
Time: 2024-01-01 00:00:00.002 → timestamp % 2 = 0 → shard0
Time: 2024-01-01 00:00:00.003 → timestamp % 2 = 1 → shard1
```

### 4.3 Data Characteristics

| Aspect | Value |
|--------|-------|
| Row Size | ~500 bytes average |
| ID Size | 8 bytes (BIGINT) |
| Short Code Size | 7-11 bytes (VARCHAR) |
| Long URL Size | 100-2000 bytes average |
| Expected Rows | Millions to billions |

---

## 5. Caching Strategy

### 5.1 Cache-Aside Pattern (Lazy Loading)

#### Write Operation
1. Write to database first
2. Then write to cache
3. Set TTL to prevent stale data

#### Read Operation
1. Check cache first
2. If cache hit: return immediately
3. If cache miss: query database, populate cache, return

### 5.2 Cache Configuration

```
Technology: Redis
Data Structure: String (key-value)
Key: short_code (e.g., "abc123XYZ")
Value: long_url (e.g., "https://example.com")
TTL: 86400 seconds (1 day)
Eviction Policy: LRU (Least Recently Used)
```

### 5.3 Cache Performance Metrics

Assuming 80/20 rule (80% of traffic hits 20% of URLs):
- **Expected Cache Hit Rate**: 70-80%
- **Cache Latency**: <1ms (in-memory)
- **Database Latency**: 10-50ms (disk I/O)
- **Performance Gain**: 10-50x faster for cached URLs

---

## 6. ID Generation: Snowflake Algorithm

### 6.1 Snowflake ID Structure (64 bits)

```
┌─────────────────────────────────────────────────────────────────┐
│                      64-bit Snowflake ID                         │
└─────────────────────────────────────────────────────────────────┘

Bit Layout:
┌───────────────┬────────────┬──────────────┐
│ 41 bits       │ 10 bits    │ 12 bits      │
│ Timestamp     │ Node ID    │ Sequence     │
│ (milliseconds)│ (1024 max) │ (4096/ms)    │
└───────────────┴────────────┴──────────────┘

Range:
- Timestamp: 0 to 2^41-1 (69 years from epoch)
- Node ID: 0 to 1023 (1024 nodes max)
- Sequence: 0 to 4095 (4096 IDs per millisecond)
```

### 6.2 Snowflake Properties

| Property | Value | Explanation |
|----------|-------|-------------|
| Time-ordered | Yes | IDs increase with time |
| Unique | Guaranteed | Across all nodes and time |
| Size | 64 bits | Compact, efficient |
| Throughput | 4M+ IDs/sec/node | 4096 per millisecond |
| Max Nodes | 1024 | 10-bit node ID |
| Lifespan | 69 years | 41-bit timestamp |

### 6.3 Epoch Configuration

```java
Custom Epoch: January 1, 2021, 00:00:00 UTC
System Epoch: January 1, 1970, 00:00:00 UTC

timestamp = (currentTimeMillis - customEpoch)
```

---

## 7. Encoding: Base62

### 7.1 Base62 Character Set

```
Alphabet: [0-9][a-z][A-Z]
Total Characters: 62

0123456789          (10 digits)
abcdefghijklmnopqrstuvwxyz  (26 lowercase)
ABCDEFGHIJKLMNOPQRSTUVWXYZ  (26 uppercase)
```

### 7.2 Encoding Example

```
Decimal ID: 123456789012345
Base62 Encoded: "abc123XYZ" (example)

Properties:
- URL-safe (no special characters)
- Compact (shorter than decimal)
- Reversible (decode to get original ID)
```

### 7.3 Short Code Length

| Max Value | Short Code Length |
|-----------|-------------------|
| 62^6 | 6 characters (56.8 billion) |
| 62^7 | 7 characters (3.5 trillion) |
| 62^8 | 8 characters (218 trillion) |
| 62^11 | 11 characters (2^64 max) |

---

## 8. Technology Stack Summary

| Layer | Technology | Purpose |
|-------|------------|---------|
| **Application** | Spring Boot 3.1.5 | Framework, DI, REST API |
| **Language** | Java 17 | LTS version, modern features |
| **Cache** | Redis | In-memory key-value store |
| **Database** | PostgreSQL | ACID-compliant relational DB |
| **Connection Pool** | HikariCP | High-performance pooling |
| **Migration** | Flyway | Database version control |
| **Build** | Maven | Dependency & build management |
| **ORM** | Spring Data JPA | Data access abstraction |
| **Serialization** | Lombok | Boilerplate reduction |

---

# Low-Level Design (LLD)

## 1. Class Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                      Controller Layer                            │
└─────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────┐
│   UrlShortenerController              │
│───────────────────────────────────────│
│ - urlShortenerService: Service        │
│───────────────────────────────────────│
│ + shortenUrl(longUrl: String): String │
│ + redirect(shortCode: String): void   │
└───────────────┬───────────────────────┘
                │ uses
                ▼
┌─────────────────────────────────────────────────────────────────┐
│                       Service Layer                              │
└─────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│   UrlShortenerService                                           │
│────────────────────────────────────────────────────────────────│
│ - snowflakeIdGenerator: SnowflakeIdGenerator                   │
│ - base62: Base62                                               │
│ - urlMappingRepository: UrlMappingRepository                   │
│ - redisTemplate: StringRedisTemplate                           │
│────────────────────────────────────────────────────────────────│
│ + shortenUrl(longUrl: String): String                          │
│ + getLongUrl(shortCode: String): String                        │
│ - determineShard(id: long): int                                │
└────────────────┬───────────────────┬───────────────────────────┘
                 │ uses              │ uses
        ┌────────▼────────┐   ┌──────▼──────────┐
        │                 │   │                 │
┌───────▼─────────────┐   │   │  ┌──────────────▼─────────────┐
│ SnowflakeIdGenerator│   │   │  │ UrlMappingRepository       │
│─────────────────────│   │   │  │────────────────────────────│
│ - nodeId: long      │   │   │  │                            │
│ - sequence: long    │   │   │  │ + save(entity): Entity     │
│ - lastTimestamp     │   │   │  │ + findByShortCode(code)    │
│─────────────────────│   │   │  └────────────────────────────┘
│ + nextId(): long    │   │   │
└─────────────────────┘   │   │
                          │   │
┌─────────────────────┐   │   │
│      Base62         │   │   │
│─────────────────────│◄──┘   │
│ - BASE62_CHARS      │        │
│─────────────────────│        │
│ + encode(n): String │        │
│ + decode(s): long   │        │
└─────────────────────┘        │
                               │
┌─────────────────────────────────────────────────────────────────┐
│                      Entity Layer                                │
└─────────────────────────────────────────────────────────────────┘
                               │
                               │
                    ┌──────────▼──────────┐
                    │    UrlMapping       │
                    │─────────────────────│
                    │ - id: Long          │
                    │ - shortCode: String │
                    │ - longUrl: String   │
                    │ - createdAt: Date   │
                    │─────────────────────│
                    │ + getters/setters   │
                    └─────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    Configuration Layer                           │
└─────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│               DataSourceConfig                                │
│──────────────────────────────────────────────────────────────│
│ + shard0Properties(): DataSourceProperties                   │
│ + shard0(properties): DataSource                             │
│ + shard1Properties(): DataSourceProperties                   │
│ + shard1(properties): DataSource                             │
│ + dataSource(shard0, shard1): DataSource                     │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│          ShardRoutingDataSource                               │
│──────────────────────────────────────────────────────────────│
│ (extends AbstractRoutingDataSource)                          │
│──────────────────────────────────────────────────────────────│
│ # determineCurrentLookupKey(): Object                        │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│          DataSourceContextHolder                              │
│──────────────────────────────────────────────────────────────│
│ - contextHolder: ThreadLocal<String>                         │
│──────────────────────────────────────────────────────────────│
│ + setDataSourceKey(key: String): void                        │
│ + getDataSourceKey(): String                                 │
│ + clearDataSourceKey(): void                                 │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│               FlywayConfig                                    │
│──────────────────────────────────────────────────────────────│
│ + flywayMigrationStrategy(): Strategy                        │
│ + flywayShard0(dataSource): Flyway                           │
│ + flywayShard1(dataSource): Flyway                           │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. Component-Level Design

### 2.1 UrlShortenerController

**Responsibility**: HTTP request handling and response formatting

```java
@RestController
public class UrlShortenerController {

    @Autowired
    private UrlShortenerService urlShortenerService;

    // Endpoint: POST /shorten
    @PostMapping("/shorten")
    public ResponseEntity<String> shortenUrl(@RequestBody String longUrl) {
        // 1. Validate input
        if (longUrl == null || longUrl.isEmpty()) {
            return ResponseEntity.badRequest().body("URL cannot be empty");
        }

        // 2. Call service layer
        String shortCode = urlShortenerService.shortenUrl(longUrl);

        // 3. Build short URL
        String shortUrl = "http://localhost:8080/" + shortCode;

        // 4. Return response
        return ResponseEntity.ok(shortUrl);
    }

    // Endpoint: GET /{shortCode}
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        // 1. Get long URL from service
        String longUrl = urlShortenerService.getLongUrl(shortCode);

        // 2. Check if found
        if (longUrl == null) {
            return ResponseEntity.notFound().build();
        }

        // 3. Return 302 redirect
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(longUrl))
                .build();
    }
}
```

**Key Points**:
- Thin layer - no business logic
- Input validation
- Delegates to service layer
- Returns appropriate HTTP status codes

---

### 2.2 UrlShortenerService

**Responsibility**: Core business logic, orchestration

```java
@Service
public class UrlShortenerService {

    @Autowired
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Autowired
    private Base62 base62;

    @Autowired
    private UrlMappingRepository urlMappingRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * Shortens a long URL
     * Algorithm:
     * 1. Generate unique Snowflake ID
     * 2. Encode to Base62
     * 3. Determine shard from timestamp
     * 4. Save to database with shard routing
     * 5. Cache in Redis
     */
    public String shortenUrl(String longUrl) {
        // Step 1: Generate unique ID
        long id = snowflakeIdGenerator.nextId();

        // Step 2: Encode to Base62
        String shortCode = base62.encode(id);

        // Step 3: Extract timestamp and determine shard
        long timestamp = id >> 22;  // Extract 41-bit timestamp
        int shardKey = (int) (timestamp % 2);

        // Step 4: Set shard context and save
        try {
            DataSourceContextHolder.setDataSourceKey("shard" + shardKey);

            UrlMapping urlMapping = new UrlMapping();
            urlMapping.setId(id);
            urlMapping.setShortCode(shortCode);
            urlMapping.setLongUrl(longUrl);

            urlMappingRepository.save(urlMapping);
        } finally {
            DataSourceContextHolder.clearDataSourceKey();
        }

        // Step 5: Cache in Redis
        redisTemplate.opsForValue().set(
            shortCode,
            longUrl,
            1,
            TimeUnit.DAYS
        );

        return shortCode;
    }

    /**
     * Retrieves long URL from short code
     * Algorithm:
     * 1. Check Redis cache first
     * 2. If miss, decode short code to ID
     * 3. Determine shard from ID
     * 4. Query database with shard routing
     * 5. Cache result and return
     */
    public String getLongUrl(String shortCode) {
        // Step 1: Check cache
        String cached = redisTemplate.opsForValue().get(shortCode);
        if (cached != null) {
            return cached;  // Cache hit - fast path
        }

        // Step 2: Cache miss - decode to get ID
        long id = base62.decode(shortCode);

        // Step 3: Determine shard
        long timestamp = id >> 22;
        int shardKey = (int) (timestamp % 2);

        // Step 4: Query database
        String longUrl = null;
        try {
            DataSourceContextHolder.setDataSourceKey("shard" + shardKey);

            UrlMapping urlMapping = urlMappingRepository
                .findByShortCode(shortCode)
                .orElse(null);

            if (urlMapping != null) {
                longUrl = urlMapping.getLongUrl();
            }
        } finally {
            DataSourceContextHolder.clearDataSourceKey();
        }

        // Step 5: Cache result (cache warming)
        if (longUrl != null) {
            redisTemplate.opsForValue().set(
                shortCode,
                longUrl,
                1,
                TimeUnit.DAYS
            );
        }

        return longUrl;
    }
}
```

**Key Design Decisions**:
- Try-finally ensures ThreadLocal cleanup
- Cache-aside pattern implementation
- Shard routing via bit manipulation
- Separation of concerns (delegates ID generation, encoding)

---

### 2.3 SnowflakeIdGenerator

**Responsibility**: Distributed unique ID generation

```java
@Component
public class SnowflakeIdGenerator {

    // Epoch: Jan 1, 2021 00:00:00 UTC
    private static final long EPOCH = 1609459200000L;

    // Bit allocation
    private static final long NODE_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;

    // Max values
    private static final long MAX_NODE_ID = (1L << NODE_ID_BITS) - 1;  // 1023
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1; // 4095

    // Bit shifts
    private static final long NODE_SHIFT = SEQUENCE_BITS;              // 12
    private static final long TIMESTAMP_SHIFT = NODE_ID_BITS + SEQUENCE_BITS; // 22

    private long nodeId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator() {
        this.nodeId = createNodeId();
    }

    /**
     * Generate next unique ID
     * Thread-safe via synchronized
     */
    public synchronized long nextId() {
        long currentTimestamp = System.currentTimeMillis();

        // Clock moved backwards - error
        if (currentTimestamp < lastTimestamp) {
            throw new RuntimeException("Clock moved backwards");
        }

        // Same millisecond - increment sequence
        if (currentTimestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;

            // Sequence overflow - wait for next millisecond
            if (sequence == 0) {
                currentTimestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            // New millisecond - reset sequence
            sequence = 0L;
        }

        lastTimestamp = currentTimestamp;

        // Build ID: [timestamp][nodeId][sequence]
        long timestamp = currentTimestamp - EPOCH;

        return (timestamp << TIMESTAMP_SHIFT)
             | (nodeId << NODE_SHIFT)
             | sequence;
    }

    private long waitNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    private long createNodeId() {
        // Get node ID from system property or use hash
        String nodeIdStr = System.getProperty("nodeId", "0");
        return nodeIdStr.hashCode() & MAX_NODE_ID;
    }
}
```

**Algorithm Complexity**:
- Time: O(1) average, O(n) worst case (if waiting for next millisecond)
- Space: O(1)
- Thread-safety: Synchronized method

**Production Considerations**:
- Distribute unique node IDs (via config, Zookeeper, etc.)
- Handle clock drift/backwards
- Monitor sequence exhaustion

---

### 2.4 Base62 Encoder

**Responsibility**: Bidirectional Base62 encoding/decoding

```java
@Component
public class Base62 {

    private static final String BASE62_CHARS =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /**
     * Encode decimal number to Base62 string
     * Example: 12345678901 → "3nLqm7H"
     */
    public String encode(long num) {
        if (num == 0) {
            return "0";
        }

        StringBuilder sb = new StringBuilder();

        while (num > 0) {
            int remainder = (int) (num % 62);
            sb.append(BASE62_CHARS.charAt(remainder));
            num = num / 62;
        }

        return sb.reverse().toString();
    }

    /**
     * Decode Base62 string to decimal number
     * Example: "3nLqm7H" → 12345678901
     */
    public long decode(String str) {
        long num = 0;

        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            int index = BASE62_CHARS.indexOf(c);

            if (index == -1) {
                throw new IllegalArgumentException("Invalid Base62 character: " + c);
            }

            num = num * 62 + index;
        }

        return num;
    }
}
```

**Algorithm Complexity**:
- Encode: O(log₆₂(n)) ≈ O(log n)
- Decode: O(m) where m = string length
- Space: O(1) excluding output

---

### 2.5 Database Sharding Components

#### DataSourceConfig

**Responsibility**: Configure multiple data sources and routing

```java
@Configuration
public class DataSourceConfig {

    // Create properties bean for shard0
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.shard0")
    public DataSourceProperties shard0Properties() {
        return new DataSourceProperties();
    }

    // Create actual DataSource for shard0
    @Bean(name = "shard0")
    public DataSource shard0(
        @Qualifier("shard0Properties") DataSourceProperties properties
    ) {
        return properties.initializeDataSourceBuilder()
            .type(HikariDataSource.class)
            .build();
    }

    // Repeat for shard1...

    // Create routing DataSource (PRIMARY)
    @Bean
    @Primary
    public DataSource dataSource(
        @Qualifier("shard0") DataSource shard0,
        @Qualifier("shard1") DataSource shard1
    ) {
        ShardRoutingDataSource routingDataSource = new ShardRoutingDataSource();

        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put("shard0", shard0);
        targetDataSources.put("shard1", shard1);

        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.setDefaultTargetDataSource(shard0);

        return routingDataSource;
    }
}
```

**Configuration Properties** (application.properties):
```properties
# Shard 0
spring.datasource.shard0.url=jdbc:postgresql://localhost:5432/shard0
spring.datasource.shard0.username=postgres
spring.datasource.shard0.password=postgres
spring.datasource.shard0.driver-class-name=org.postgresql.Driver

# Shard 1
spring.datasource.shard1.url=jdbc:postgresql://localhost:5432/shard1
spring.datasource.shard1.username=postgres
spring.datasource.shard1.password=postgres
spring.datasource.shard1.driver-class-name=org.postgresql.Driver
```

---

#### ShardRoutingDataSource

**Responsibility**: Dynamic routing logic

```java
public class ShardRoutingDataSource extends AbstractRoutingDataSource {

    /**
     * Called by Spring to determine which DataSource to use
     * Returns the lookup key ("shard0" or "shard1")
     */
    @Override
    protected Object determineCurrentLookupKey() {
        return DataSourceContextHolder.getDataSourceKey();
    }
}
```

**How it works**:
```
1. JPA calls: dataSource.getConnection()
2. AbstractRoutingDataSource intercepts
3. Calls: determineCurrentLookupKey()
4. Gets: "shard1" from ThreadLocal
5. Looks up: targetDataSources.get("shard1")
6. Returns: shard1 HikariCP connection
7. JPA executes query on shard1 database
```

---

#### DataSourceContextHolder

**Responsibility**: Thread-safe shard context storage

```java
public class DataSourceContextHolder {

    private static final ThreadLocal<String> contextHolder = new ThreadLocal<>();

    public static void setDataSourceKey(String key) {
        contextHolder.set(key);
    }

    public static String getDataSourceKey() {
        return contextHolder.get();
    }

    public static void clearDataSourceKey() {
        contextHolder.remove();  // CRITICAL: prevent memory leaks
    }
}
```

**ThreadLocal Behavior**:
```
Thread 1: contextHolder = "shard0"
Thread 2: contextHolder = "shard1"
Thread 3: contextHolder = "shard0"

Each thread has its own isolated value
No interference between threads
```

---

### 2.6 Repository Layer

```java
@Repository
public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    /**
     * Find URL mapping by short code
     * Automatically routed to correct shard via ShardRoutingDataSource
     */
    Optional<UrlMapping> findByShortCode(String shortCode);
}
```

**JPA Generated Query**:
```sql
SELECT * FROM url_mapping WHERE short_code = ?
```

**Routing Happens Automatically**:
- Service sets ThreadLocal context
- Repository calls JPA
- JPA calls DataSource
- ShardRoutingDataSource reads ThreadLocal
- Query executes on correct shard

---

### 2.7 Entity Model

```java
@Entity
@Table(name = "url_mapping")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UrlMapping {

    @Id
    private Long id;  // Snowflake ID

    @Column(nullable = false, unique = true, length = 11)
    private String shortCode;  // Base62 encoded ID

    @Column(nullable = false, length = 2048)
    private String longUrl;  // Original URL

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;  // Auto-populated
}
```

**Annotations Explained**:
- `@Entity`: JPA entity (maps to database table)
- `@Table`: Specifies table name
- `@Id`: Primary key
- `@Column`: Column constraints
- `@Data`: Lombok - generates getters/setters/toString/equals/hashCode
- `@CreationTimestamp`: Auto-populated on insert

---

## 3. Sequence Diagrams

### 3.1 URL Shortening Sequence

```
Client    Controller    Service    IDGen    Base62    ContextHolder    Repository    DataSource    Redis    Database
  │           │            │         │        │            │              │             │           │         │
  │ POST      │            │         │        │            │              │             │           │         │
  │ /shorten  │            │         │        │            │              │             │           │         │
  ├──────────>│            │         │        │            │              │             │           │         │
  │           │shortenUrl()│         │        │            │              │             │           │         │
  │           ├───────────>│         │        │            │              │             │           │         │
  │           │            │nextId() │        │            │              │             │           │         │
  │           │            ├────────>│        │            │              │             │           │         │
  │           │            │   ID    │        │            │              │             │           │         │
  │           │            │<────────┤        │            │              │             │           │         │
  │           │            │encode(ID)        │            │              │             │           │         │
  │           │            ├─────────────────>│            │              │             │           │         │
  │           │            │   shortCode      │            │              │             │           │         │
  │           │            │<─────────────────┤            │              │             │           │         │
  │           │            │setDataSourceKey("shard0")     │              │             │           │         │
  │           │            ├──────────────────────────────>│              │             │           │         │
  │           │            │                               │              │             │           │         │
  │           │            │save(urlMapping)               │              │             │           │         │
  │           │            ├──────────────────────────────────────────────>│             │           │         │
  │           │            │                               │              │getConnection()          │         │
  │           │            │                               │              ├────────────>│           │         │
  │           │            │                               │              │getDataSourceKey()       │         │
  │           │            │                               │              │<────────────┤           │         │
  │           │            │                               │              │  "shard0"   │           │         │
  │           │            │                               │              ├────────────>│           │         │
  │           │            │                               │              │ connection  │           │         │
  │           │            │                               │              │<────────────┤           │         │
  │           │            │                               │              │INSERT       │           │         │
  │           │            │                               │              ├─────────────────────────────────>│
  │           │            │clearDataSourceKey()           │              │             │           │  OK    │
  │           │            ├──────────────────────────────>│              │             │           │<───────┤
  │           │            │                               │              │             │           │         │
  │           │            │cache(shortCode, longUrl)      │              │             │           │         │
  │           │            ├───────────────────────────────────────────────────────────────────────>│         │
  │           │            │                               │              │             │           │  OK    │
  │           │  shortCode │                               │              │             │           │<───────┤
  │           │<───────────┤                               │              │             │           │         │
  │  shortUrl │            │                               │              │             │           │         │
  │<──────────┤            │                               │              │             │           │         │
```

---

### 3.2 URL Redirect Sequence (Cache Hit)

```
Client    Controller    Service    Redis
  │           │            │         │
  │ GET       │            │         │
  │ /abc123   │            │         │
  ├──────────>│            │         │
  │           │getLongUrl()│         │
  │           ├───────────>│         │
  │           │            │ GET     │
  │           │            ├────────>│
  │           │            │ longUrl │
  │           │  longUrl   │<────────┤
  │           │<───────────┤         │
  │  302      │            │         │
  │  Redirect │            │         │
  │<──────────┤            │         │
```

---

### 3.3 URL Redirect Sequence (Cache Miss)

```
Client    Controller    Service    Redis    Base62    ContextHolder    Repository    Database
  │           │            │         │        │            │              │             │
  │ GET       │            │         │        │            │              │             │
  │ /abc123   │            │         │        │            │              │             │
  ├──────────>│            │         │        │            │              │             │
  │           │getLongUrl()│         │        │            │              │             │
  │           ├───────────>│         │        │            │              │             │
  │           │            │ GET     │        │            │              │             │
  │           │            ├────────>│        │            │              │             │
  │           │            │ NULL    │        │            │              │             │
  │           │            │<────────┤        │            │              │             │
  │           │            │decode(shortCode) │            │              │             │
  │           │            ├─────────────────>│            │              │             │
  │           │            │   ID    │        │            │              │             │
  │           │            │<─────────────────┤            │              │             │
  │           │            │setDataSourceKey("shard1")     │              │             │
  │           │            ├──────────────────────────────>│              │             │
  │           │            │findByShortCode()              │              │             │
  │           │            ├──────────────────────────────────────────────>│             │
  │           │            │                               │              │ SELECT      │
  │           │            │                               │              ├────────────>│
  │           │            │                               │              │ urlMapping  │
  │           │            │                               │              │<────────────┤
  │           │            │  urlMapping                   │              │             │
  │           │            │<──────────────────────────────────────────────┤             │
  │           │            │clearDataSourceKey()           │              │             │
  │           │            ├──────────────────────────────>│              │             │
  │           │            │ SET (cache warming)           │              │             │
  │           │            ├────────>│        │            │              │             │
  │           │  longUrl   │         │        │            │              │             │
  │           │<───────────┤         │        │            │              │             │
  │  302      │            │         │        │            │              │             │
  │  Redirect │            │         │        │            │              │             │
  │<──────────┤            │         │        │            │              │             │
```

---

## 4. State Diagrams

### 4.1 URL Lifecycle State Machine

```
┌─────────────────────────────────────────────────────────────────┐
│                    URL Lifecycle States                          │
└─────────────────────────────────────────────────────────────────┘

         ┌──────────────┐
         │  Non-existent│
         └──────┬───────┘
                │
                │ POST /shorten
                │
                ▼
         ┌──────────────┐
         │   Creating   │ (Generate ID, save to DB)
         └──────┬───────┘
                │
                │ Success
                │
                ▼
         ┌──────────────┐
         │    Active    │◄─────────────┐
         │              │              │
         │ - In DB      │              │
         │ - Maybe      │              │ Cache refresh
         │   cached     │              │
         └──────┬───────┘              │
                │                      │
                │ GET /{shortCode}     │
                │                      │
                ▼                      │
         ┌──────────────┐              │
         │   Accessed   │──────────────┘
         │              │ (Update cache)
         │ - Cached     │
         │ - Hot data   │
         └──────┬───────┘
                │
                │ TTL expires
                │
                ▼
         ┌──────────────┐
         │Cache Evicted │
         │              │
         │ - Still in DB│
         │ - Cold data  │
         └──────────────┘
                │
                │ GET again
                │
                └──────────> (Back to Active)
```

---

# Design Patterns

## 1. Repository Pattern

**Intent**: Separate data access logic from business logic

**Implementation**:
```java
UrlMappingRepository (interface)
    ├─> Spring Data JPA (implementation)
    └─> Abstracts database operations
```

**Benefits**:
- Decoupling: Service doesn't know about JPA/SQL
- Testability: Easy to mock repository
- Flexibility: Can swap implementations

---

## 2. Dependency Injection (Inversion of Control)

**Intent**: Decouple object creation from usage

**Implementation**:
```java
@Service
public class UrlShortenerService {
    @Autowired
    private UrlMappingRepository repository;  // Injected by Spring
}
```

**Benefits**:
- Loose coupling
- Easy testing (inject mocks)
- Centralized configuration

---

## 3. Template Method Pattern

**Intent**: Define algorithm skeleton, let subclasses override steps

**Implementation**:
```java
AbstractRoutingDataSource (Spring)
    ├─> defineTemplate: getConnection()
    └─> overrideStep: determineCurrentLookupKey()

ShardRoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        // Custom routing logic
    }
}
```

**Benefits**:
- Reuse common logic (connection management)
- Customize specific behavior (shard selection)

---

## 4. Strategy Pattern

**Intent**: Define family of algorithms, make them interchangeable

**Implementation**:
```java
Sharding Strategy:
- TimestampModuloStrategy (current)
- ConsistentHashingStrategy (future)
- RangeBasedStrategy (future)
```

**Benefits**:
- Easy to swap sharding strategies
- Open/Closed principle

---

## 5. Cache-Aside Pattern

**Intent**: Lazy cache loading

**Implementation**:
```java
1. Try cache first
2. On miss, load from DB
3. Populate cache
4. Return data
```

**Benefits**:
- Only caches hot data
- Simple to implement
- Reduces database load

---

## 6. Singleton Pattern

**Intent**: Single instance of a class

**Implementation**:
```java
Spring Beans are singletons by default:
- UrlShortenerService (one instance)
- SnowflakeIdGenerator (one instance)
```

**Benefits**:
- Resource efficiency
- Shared state (for SnowflakeIdGenerator sequence)

---

# Scalability Considerations

## 1. Horizontal Scaling Strategies

### 1.1 Application Layer Scaling

**Current State**: Single instance

**Scaling Approach**:
```
┌─────────────┐
│ Load Balancer│
└──────┬──────┘
       │
   ┌───┴───┬────────┬────────┐
   │       │        │        │
┌──▼──┐ ┌──▼──┐  ┌──▼──┐  ┌──▼──┐
│App 1│ │App 2│  │App 3│  │App 4│
└─────┘ └─────┘  └─────┘  └─────┘
```

**Requirements**:
- Stateless application (✓ Already stateless)
- Unique node IDs for Snowflake (set via JVM property)
- Shared Redis and database (✓ Already external)

**Deployment**:
```bash
# Instance 1
java -DnodeId=1 -jar app.jar

# Instance 2
java -DnodeId=2 -jar app.jar

# Instance 3
java -DnodeId=3 -jar app.jar
```

---

### 1.2 Database Layer Scaling

**Current State**: 2 shards (shard0, shard1)

**Scaling to More Shards**:

```java
// Current: 2 shards
int shardKey = (int) (timestamp % 2);

// Scaling to 4 shards
int shardKey = (int) (timestamp % 4);

// Scaling to 8 shards
int shardKey = (int) (timestamp % 8);

// Scaling to N shards (configurable)
int totalShards = config.getTotalShards();
int shardKey = (int) (timestamp % totalShards);
```

**Configuration**:
```properties
# application.properties
app.sharding.total-shards=4

spring.datasource.shard0.url=...
spring.datasource.shard1.url=...
spring.datasource.shard2.url=...
spring.datasource.shard3.url=...
```

**Challenges**:
- Data rebalancing when adding shards
- Migration complexity
- Cross-shard queries

**Solution**: Use consistent hashing for better rebalancing

---

### 1.3 Cache Layer Scaling

**Current State**: Single Redis instance

**Scaling Options**:

#### Option A: Redis Sentinel (High Availability)
```
┌───────────┐
│  Master   │
└─────┬─────┘
      │ Replication
   ┌──┴──┬──────┐
   │     │      │
┌──▼──┐ ┌▼───┐ ┌▼───┐
│Slave│ │Slave│ │Slave│
└─────┘ └────┘ └────┘

Sentinels monitor and auto-failover
```

#### Option B: Redis Cluster (Horizontal Scaling)
```
┌────────┐  ┌────────┐  ┌────────┐
│ Node 1 │  │ Node 2 │  │ Node 3 │
│ Slots  │  │ Slots  │  │ Slots  │
│ 0-5460 │  │5461-   │  │10923-  │
│        │  │10922   │  │16383   │
└────────┘  └────────┘  └────────┘
```

**Benefits**:
- Higher throughput
- Better fault tolerance
- Automatic sharding

---

## 2. Performance Optimization

### 2.1 Database Optimization

#### Indexing Strategy
```sql
-- Primary key index (automatic)
PRIMARY KEY (id)

-- Short code lookup index (critical!)
CREATE UNIQUE INDEX idx_short_code ON url_mapping(short_code);

-- Time-range queries (analytics)
CREATE INDEX idx_created_at ON url_mapping(created_at);
```

#### Connection Pooling Tuning
```properties
# HikariCP configuration
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000
spring.datasource.hikari.idle-timeout=600000
spring.datasource.hikari.max-lifetime=1800000
```

---

### 2.2 Caching Optimization

#### TTL Strategy
```java
// Current: Fixed 1-day TTL
redisTemplate.opsForValue().set(key, value, 1, TimeUnit.DAYS);

// Optimization: Adaptive TTL based on access patterns
// Hot URLs: 7 days
// Warm URLs: 1 day
// Cold URLs: 1 hour

public Duration calculateTTL(int accessCount) {
    if (accessCount > 1000) return Duration.ofDays(7);
    if (accessCount > 100) return Duration.ofDays(1);
    return Duration.ofHours(1);
}
```

#### Cache Eviction Policy
```
LRU (Least Recently Used) - Default
- Evicts least accessed items first
- Good for URL shortener (80/20 rule applies)
```

---

### 2.3 Network Optimization

#### Compression
```java
// Enable Gzip compression
@Configuration
public class CompressionConfig {
    @Bean
    public GzipFilter gzipFilter() {
        return new GzipFilter();
    }
}
```

#### CDN for Redirects (Future Enhancement)
```
Client → CDN → Application → Database
         ↑
         └─ Cache popular redirects at edge
```

---

## 3. Capacity Planning

### 3.1 Storage Requirements

#### Database Storage
```
Assumptions:
- 1 billion URLs
- Average row size: 500 bytes

Total storage: 1B × 500 bytes = 500 GB

With indexes (2x data size): 1 TB
With replication (2x for HA): 2 TB

Per shard (2 shards): 1 TB each
```

#### Cache Storage
```
Assumptions:
- 20% hot data (80/20 rule)
- 200M URLs cached
- Average size: 500 bytes

Total cache: 200M × 500 bytes = 100 GB

Redis memory requirement: 100 GB
(Single instance or distributed)
```

---

### 3.2 Throughput Requirements

#### Write Throughput
```
Target: 1000 URLs/second

Database:
- 1000 writes/sec ÷ 2 shards = 500 writes/sec/shard
- PostgreSQL can handle 10K+ writes/sec (easily sufficient)

Snowflake ID:
- 4096 IDs/ms/node = 4M IDs/sec
- Far exceeds requirement
```

#### Read Throughput
```
Target: 10,000 redirects/second

With 80% cache hit rate:
- Redis: 8000 reads/sec (sub-millisecond)
- Database: 2000 reads/sec ÷ 2 shards = 1000/shard
- PostgreSQL can handle 100K+ reads/sec (easily sufficient)
```

---

### 3.3 Latency Targets

| Operation | Target | Actual (Estimate) |
|-----------|--------|-------------------|
| Shorten URL | <100ms | 10-50ms |
| Redirect (cache hit) | <10ms | 1-5ms |
| Redirect (cache miss) | <100ms | 20-80ms |
| Database query | <50ms | 10-30ms |
| Redis lookup | <5ms | 0.1-1ms |

---

# Trade-offs and Design Decisions

## 1. Snowflake ID vs Other Approaches

### Decision: Snowflake ID

**Alternatives Considered**:
1. Auto-increment IDs
2. UUID (v4)
3. Hash-based IDs
4. Snowflake IDs ✓

**Comparison**:

| Aspect | Auto-increment | UUID | Hash | Snowflake |
|--------|---------------|------|------|-----------|
| Uniqueness | Single DB only | 99.99% | Collision risk | Guaranteed |
| Ordering | Sequential | Random | Random | Time-ordered |
| Size | 4-8 bytes | 16 bytes | Varies | 8 bytes |
| Distributed | ❌ No | ✓ Yes | ✓ Yes | ✓ Yes |
| Performance | Fast | Fast | Fast | Fast |

**Trade-offs**:
- ✓ Time-ordered (good for range queries)
- ✓ Compact (8 bytes vs 16 for UUID)
- ✓ Guaranteed uniqueness
- ❌ Requires node coordination
- ❌ Exposes creation time (security concern?)

**Decision Justification**: Distributed system needs time-ordered, guaranteed-unique IDs. Snowflake is industry-proven (Twitter, Discord).

---

## 2. Database Sharding Strategy

### Decision: Timestamp-based Modulo Sharding

**Alternatives Considered**:
1. Range-based sharding (e.g., A-M in shard0, N-Z in shard1)
2. Hash-based sharding (e.g., hash(shortCode) % 2)
3. Timestamp modulo ✓

**Comparison**:

| Aspect | Range-based | Hash-based | Timestamp Modulo |
|--------|-------------|------------|------------------|
| Distribution | Uneven | Even | Even |
| Rebalancing | Hard | Easy | Easy |
| Time queries | Fast | Slow | Fast |
| Complexity | Low | Low | Low |

**Trade-offs**:
- ✓ Even distribution over time
- ✓ Simple to implement
- ✓ Embedded in ID (no extra computation)
- ❌ Rebalancing requires data migration
- ❌ Not optimal for non-time-based queries

**Decision Justification**: URL shortening is naturally time-ordered. New URLs distribute evenly.

---

## 3. Caching Strategy

### Decision: Cache-Aside (Lazy Loading)

**Alternatives Considered**:
1. Cache-aside (lazy loading) ✓
2. Write-through
3. Write-behind
4. Read-through

**Comparison**:

| Strategy | Data Freshness | Complexity | Cache Efficiency |
|----------|----------------|------------|------------------|
| Cache-aside | Good | Low | High (only hot data) |
| Write-through | Excellent | Medium | Low (all data) |
| Write-behind | Good | High | Medium |
| Read-through | Good | Medium | High |

**Trade-offs**:
- ✓ Simple to implement
- ✓ Only caches frequently accessed data
- ✓ Cache failures don't affect writes
- ❌ Cache miss penalty (extra DB query)
- ❌ Potential stale data (mitigated by TTL)

**Decision Justification**: Read-heavy workload benefits from lazy loading. 80/20 rule applies (most traffic hits popular URLs).

---

## 4. Base62 vs Base64

### Decision: Base62

**Alternatives Considered**:
1. Base62 (0-9, a-z, A-Z) ✓
2. Base64 (0-9, a-z, A-Z, +, /, =)

**Comparison**:

| Aspect | Base62 | Base64 |
|--------|--------|--------|
| URL-safe | ✓ Yes (no encoding needed) | ❌ No (+/= need encoding) |
| Compactness | Good | Slightly better |
| Readability | Better | Worse (special chars) |
| Simplicity | Simple | Simple |

**Trade-offs**:
- ✓ URL-safe without encoding
- ✓ More readable
- ❌ Slightly longer (negligible)

**Decision Justification**: URL-safety is critical. Extra 1 character is acceptable trade-off.

---

## 5. Two Shards vs More

### Decision: Start with 2 Shards

**Trade-offs**:
- ✓ Demonstrates sharding concept
- ✓ Simple to manage
- ✓ Sufficient for moderate scale
- ❌ Limited scalability
- ❌ Rebalancing needed for growth

**Future Enhancement**: Make shard count configurable, use consistent hashing for rebalancing.

---

## Summary

This URL shortener demonstrates:

**High-Level Design**:
- Scalable architecture with clear separation of concerns
- Read-optimized with caching layer
- Write-optimized with unique ID generation
- Horizontal scalability via database sharding

**Low-Level Design**:
- Well-defined class responsibilities
- Design patterns (Repository, DI, Template Method, Cache-Aside)
- Thread-safe distributed ID generation
- Dynamic database routing via Spring abstractions

**Production Readiness**:
- ACID compliance for data consistency
- Connection pooling for performance
- Database migrations for schema versioning
- Comprehensive error handling (future enhancement)
- Monitoring and observability (future enhancement)

This system can scale to millions of URLs with appropriate infrastructure (more shards, Redis cluster, load balancers, CDN).
