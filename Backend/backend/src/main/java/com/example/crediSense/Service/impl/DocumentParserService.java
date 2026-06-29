package com.example.crediSense.Service.impl;

import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

@Service
public class DocumentParserService {

    public String extractText(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename().toLowerCase();

        if (filename.endsWith(".pdf")) {
            return extractFromPdf(file);
        } else if (filename.endsWith(".docx")) {
            return extractFromDocx(file);
        } else if (filename.matches(".*\\.(jpg|jpeg|png)")) {
            return extractFromImageOcr(file);
        }
        throw new IllegalArgumentException("Unsupported file type");
    }

    private String extractFromPdf(MultipartFile file) throws Exception {
        try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private String extractFromDocx(MultipartFile file) throws Exception {
        XWPFDocument doc = new XWPFDocument(file.getInputStream());
        XWPFWordExtractor extractor = new XWPFWordExtractor(doc);
        return extractor.getText();
    }

    private String extractFromImageOcr(MultipartFile file) throws Exception {
        // Tesseract via Tess4J or call your existing OcrService
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath("/usr/share/tessdata");
        tesseract.setLanguage("fra");
        BufferedImage img = ImageIO.read(file.getInputStream());
        return tesseract.doOCR(img);
    }
}