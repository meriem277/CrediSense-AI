package com.example.crediSense.Service.impl;


import com.itextpdf.text.Document;
import com.itextpdf.text.Image;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import fr.opensagres.poi.xwpf.converter.pdf.PdfConverter;
import fr.opensagres.poi.xwpf.converter.pdf.PdfOptions;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.io.FileInputStream;
import java.io.InputStream;
import org.springframework.stereotype.Service;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Set;

@Slf4j
@Service
public class PdfConversionService {

    private static final Set<String> IMAGE_TYPES = Set.of("jpg", "jpeg", "png");

    public void convert(Path sourcePath, Path destPath, String extension) throws Exception {
        String ext = extension.toLowerCase().trim();
        destPath.getParent().toFile().mkdirs();

        if (IMAGE_TYPES.contains(ext)) {
            convertImage(sourcePath, destPath);
        } else if ("docx".equals(ext)) {
            convertDocx(sourcePath, destPath);
        } else if ("pdf".equals(ext)) {
            // Copier le PDF sans conversion
            log.info("Fichier déjà en PDF, copie directe : {}", sourcePath.getFileName());
            java.nio.file.Files.copy(sourcePath, destPath);

        } else {
            throw new IllegalArgumentException("Type non supporté : " + ext);
        }
    }

    // ─── Image → PDF (iText) ─────────────────────────────────────────────────
    // IMPORTANT : document.close() doit être appelé AVANT fos.close()
    // Le try-with-resources ferme le stream trop tôt → Stream Closed

    private void convertImage(Path imagePath, Path pdfPath) throws Exception {
        log.info("Image → PDF : {}", imagePath.getFileName());

        FileOutputStream fos = new FileOutputStream(pdfPath.toFile());
        Document document = new Document(PageSize.A4);
        try {
            PdfWriter.getInstance(document, fos);
            document.open();

            Image image = Image.getInstance(imagePath.toAbsolutePath().toString());
            float maxW = PageSize.A4.getWidth()  - document.leftMargin() - document.rightMargin();
            float maxH = PageSize.A4.getHeight() - document.topMargin()  - document.bottomMargin();
            image.scaleToFit(maxW, maxH);
            image.setAlignment(Image.ALIGN_CENTER);

            document.add(image);

            document.close(); // 1. iText flush vers le stream
            fos.close();      // 2. stream fermé après

            log.info("Image convertie → {}", pdfPath.getFileName());

        } catch (Exception e) {
            if (document.isOpen()) document.close();
            try { fos.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    // ─── DOCX → PDF (docx4j) ─────────────────────────────────────────────────

    // ✅ Nouvelle méthode convertDocx()
    private void convertDocx(Path docxPath, Path pdfPath) throws Exception {
        log.info("DOCX → PDF : {}", docxPath.getFileName());

        try (InputStream in  = new FileInputStream(docxPath.toFile());
             OutputStream out = new FileOutputStream(pdfPath.toFile())) {

            XWPFDocument document = new XWPFDocument(in);
            PdfOptions options = PdfOptions.create();
            PdfConverter.getInstance().convert(document, out, options);

            log.info("DOCX converti → {}", pdfPath.getFileName());
        }
    }
}