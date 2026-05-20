package com.example.crediSense.Service.impl;


import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class Textcleaningservice {

    /**
     * Applique toutes les transformations de nettoyage sur le texte brut.
     *
     * @param texteBrut texte brut retourné par Doctr ou PDFBox
     * @return texte nettoyé prêt pour GROQ
     */
    public String nettoyer(String texteBrut) {
        if (texteBrut == null || texteBrut.isBlank()) {
            return "";
        }

        log.info("Nettoyage du texte ({} caractères bruts)", texteBrut.length());

        String texte = texteBrut;

        // 1. Supprimer les caractères de contrôle sauf \n et \t
        texte = texte.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");

        // 2. Normaliser les retours à la ligne (Windows \r\n → \n)
        texte = texte.replaceAll("\\r\\n|\\r", "\n");

        // 3. Supprimer les lignes vides multiples (max 2 sauts consécutifs)
        texte = texte.replaceAll("\\n{3,}", "\n\n");

        // 4. Supprimer les espaces multiples sur une même ligne
        texte = texte.replaceAll("[ \\t]+", " ");

        // 5. Supprimer les espaces en début/fin de chaque ligne
        String[] lignes = texte.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String ligne : lignes) {
            sb.append(ligne.strip()).append("\n");
        }
        texte = sb.toString();

        // 6. Corriger les artefacts OCR courants pour les documents bancaires
        texte = corrigerArtefactsOcr(texte);

        // 7. Trim global
        texte = texte.strip();

        log.info("Texte nettoyé ({} caractères)", texte.length());
        return texte;
    }

    // ─── Corrections spécifiques aux documents bancaires ─────────────────────

    private String corrigerArtefactsOcr(String texte) {
        return texte
                // Chiffres souvent mal reconnus
                .replace("l'", "l'")
                .replace("I0", "10")    // I majuscule confondu avec 1
                .replace("O0", "00")    // O confondu avec 0
                .replace("|", "l")      // pipe confondu avec l

                // Caractères arabes/spéciaux mal encodés parfois
                .replaceAll("[^\\p{L}\\p{N}\\p{P}\\p{Z}\\n\\t/€$%°]", " ")

                // Nettoyer les espaces réintroduits
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n ", "\n")
                .replaceAll(" \\n", "\n");
    }
}
