package com.example.urlshortener.service;

import com.example.urlshortener.config.DataSourceContextHolder;
import com.example.urlshortener.entity.UrlMapping;
import com.example.urlshortener.repository.UrlMappingRepository;
import com.example.urlshortener.util.Base62;
import com.example.urlshortener.util.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class UrlShortenerService {

    @Autowired
    private UrlMappingRepository urlMappingRepository;

    @Autowired
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Autowired
    private Base62 base62;

    @Autowired
    private StringRedisTemplate redisTemplate;

    public String shortenUrl(String longUrl) {
        if (longUrl == null || longUrl.isEmpty()) {
            throw new IllegalArgumentException("URL cannot be null or empty");
        }
        long id = snowflakeIdGenerator.nextId();
        String shortCode = base62.encode(id);

        long timestamp = id >> 22;
        int shardKey = (int) (timestamp % 2);
        DataSourceContextHolder.setDataSourceKey("shard" + shardKey);

        UrlMapping urlMapping = new UrlMapping(id, shortCode, longUrl);
        urlMappingRepository.save(urlMapping);

        DataSourceContextHolder.clearDataSourceKey();

        redisTemplate.opsForValue().set(shortCode, longUrl, 1, TimeUnit.DAYS);

        return shortCode;
    }

    public String getLongUrl(String shortCode) {
        String longUrl = redisTemplate.opsForValue().get(shortCode);
        if (longUrl != null) {
            return longUrl;
        }

        long id;
        try {
            id = base62.decode(shortCode);
        } catch (IllegalArgumentException e) {
            return null;
        }
        long timestamp = id >> 22;
        int shardKey = (int) (timestamp % 2);
        DataSourceContextHolder.setDataSourceKey("shard" + shardKey);

        UrlMapping urlMapping = urlMappingRepository.findByShortCode(shortCode);

        DataSourceContextHolder.clearDataSourceKey();

        if (urlMapping != null) {
            redisTemplate.opsForValue().set(shortCode, urlMapping.getLongUrl(), 1, TimeUnit.DAYS);
            return urlMapping.getLongUrl();
        }

        return null;
    }
}
