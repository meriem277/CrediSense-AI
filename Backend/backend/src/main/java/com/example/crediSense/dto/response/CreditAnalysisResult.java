package com.example.crediSense.dto.response;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class CreditAnalysisResult {
    private String creditType;
    private String eligibility;
    private Integer eligibilityScore;
    private List<String> risks;
    private List<String> recommendedPlan;
    private Map<String, Object> financialMetrics;
    private String rawExplanation;
    private Double scoreFinal;
    private String decisionFinale;
    private String justificationGlobale;
    private String dossierId;
}