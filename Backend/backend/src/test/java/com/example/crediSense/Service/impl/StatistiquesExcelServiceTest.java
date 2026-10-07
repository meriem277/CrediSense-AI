package com.example.crediSense.Service.impl;

import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.DecisionFinale;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.repository.DecisionFinaleRepository;
import com.example.crediSense.repository.DossierRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L'export Excel est relu avec POI : on vérifie ce que verra l'administrateur dans Excel (feuilles,
 * en-têtes, vrais nombres et vraies dates pour pouvoir trier et filtrer, filtre automatique).
 */
class StatistiquesExcelServiceTest {

    private static final LocalDate DU = LocalDate.of(2026, 10, 1);
    private static final LocalDate AU = LocalDate.of(2026, 10, 10);

    private final StatistiquesExcelService excel = new StatistiquesExcelService();
    private StatistiquesService statistiques;
    private final List<DecisionFinale> lignes = new ArrayList<>();

    @BeforeEach
    void preparer() {
        DecisionFinaleRepository decisions = mock(DecisionFinaleRepository.class);
        DossierRepository dossiers = mock(DossierRepository.class);
        statistiques = new StatistiquesService(decisions, dossiers);
        when(decisions.findDecisionsEntre(any(), any())).thenAnswer(i -> lignes);
        when(dossiers.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any())).thenReturn(12L);
    }

    private DecisionFinale decision(String decision, double montant, int heures, String json) {
        Client client = new Client();
        client.setNom("Rehouma");
        client.setPrenom("Meriem");
        client.setCin("12015060");
        LocalDateTime depot = LocalDateTime.of(2026, 10, 3, 8, 0);
        Dossier dossier = Dossier.builder().id(UUID.randomUUID()).createdAt(depot).montantCredit(montant)
                .dureeCredit(12).client(client).build();
        DecisionFinale df = DecisionFinale.builder().id(UUID.randomUUID()).decisionFinale(decision).scoreFinal(70.0)
                .decisionLe(depot.plusHours(heures)).resultatComplet(json).dossier(dossier).build();
        lignes.add(df);
        return df;
    }

    private XSSFWorkbook classeur() throws Exception {
        byte[] fichier = excel.generer(statistiques.rapport(DU, AU));
        return new XSSFWorkbook(new ByteArrayInputStream(fichier));
    }

    /** Valeur de la 2e colonne de la ligne dont la 1re colonne contient le libellé. */
    private Cell valeurDe(Sheet feuille, String libelle) {
        for (Row r : feuille) {
            Cell c = r.getCell(0);
            if (c != null && c.getCellType() == CellType.STRING && c.getStringCellValue().contains(libelle)) {
                return r.getCell(1);
            }
        }
        fail("Ligne introuvable : " + libelle);
        return null;
    }

    // ── Structure ────────────────────────────────────────────────────────────

    @Test
    void le_classeur_a_quatre_feuilles_nommees() throws Exception {
        try (XSSFWorkbook c = classeur()) {
            assertEquals(4, c.getNumberOfSheets());
            assertEquals("Synthèse", c.getSheetName(0));
            assertEquals("Décisions", c.getSheetName(1));
            assertEquals("Motifs", c.getSheetName(2));
            assertEquals("Évolution", c.getSheetName(3));
        }
    }

    @Test
    void le_fichier_est_un_vrai_xlsx() {
        byte[] fichier = excel.generer(statistiques.rapport(DU, AU));

        assertEquals('P', fichier[0]);                // un .xlsx est une archive zip : « PK »
        assertEquals('K', fichier[1]);
    }

    // ── Synthèse ─────────────────────────────────────────────────────────────

    @Test
    void la_synthese_donne_la_periode_les_volumes_et_les_taux_en_vrais_nombres() throws Exception {
        decision("ELIGIBLE", 1000, 2, null);
        decision("ELIGIBLE", 3000, 4, null);
        decision("REFUS", 2000, 6, null);
        decision("REFUS", 2000, 8, null);

        try (XSSFWorkbook c = classeur()) {
            Sheet s = c.getSheet("Synthèse");
            assertTrue(s.getRow(1).getCell(0).getStringCellValue().contains("du 2026-10-01 au 2026-10-10"));

            assertEquals(12.0, valeurDe(s, "Dossiers déposés").getNumericCellValue());
            assertEquals(4.0, valeurDe(s, "Décisions rendues").getNumericCellValue());
            Cell taux = valeurDe(s, "Taux d'acceptation (éligibles)");
            assertEquals(CellType.NUMERIC, taux.getCellType());                    // un vrai nombre, pas du texte
            assertEquals(0.5, taux.getNumericCellValue(), 1e-9);
            assertEquals("0.0%", taux.getCellStyle().getDataFormatString());       // affiché « 50,0 % »
            assertEquals(2000.0, valeurDe(s, "Montant moyen demandé").getNumericCellValue(), 1e-9);
        }
    }

    @Test
    void sans_decision_definitive_les_taux_affichent_un_tiret_et_pas_zero() throws Exception {
        decision("A_COMPLETER", 1000, 2, null);

        try (XSSFWorkbook c = classeur()) {
            Cell taux = valeurDe(c.getSheet("Synthèse"), "Taux d'acceptation (éligibles)");
            assertEquals(CellType.STRING, taux.getCellType());
            assertEquals("—", taux.getStringCellValue());
        }
    }

    @Test
    void les_delais_sont_en_heures_avec_moyenne_mediane_et_maximum() throws Exception {
        decision("ELIGIBLE", 1000, 2, null);
        decision("ELIGIBLE", 1000, 4, null);
        decision("REFUS", 1000, 12, null);

        try (XSSFWorkbook c = classeur()) {
            Row r = null;
            for (Row ligne : c.getSheet("Synthèse")) {
                Cell a = ligne.getCell(0);
                if (a != null && a.getCellType() == CellType.STRING && a.getStringCellValue().startsWith("Délai de traitement")) r = ligne;
            }
            assertNotNull(r);
            assertEquals(6.0, r.getCell(1).getNumericCellValue(), 1e-9);    // moyenne
            assertEquals(4.0, r.getCell(2).getNumericCellValue(), 1e-9);    // médiane
            assertEquals(12.0, r.getCell(3).getNumericCellValue(), 1e-9);   // maximum
            assertEquals(3.0, r.getCell(4).getNumericCellValue(), 1e-9);    // nombre de dossiers
        }
    }

    // ── Décisions ────────────────────────────────────────────────────────────

    @Test
    void la_feuille_des_decisions_a_un_entete_figé_un_filtre_et_une_ligne_par_decision() throws Exception {
        decision("REFUS", 2000, 30,
                "{\"versionRegles\":\"2026-10-a\",\"regulatoryChecks\":[{\"criterion\":\"Taux d'endettement\",\"status\":\"KO\"}]}")
                .setEmailStatut("ENVOYE");
        decision("ELIGIBLE", 1000, 2, null);

        try (XSSFWorkbook c = classeur()) {
            XSSFSheet s = c.getSheet("Décisions");
            assertEquals("Référence", s.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Décision", s.getRow(0).getCell(5).getStringCellValue());
            assertEquals("Version des règles", s.getRow(0).getCell(13).getStringCellValue());
            assertEquals(3, s.getPhysicalNumberOfRows());                       // en-tête + 2 décisions
            assertNotNull(s.getCTWorksheet().getAutoFilter(), "filtre automatique attendu");
            assertEquals(1, s.getPaneInformation().getHorizontalSplitPosition());   // en-tête figé
        }
    }

    @Test
    void les_dates_sont_de_vraies_dates_et_les_montants_de_vrais_nombres() throws Exception {
        decision("REFUS", 2500.5, 30, null);

        try (XSSFWorkbook c = classeur()) {
            Row r = c.getSheet("Décisions").getRow(1);
            Cell depot = r.getCell(2);
            assertEquals(CellType.NUMERIC, depot.getCellType());
            assertTrue(DateUtil.isCellDateFormatted(depot), "une vraie date, triable et filtrable dans Excel");
            assertEquals(LocalDateTime.of(2026, 10, 3, 8, 0), depot.getLocalDateTimeCellValue());
            assertEquals(LocalDateTime.of(2026, 10, 4, 14, 0), r.getCell(3).getLocalDateTimeCellValue());   // +30 h
            assertEquals(30.0, r.getCell(4).getNumericCellValue(), 1e-9);                                    // délai
            assertEquals(2500.5, r.getCell(7).getNumericCellValue(), 1e-9);                                  // montant
            assertEquals(12.0, r.getCell(8).getNumericCellValue(), 1e-9);                                    // durée
        }
    }

    @Test
    void libelles_lisibles_et_aucune_donnee_d_identite_sensible() throws Exception {
        decision("A_COMPLETER", 1000, 2, null).setEmailStatut("EN_ATTENTE_VALIDATION");

        try (XSSFWorkbook c = classeur()) {
            Row r = c.getSheet("Décisions").getRow(1);
            assertEquals("À compléter", r.getCell(5).getStringCellValue());            // pas le code interne
            assertEquals("En attente de validation", r.getCell(10).getStringCellValue());
            assertEquals("Meriem Rehouma", r.getCell(1).getStringCellValue());

            // le CIN du client n'est écrit nulle part dans le fichier
            for (Sheet s : c) for (Row ligne : s) for (Cell cellule : ligne) {
                if (cellule.getCellType() == CellType.STRING) assertFalse(cellule.getStringCellValue().contains("12015060"));
            }
        }
    }

    @Test
    void une_decision_sans_dates_ni_montant_laisse_des_cellules_vides() throws Exception {
        DecisionFinale df = decision("REFUS", 0, 2, null);
        df.getDossier().setMontantCredit(null);
        df.getDossier().setDureeCredit(null);
        df.getDossier().setCreatedAt(null);

        try (XSSFWorkbook c = classeur()) {
            Row r = c.getSheet("Décisions").getRow(1);
            assertEquals(CellType.BLANK, r.getCell(2).getCellType());
            assertEquals(CellType.BLANK, r.getCell(4).getCellType());
            assertEquals(CellType.BLANK, r.getCell(7).getCellType());
            assertEquals(CellType.BLANK, r.getCell(8).getCellType());
        }
    }

    // ── Motifs et évolution ──────────────────────────────────────────────────

    @Test
    void la_feuille_des_motifs_liste_les_criteres_avec_leur_nombre() throws Exception {
        String json = "{\"regulatoryChecks\":[{\"criterion\":\"Taux d'endettement\",\"status\":\"KO\"}]}";
        decision("REFUS", 1000, 2, json);
        decision("REFUS", 1000, 3, json);

        try (XSSFWorkbook c = classeur()) {
            Sheet s = c.getSheet("Motifs");
            assertEquals("Refus", s.getRow(1).getCell(0).getStringCellValue());
            assertEquals("Taux d'endettement", s.getRow(1).getCell(1).getStringCellValue());
            assertEquals(2.0, s.getRow(1).getCell(2).getNumericCellValue(), 1e-9);
        }
    }

    @Test
    void la_feuille_d_evolution_a_une_ligne_par_jour_de_la_periode() throws Exception {
        decision("ELIGIBLE", 1000, 2, null);

        try (XSSFWorkbook c = classeur()) {
            Sheet s = c.getSheet("Évolution");
            assertEquals(11, s.getPhysicalNumberOfRows());                      // en-tête + 10 jours
            assertEquals("Jour", s.getRow(0).getCell(0).getStringCellValue());
            Row troisOctobre = s.getRow(3);
            assertTrue(DateUtil.isCellDateFormatted(troisOctobre.getCell(0)));
            assertEquals(1.0, troisOctobre.getCell(1).getNumericCellValue(), 1e-9);    // éligibles
            assertEquals(1.0, troisOctobre.getCell(5).getNumericCellValue(), 1e-9);    // total
        }
    }

    @Test
    void sur_une_longue_periode_la_premiere_colonne_dit_semaine_du() throws Exception {
        byte[] fichier = excel.generer(statistiques.rapport(LocalDate.of(2026, 7, 1), AU));

        try (XSSFWorkbook c = new XSSFWorkbook(new ByteArrayInputStream(fichier))) {
            assertEquals("Semaine du", c.getSheet("Évolution").getRow(0).getCell(0).getStringCellValue());
        }
    }

    // ── Cas limites ──────────────────────────────────────────────────────────

    @Test
    void sans_aucune_donnee_le_fichier_est_quand_meme_valide() throws Exception {
        try (XSSFWorkbook c = classeur()) {
            assertEquals(1, c.getSheet("Décisions").getPhysicalNumberOfRows());   // en-tête seul
            assertEquals(1, c.getSheet("Motifs").getPhysicalNumberOfRows());
        }
    }

    @Test
    void libelles_des_codes_internes() {
        assertEquals("Éligible", StatistiquesExcelService.libelleDecision("ELIGIBLE"));
        assertEquals("Indéterminée", StatistiquesExcelService.libelleDecision("INDETERMINE"));
        assertEquals("", StatistiquesExcelService.libelleDecision(null));
        assertEquals("Aucun envoi tenté", StatistiquesExcelService.libelleStatut(null));
        assertEquals("Annulée par un agent", StatistiquesExcelService.libelleStatut("ANNULE"));
        assertEquals("Après validation", StatistiquesExcelService.libelleMode("VALIDATION"));
        assertEquals("", StatistiquesExcelService.libelleMode(null));
    }
}
