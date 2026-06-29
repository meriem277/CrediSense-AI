package com.example.crediSense.agent;

import lombok.Data;
import java.util.List;

@Data
public class Agent3Result {
    private String eligibility;
    private Integer eligibilityScore;
    private List<String> risks;
    private String decisionRationale;
}
