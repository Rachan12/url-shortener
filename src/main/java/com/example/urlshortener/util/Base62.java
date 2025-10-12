package com.example.urlshortener.util;

import org.springframework.stereotype.Component;

@Component
public class Base62 {

    private static final String BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public String encode(long n) {
        if (n == 0) {
            return "0";
        }
        StringBuilder sb = new StringBuilder();
        while (n > 0) {
            sb.append(BASE62_CHARS.charAt((int) (n % 62)));
            n /= 62;
        }
        return sb.reverse().toString();
    }

    public long decode(String s) {
        long n = 0;
        for (char c : s.toCharArray()) {
            n = n * 62 + BASE62_CHARS.indexOf(c);
        }
        return n;
    }
}
