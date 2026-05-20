package com.example.crediSense.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}


// ═══════════════════════════════════════════════════════════════════
// Ajouts dans application.properties
// ═══════════════════════════════════════════════════════════════════

/*
# ─── Service Doctr Python ─────────────────────────────────────────
doctr.service.url=http://localhost:8000
*/


// ═══════════════════════════════════════════════════════════════════
// Dépendance à ajouter dans pom.xml (PDFBox pour PDF natifs)
// ═══════════════════════════════════════════════════════════════════

/*
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>3.0.2</version>
</dependency>
*/

