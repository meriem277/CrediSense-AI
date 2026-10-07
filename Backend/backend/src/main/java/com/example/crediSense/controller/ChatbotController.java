package com.example.crediSense.controller;

import com.example.crediSense.Service.impl.ChatbotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
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
                request.question(),
                request.historique()
        );

        return ResponseEntity.ok(new ChatbotResponse(reponse));
    }

    /**
     * `historique` : les derniers messages de la conversation, du plus ancien au plus récent,
     * sans la question en cours : [{"role": "user" | "assistant", "content": "..."}].
     * Facultatif : sans lui, chaque question est traitée seule (comportement d'avant).
     */
    public record ChatbotRequest(
            String cin,
            String dossierId,
            String question,
            List<Map<String, String>> historique
    ) {}

    public record ChatbotResponse(
            String reponse
    ) {}
}