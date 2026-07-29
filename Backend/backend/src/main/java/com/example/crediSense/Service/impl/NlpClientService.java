package com.example.crediSense.Service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NlpClientService {

    private final RestTemplate restTemplate;

    @Value("${nlp.service.url:http://localhost:8002}")
    private String nlpUrl;

    public String classifierDocument(String texte) {
        try {
            Map<String, Object> body = Map.of("texte", texte);
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    nlpUrl + "/ai/classify", body, Map.class
            );
            return (String) response.getBody().get("type_document");
        } catch (Exception e) {
            log.error("Erreur NLP classification : {}", e.getMessage());
            return "AUTRE";
        }
    }
}