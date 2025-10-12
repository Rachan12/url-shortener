package com.example.urlshortener;

import com.example.urlshortener.entity.UrlMapping;
import com.example.urlshortener.repository.UrlMappingRepository;
import com.example.urlshortener.service.UrlShortenerService;
import com.example.urlshortener.util.Base62;
import com.example.urlshortener.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class UrlShortenerServiceTest {

    @InjectMocks
    private UrlShortenerService urlShortenerService;

    @Mock
    private UrlMappingRepository urlMappingRepository;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Mock
    private Base62 base62;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    public void testShortenUrl() {
        when(snowflakeIdGenerator.nextId()).thenReturn(12345L);
        when(base62.encode(12345L)).thenReturn("short");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String longUrl = "https://example.com";
        String shortCode = urlShortenerService.shortenUrl(longUrl);

        assertEquals("short", shortCode);
        verify(urlMappingRepository, times(1)).save(any(UrlMapping.class));
        verify(redisTemplate, times(1)).opsForValue();
    }

    @Test
    public void testGetLongUrl_fromRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("short")).thenReturn("https://example.com");

        String longUrl = urlShortenerService.getLongUrl("short");

        assertEquals("https://example.com", longUrl);
        verify(urlMappingRepository, never()).findByShortCode(anyString());
    }

    @Test
    public void testGetLongUrl_fromDb() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("short")).thenReturn(null);
        when(base62.decode("short")).thenReturn(12345L);

        UrlMapping urlMapping = new UrlMapping(12345L, "short", "https://example.com");
        when(urlMappingRepository.findByShortCode("short")).thenReturn(urlMapping);

        String longUrl = urlShortenerService.getLongUrl("short");

        assertEquals("https://example.com", longUrl);
        verify(redisTemplate, times(2)).opsForValue(); // one for get, one for set
    }

    @Test
    public void testShortenUrl_withEmptyUrl_shouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> {
            urlShortenerService.shortenUrl("");
        });
    }

    @Test
    public void testShortenUrl_withNullUrl_shouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> {
            urlShortenerService.shortenUrl(null);
        });
    }

    @Test
    public void testShortenUrl_withVeryLongUrl_shouldSaveAndReturnShortCode() {
        when(snowflakeIdGenerator.nextId()).thenReturn(2L);
        when(base62.encode(2L)).thenReturn("b");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String longUrl = "https://".repeat(1000);
        String shortCode = urlShortenerService.shortenUrl(longUrl);

        assertEquals("b", shortCode);
        verify(urlMappingRepository, times(1)).save(any(UrlMapping.class));
    }

    @Test
    public void testGetLongUrl_withInvalidShortCode_shouldReturnNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("invalid")).thenReturn(null);
        when(base62.decode("invalid")).thenThrow(new IllegalArgumentException());

        String longUrl = urlShortenerService.getLongUrl("invalid");

        assertNull(longUrl);
    }

    @Test
    public void testBase62EncodingDecoding() {
        long id = 1234567890L;
        Base62 realBase62 = new Base62();
        String encoded = realBase62.encode(id);
        long decoded = realBase62.decode(encoded);

        assertEquals(id, decoded);
    }

    @Test
    public void testSharding_withEvenTimestampId_shouldWork() {
        long id = 1L << 22; // even timestamp
        when(snowflakeIdGenerator.nextId()).thenReturn(id);
        when(base62.encode(id)).thenReturn("even");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        urlShortenerService.shortenUrl("https://example.com/even");

        verify(urlMappingRepository, times(1)).save(any(UrlMapping.class));
    }

    @Test
    public void testSharding_withOddTimestampId_shouldWork() {
        long id = (1L << 23); // odd timestamp
        when(snowflakeIdGenerator.nextId()).thenReturn(id);
        when(base62.encode(id)).thenReturn("odd");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        urlShortenerService.shortenUrl("https://example.com/odd");

        verify(urlMappingRepository, times(1)).save(any(UrlMapping.class));
    }

    @Test
    public void testGetLongUrl_whenNotInRedisButInDb_shouldCacheIt() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("short")).thenReturn(null);
        when(base62.decode("short")).thenReturn(12345L);

        UrlMapping urlMapping = new UrlMapping(12345L, "short", "https://example.com");
        when(urlMappingRepository.findByShortCode("short")).thenReturn(urlMapping);

        urlShortenerService.getLongUrl("short");

        verify(valueOperations, times(1)).set(eq("short"), eq("https://example.com"), anyLong(), any(TimeUnit.class));
    }

    @Test
    public void testShortenUrl_differentUrls_shouldProduceDifferentShortCodes() {
        when(snowflakeIdGenerator.nextId()).thenReturn(1L, 2L);
        when(base62.encode(1L)).thenReturn("a");
        when(base62.encode(2L)).thenReturn("b");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String shortCode1 = urlShortenerService.shortenUrl("https://example1.com");
        String shortCode2 = urlShortenerService.shortenUrl("https://example2.com");

        assertNotEquals(shortCode1, shortCode2);
    }

    @Test
    public void testGetLongUrl_withNonExistentShortCode_shouldReturnNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("nonexistent")).thenReturn(null);
        when(base62.decode("nonexistent")).thenReturn(123L);
        when(urlMappingRepository.findByShortCode("nonexistent")).thenReturn(null);

        String longUrl = urlShortenerService.getLongUrl("nonexistent");

        assertNull(longUrl);
    }
}