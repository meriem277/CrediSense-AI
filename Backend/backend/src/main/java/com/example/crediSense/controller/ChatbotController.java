package com.example.crediSense.controller;

import com.example.crediSense.Service.impl.ChatbotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/chatbot")
@CrossOrigin(origins = "http://localhost:4200")
@RequiredArgsConstructor
public class ChatbotController {

    private final ChatbotService chatbotService;

    @PostMapping("/question")
    public ResponseEntity<ChatbotResponse> poserQuestion(
            @RequestBody ChatbotRequest request) {

        // ← Gérer dossierId null ou vide
        UUID dossierId = null;
        if (request.dossierId() != null && !request.dossierId().isBlank()) {
            try {
                dossierId = UUID.fromString(request.dossierId());
            } catch (IllegalArgumentException e) {
                dossierId = null;
            }
        }

        String reponse = chatbotService.poserQuestion(
                request.cin(),
                dossierId,
                request.question()
        );

        return ResponseEntity.ok(new ChatbotResponse(reponse));
    }

    public record ChatbotRequest(
            String cin,
            String dossierId,
            String question
    ) {}

    public record ChatbotResponse(
            String reponse
    ) {}
}