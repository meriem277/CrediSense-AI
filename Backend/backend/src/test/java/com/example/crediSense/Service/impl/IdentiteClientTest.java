package com.example.crediSense.Service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Les documents du client et sa fiche désignent-ils la même personne ? La comparaison ne doit
 * pas dépendre de l'ordre nom / prénom (fiche client ou document inversés), des accents, de la
 * casse, ni des noms composés ; mais deux personnes différentes doivent toujours être détectées.
 */
class IdentiteClientTest {

    @Test
    void nom_et_prenom_inverses_sont_la_meme_personne() {
        // le cas réel : documents « Rehouma Meriem », fiche client nom = « meriem », prénom = « rehouma »
        assertTrue(FichierServiceImpl.memeIdentite("Rehouma", "Meriem", "meriem", "rehouma"));
        assertTrue(FichierServiceImpl.memeIdentite("Meriem", "Rehouma", "rehouma", "meriem"));
    }

    @Test
    void meme_ordre_meme_personne() {
        assertTrue(FichierServiceImpl.memeIdentite("TRABELSI", "Yassine", "Trabelsi", "Yassine"));
    }

    @Test
    void casse_et_accents_ignores() {
        assertTrue(FichierServiceImpl.memeIdentite("BEN SALAH", "Hélène", "ben salah", "helene"));
        assertTrue(FichierServiceImpl.memeIdentite("Rehouma", "Meriem", "REHOUMA", "MERIEM"));
    }

    @Test
    void noms_composes_et_prenoms_multiples() {
        assertTrue(FichierServiceImpl.memeIdentite("Ben Ali", "Sami", "Ben Ali", "Sami Mohamed"));
        assertTrue(FichierServiceImpl.memeIdentite("Sami", "Ben Ali", "Ben Ali", "Sami"));
    }

    @Test
    void ocr_qui_colle_ou_coupe_un_mot() {
        // « Trabelsi » lu « Trabelsii » : l'un contient l'autre
        assertTrue(FichierServiceImpl.memeIdentite("Trabelsii", "Yassine", "Trabelsi", "Yassine"));
    }

    @Test
    void deux_personnes_differentes_sont_detectees() {
        assertFalse(FichierServiceImpl.memeIdentite("Trabelsi", "Yassine", "Rehouma", "Meriem"));
        assertFalse(FichierServiceImpl.memeIdentite("Ben Ali", "Sami", "Trabelsi", "Yassine"));
    }

    @Test
    void un_seul_mot_en_commun_ne_suffit_pas() {
        // même prénom, noms différents : ce n'est pas la même personne
        assertFalse(FichierServiceImpl.memeIdentite("Trabelsi", "Sami", "Ben Ali", "Sami"));
    }

    @Test
    void rien_a_comparer_ne_declenche_pas_d_alerte() {
        assertTrue(FichierServiceImpl.memeIdentite(null, null, "Rehouma", "Meriem"));
        assertTrue(FichierServiceImpl.memeIdentite("Rehouma", "Meriem", null, null));
        assertTrue(FichierServiceImpl.memeIdentite("", "  ", "Rehouma", "Meriem"));
    }

    @Test
    void noms_arabes() {
        assertTrue(FichierServiceImpl.memeIdentite("الطرابلسي", "ياسين", "ياسين", "الطرابلسي"));
        assertFalse(FichierServiceImpl.memeIdentite("الطرابلسي", "ياسين", "بن علي", "سامي"));
    }
}
