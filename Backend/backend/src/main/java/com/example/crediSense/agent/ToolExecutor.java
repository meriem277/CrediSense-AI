package com.example.crediSense.agent;

import com.example.crediSense.entity.AgentAnalysis;
import com.example.crediSense.repository.AgentAnalysisRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ToolExecutor {

    private final AgentAnalysisRepository agentAnalysisRepository;
    private final ObjectMapper objectMapper;

    public String execute(String toolName, Map<String, Object> args) {
        try {
            switch (toolName) {
                case "get_client_history":
                    return objectMapper.writeValueAsString(handleGetClientHistory((String) args.get("cin")));

                case "get_current_rates":
                    return objectMapper.writeValueAsString(Map.of(
                            "tmmRate", 7.97,
                            "maxConsommationRate", 10.97,
                            "maxImmobilierRate", 10.47,
                            "bctKeyRate", 8.0
                    ));

                case "calculate_monthly_payment":
                    double amount = toDouble(args.get("amount"));
                    int durationMonths = ((Number) args.get("durationMonths")).intValue();
                    double annualRate = toDouble(args.get("annualRate"));
                    return objectMapper.writeValueAsString(calculateMonthly(amount, durationMonths, annualRate));

                case "check_dti_compliance":
                    double monthlyIncome = toDouble(args.get("monthlyIncome"));
                    double totalDebts = toDouble(args.get("totalDebts"));
                    String creditType = (String) args.get("creditType");
                    return objectMapper.writeValueAsString(checkDti(monthlyIncome, totalDebts, creditType));

                default:
                    return objectMapper.writeValueAsString(Map.of("error", "Unknown tool: " + toolName));
            }
        } catch (Exception e) {
            try {
                return objectMapper.writeValueAsString(Map.of("error", e.getMessage()));
            } catch (Exception ex) {
                return "{\"error\":\"serialization_failure\"}";
            }
        }
    }

    private Map<String, Object> handleGetClientHistory(String cin) {
        List<AgentAnalysis> analyses = agentAnalysisRepository.findAll();
        List<AgentAnalysis> filtered = new ArrayList<>();
        for (AgentAnalysis a : analyses) {
            if (a.getDossier() != null && a.getDossier().getClient() != null && cin != null && cin.equals(a.getDossier().getClient().getCin())) {
                filtered.add(a);
            }
        }

        boolean hasIncidents = filtered.stream().anyMatch(a -> a.getDecision() != null && a.getDecision().equalsIgnoreCase("REFUS"));
        int activeCredits = filtered.size();
        List<String> lastDecisions = new ArrayList<>();
        filtered.stream().sorted(Comparator.comparing(AgentAnalysis::getCreatedAt).reversed()).limit(5)
                .forEach(a -> lastDecisions.add(a.getDecision()));

        return Map.of(
                "cin", cin,
                "hasIncidents", hasIncidents,
                "activeCredits", activeCredits,
                "lastDecisions", lastDecisions,
                "source", List.of("internal_agent_analyses", "bct_centrale_des_risques_unavailable")
        );
    }

    private Map<String, Object> calculateMonthly(double amount, int months, double annualRate) {
        double r = annualRate / 100.0 / 12.0;
        double n = months;
        double monthly = 0.0;
        if (r == 0) monthly = amount / n;
        else monthly = amount * r * Math.pow(1 + r, n) / (Math.pow(1 + r, n) - 1);
        double totalRepayment = monthly * n;
        double totalInterest = totalRepayment - amount;
        return Map.of(
                "monthlyPayment", monthly,
                "totalRepayment", totalRepayment,
                "totalInterest", totalInterest
        );
    }

    private Map<String, Object> checkDti(double monthlyIncome, double totalDebts, String creditType) {
        double dti = monthlyIncome == 0 ? 0.0 : (totalDebts / monthlyIncome) * 100.0;
        double threshold1, threshold2;
        if ("IMMOBILIER".equalsIgnoreCase(creditType)) {
            threshold1 = 33.0; threshold2 = 40.0;
        } else {
            threshold1 = 30.0; threshold2 = 35.0;
        }

        String status;
        if (dti <= threshold1) status = "COMPLIANT";
        else if (dti <= threshold2) status = "CONDITIONAL";
        else status = "NON_COMPLIANT";

        double margin = Math.round((threshold2 - dti) * 100.0) / 100.0;

        return Map.of(
                "dti", Math.round(dti * 100.0) / 100.0,
                "status", status,
                "margin", margin,
                "source", "BCT circulaire 91-24"
        );
    }

    private double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0.0; }
    }
}
