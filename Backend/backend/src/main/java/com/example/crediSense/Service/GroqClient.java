package com.example.crediSense.Service;

import com.example.crediSense.agent.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class GroqClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String model;

    @Value("${groq.api.url:https://api.groq.com/openai/v1/chat/completions}")
    private String apiUrl;

    public String call(String prompt, double temperature) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", prompt));

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", temperature);
            body.put("max_tokens", 2000);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode choice = root.path("choices").get(0);
            String content = choice.path("message").path("content").asText();
            return content;
        } catch (Exception e) {
            log.error("Groq call error: {}", e.getMessage());
            return "{" + "\"error\":\"groq_call_failed\"}";
        }
    }

    public String callWithTools(String systemPrompt, String userPrompt, List<Map<String, Object>> tools, ToolExecutor executor, double temperature) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userPrompt));

        int maxIterations = 6;
        int iter = 0;

        while (true) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(apiKey);

                Map<String, Object> body = new HashMap<>();
                body.put("model", model);
                body.put("messages", messages);
                body.put("temperature", temperature);
                body.put("max_tokens", 2000);
                body.put("functions", tools);

                HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
                ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);

                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode choice = root.path("choices").get(0);

                JsonNode message = choice.path("message");

                // Check function/tool call (Groq uses tool_call or function_call)
                JsonNode toolCall = message.path("tool_call");
                if (toolCall.isMissingNode() || toolCall.isNull()) {
                    toolCall = message.path("function_call");
                }

                if (!toolCall.isMissingNode() && !toolCall.isNull()) {
                    String toolName = toolCall.path("name").asText();
                    String arguments = toolCall.path("arguments").asText();
                    Map<String, Object> argsMap = new HashMap<>();
                    try {
                        if (arguments != null && !arguments.isBlank()) {
                            argsMap = objectMapper.readValue(arguments, Map.class);
                        }
                    } catch (Exception ex) {
                        log.warn("Failed to parse tool arguments JSON, passing raw string");
                        argsMap.put("_raw", arguments);
                    }

                    // execute tool
                    String toolResult = executor.execute(toolName, argsMap);

                    // append tool result to messages
                    Map<String, Object> toolMessage = new HashMap<>();
                    toolMessage.put("role", "tool");
                    toolMessage.put("name", toolName);
                    toolMessage.put("content", toolResult);
                    messages.add(toolMessage);

                    iter++;
                    if (iter >= maxIterations) {
                        return "{\"error\":\"max_tool_iterations_reached\"}";
                    }

                    // continue loop to let model react to tool output
                    continue;
                } else {
                    String content = message.path("content").asText();
                    if (content == null || content.isBlank()) {
                        // sometimes the assistant message might be in choices[].message.content
                        content = choice.path("message").path("content").asText();
                    }
                    return content;
                }

            } catch (Exception e) {
                log.error("Error in callWithTools: {}", e.getMessage());
                return "{\"error\":\"groq_call_with_tools_failed\"}";
            }
        }
    }

    public <T> T parse(String raw, Class<T> type) throws Exception {
        String cleaned = stripFencedJson(raw);
        if (cleaned == null) cleaned = "";
        cleaned = cleaned.trim();
        try {
            return objectMapper.readValue(cleaned, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            System.out.println("Failed to parse JSON from Groq response. Raw response follows:");
            System.out.println(raw);
            throw e;
        }
    }

    public ObjectMapper getObjectMapper() { return objectMapper; }

    private String stripFencedJson(String texte) {
        if (texte == null) return "{}";
        String propre = texte.strip();
        if (propre.startsWith("```json")) propre = propre.substring(7).strip();
        else if (propre.startsWith("```") ) propre = propre.substring(3).strip();
        if (propre.endsWith("```")) propre = propre.substring(0, propre.length() - 3).strip();

        // try to locate first { and last }
        int first = propre.indexOf('{');
        int last = propre.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return propre.substring(first, last + 1);
        }
        return propre;
    }
}
