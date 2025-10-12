# URL Shortener

A simple URL shortener service built with Spring Boot.

## Technologies Used

*   Java 17
*   Spring Boot
*   PostgreSQL
*   Redis
*   Maven

## Features

*   Shortens a long URL to a short code.
*   Redirects a short code to the original long URL.
*   Uses a sharded PostgreSQL database for storing URL mappings.
*   Uses Redis for caching shortened URLs.
*   Uses Snowflake ID generator for generating unique IDs.

## How to Build and Run

1.  **Prerequisites:**
    *   Java 17
    *   Maven
    *   PostgreSQL
    *   Redis

2.  **Database Setup:**
    *   Create two PostgreSQL databases named `shard0` and `shard1`.
    *   Update the database credentials in `src/main/resources/application.properties`.

3.  **Redis Setup:**
    *   Make sure Redis is running on `localhost:6379`.

4.  **Build the project:**
    ```bash
    mvn clean install
    ```

5.  **Run the application:**
    ```bash
    mvn spring-boot:run
    ```
    The application will be available at `http://localhost:8080`.

## API Endpoints

### Shorten a URL

*   **Method:** `POST`
*   **Endpoint:** `/shorten`
*   **Request Body:** The long URL as a plain text string.
*   **Example:**
    ```bash
    curl -X POST http://localhost:8080/shorten -H "Content-Type: text/plain" -d "https://www.google.com"
    ```
*   **Response:** The shortened URL.
    ```
    http://localhost:8080/your_short_code
    ```

### Redirect to Long URL

*   **Method:** `GET`
*   **Endpoint:** `/{shortCode}`
*   **Example:**
    ```bash
    curl -L http://localhost:8080/your_short_code
    ```
    This will redirect to the original long URL.