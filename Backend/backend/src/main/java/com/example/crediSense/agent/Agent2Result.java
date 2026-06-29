package com.example.crediSense.agent;

import lombok.Data;
import java.util.List;

@Data
public class Agent2Result {
    private Double monthlyIncome;
    private Double existingDebts;
    private Double requestedAmount;
    private Double propertyValue;
    private Integer durationMonths;
    private Double dti;
    private Double ltv;
    private Double personalContribution;
    private String employmentType;
    private Integer employmentYears;
    private Boolean hasCreditIncidents;
    private String creditPurpose;
    private List<String> missingFields;
}
