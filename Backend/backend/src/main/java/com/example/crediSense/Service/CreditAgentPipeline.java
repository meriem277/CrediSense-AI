package com.example.crediSense.Service;

import com.example.crediSense.agent.Agent1Result;
import com.example.crediSense.agent.Agent2Result;
import com.example.crediSense.agent.Agent3Result;
import com.example.crediSense.agent.DocumentParserAgent;
import com.example.crediSense.agent.FinancialExtractorAgent;
import com.example.crediSense.agent.ReportSynthesizerAgent;
import com.example.crediSense.agent.RiskEvaluatorAgent;
import com.example.crediSense.dto.response.CreditAnalysisResult;
import com.example.crediSense.Service.impl.DocumentParserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class CreditAgentPipeline {

    private final DocumentParserService documentParserService;
    private final DocumentParserAgent documentParserAgent;
    private final FinancialExtractorAgent financialExtractorAgent;
    private final RiskEvaluatorAgent riskEvaluatorAgent;
    private final ReportSynthesizerAgent reportSynthesizerAgent;

    public CreditAnalysisResult run(MultipartFile file, String creditType, String cin) throws Exception {
        String rawText = documentParserService.extractText(file);
        int rawLen = (rawText == null) ? 0 : rawText.length();
        System.out.println("=== PIPELINE: rawText LENGTH: " + rawLen);

        Agent1Result doc = documentParserAgent.run(rawText);
        System.out.println("=== PIPELINE: agent1 documentType: " + (doc == null ? "null" : doc.getDocumentType()));

        // Force the full rawText into doc.cleanText so downstream agents always receive the full document
        if (doc == null) doc = new Agent1Result();
        doc.setCleanText(rawText == null ? "" : rawText);
        System.out.println("=== PIPELINE: cleanText forced to rawText, length: " + rawLen);

        Agent2Result financials = financialExtractorAgent.run(doc, creditType);
        System.out.println("=== PIPELINE: agent2 monthlyIncome: " + (financials == null ? "null" : financials.getMonthlyIncome()));
        System.out.println("=== PIPELINE: agent2 dti: " + (financials == null ? "null" : financials.getDti()));

        Agent3Result risk = riskEvaluatorAgent.run(financials, creditType, cin);
        System.out.println("=== PIPELINE: agent3 eligibility: " + (risk == null ? "null" : risk.getEligibility()));

        return reportSynthesizerAgent.run(doc, financials, risk, creditType);
    }
}
