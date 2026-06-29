package com.example.crediSense.controller;

import com.example.crediSense.Service.CreditAgentPipeline;
import com.example.crediSense.dto.response.CreditAnalysisResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/credit")
@CrossOrigin(origins = "http://localhost:4200")
public class CreditController {

    private final CreditAgentPipeline pipeline;

    public CreditController(CreditAgentPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @PostMapping("/analyse/{type}")
    public ResponseEntity<CreditAnalysisResult> analyse(
            @PathVariable String type,
            @RequestParam("file") MultipartFile file,
            @RequestParam("cin") String cin) throws Exception {

        CreditAnalysisResult result = pipeline.run(file, type.toUpperCase(), cin);

        return ResponseEntity.ok(result);
    }
}
