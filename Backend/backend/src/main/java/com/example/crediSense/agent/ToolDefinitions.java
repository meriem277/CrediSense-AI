package com.example.crediSense.agent;

import java.util.*;

public class ToolDefinitions {

    public static List<Map<String, Object>> creditTools() {
        List<Map<String, Object>> tools = new ArrayList<>();

        // get_client_history(cin: string)
        Map<String, Object> t1 = new HashMap<>();
        t1.put("name", "get_client_history");
        t1.put("description", "Retrieve client credit history from internal records and BCT Centrale des Risques");
        Map<String, Object> p1 = new HashMap<>();
        p1.put("type", "object");
        Map<String, Object> props1 = new HashMap<>();
        props1.put("cin", Map.of("type", "string", "description", "Client CIN"));
        p1.put("properties", props1);
        p1.put("required", List.of("cin"));
        t1.put("parameters", p1);

        // get_current_rates()
        Map<String, Object> t2 = new HashMap<>();
        t2.put("name", "get_current_rates");
        t2.put("description", "Get current BCT TMM rate and maximum applicable credit rates for 2024");
        Map<String, Object> p2 = new HashMap<>();
        p2.put("type", "object");
        p2.put("properties", Map.of());
        t2.put("parameters", p2);

        // calculate_monthly_payment(amount: number, durationMonths: integer, annualRate: number)
        Map<String, Object> t3 = new HashMap<>();
        t3.put("name", "calculate_monthly_payment");
        t3.put("description", "Calculate monthly payment using standard French amortization formula");
        Map<String, Object> p3 = new HashMap<>();
        p3.put("type", "object");
        Map<String, Object> props3 = new HashMap<>();
        props3.put("amount", Map.of("type", "number"));
        props3.put("durationMonths", Map.of("type", "integer"));
        props3.put("annualRate", Map.of("type", "number"));
        p3.put("properties", props3);
        p3.put("required", List.of("amount", "durationMonths", "annualRate"));
        t3.put("parameters", p3);

        // check_dti_compliance(monthlyIncome: number, totalDebts: number, creditType: string)
        Map<String, Object> t4 = new HashMap<>();
        t4.put("name", "check_dti_compliance");
        t4.put("description", "Check if DTI ratio complies with BCT circulaire 91-24 thresholds");
        Map<String, Object> p4 = new HashMap<>();
        p4.put("type", "object");
        Map<String, Object> props4 = new HashMap<>();
        props4.put("monthlyIncome", Map.of("type", "number"));
        props4.put("totalDebts", Map.of("type", "number"));
        props4.put("creditType", Map.of("type", "string"));
        p4.put("properties", props4);
        p4.put("required", List.of("monthlyIncome", "totalDebts", "creditType"));
        t4.put("parameters", p4);

        tools.add(t1);
        tools.add(t2);
        tools.add(t3);
        tools.add(t4);

        return tools;
    }
}
