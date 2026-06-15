package com.example.crediSense.Service.impl;


import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class Textcleaningservice {

    public String nettoyer(String texteBrut) {
        if (texteBrut == null || texteBrut.isBlank()) {
            log.warn("Texte brut vide ou null");
            return "";
        }

        log.info("Nettoyage texte — {} caractères en entrée", texteBrut.length());

        String texte = texteBrut;

        // 1. Supprimer caractères de contrôle (sauf \n et \t)
        texte = texte.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");

        // 2. Normaliser les retours à la ligne Windows (\r\n → \n)
        texte = texte.replaceAll("\\r\\n|\\r", "\n");

        // 3. Supprimer les lignes vides multiples (max 2 sauts consécutifs)
        texte = texte.replaceAll("\\n{3,}", "\n\n");

        // 4. Supprimer les espaces multiples sur une même ligne
        texte = texte.replaceAll("[ \\t]+", " ");

        // 5. Nettoyer chaque ligne (trim gauche/droite)
        StringBuilder sb = new StringBuilder();
        for (String ligne : texte.split("\n")) {
            sb.append(ligne.strip()).append("\n");
        }
        texte = sb.toString().strip();

        log.info("Nettoyage terminé — {} caractères en sortie", texte.length());
        return texte;
    }

}
