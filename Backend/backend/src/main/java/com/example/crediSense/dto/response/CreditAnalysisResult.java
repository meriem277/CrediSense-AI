package com.example.crediSense.dto.response;

import lombok.Data;

import java.util.List;
import java.util.Map;

// CreditAnalysisResult.java
@Data
public class CreditAnalysisResult {
    private String creditType;          // "IMMOBILIER" | "CONSOMMATION"
    private String eligibility;         // "ELIGIBLE" | "REFUS" | "CONDITIONNEL"
    private int eligibilityScore;       // 0–100
    private List<String> risks;         // identified risk factors
    private List<String> recommendedPlan; // action steps
    private Map<String, Object> financialMetrics; // DTI, LTV, etc.
    private String rawExplanation;      // full LLM narrative
}
