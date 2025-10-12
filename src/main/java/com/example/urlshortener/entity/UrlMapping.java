package com.example.urlshortener.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UrlMapping {

    @Id
    private long id;

    @Column(nullable = false)
    private String shortCode;

    @Column(nullable = false, length = 2048)
    private String longUrl;
}
