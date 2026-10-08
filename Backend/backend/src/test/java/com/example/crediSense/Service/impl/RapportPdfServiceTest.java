package com.example.crediSense.Service.impl;

import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.Dossier;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Le rapport PDF d'une décision : ce que contiennent ses deux versions, et surtout ce que la
 * version CLIENT ne doit jamais contenir (contrôles internes, risques, CIN complet).
 */
class RapportPdfServiceTest {

    private RapportPdfService service;
    private Dossier dossier;

    @BeforeEach
    void preparer() {
        service = new RapportPdfService();
        Client client = new Client();
        client.setNom("Rehouma");
        client.setPrenom("Meriem");
        client.setCin("12015060");
        dossier = Dossier.builder().id(UUID.fromString("1a2b3c4d-0000-0000-0000-000000000000"))
                .client(client).montantCredit(1200.0).dureeCredit(12).build();
    }

    /** Résultat complet et favorable, tel que le renvoie le service IA. */
    private Map<String, Object> resultatEligible() {
        Map<String, Object> r = new HashMap<>();
        r.put("eligibility", "ELIGIBLE");
        r.put("eligibilityScore", 78);
        r.put("creditType", "CONSOMMATION");
        r.put("summary", "Dossier solide : capacité de remboursement confortable.");
        r.put("rawExplanation", "Votre revenu couvre largement la mensualité demandée.");
        r.put("tauxAnnuelApplique", 0.10);
        r.put("financialMetrics", Map.of("requestedAmount", 1200, "duration", 12, "monthlyPayment", 105.498,
                "monthlyIncome", 4800, "dti", 2.2, "existingDebts", 0));
        r.put("regulatoryChecks", List.of(
                Map.of("criterion", "Plafond du montant", "status", "OK", "value", "1 200 DT",
                       "threshold", "≤ 5 × salaire", "explanation", "Sous le plafond.", "blocking", true),
                Map.of("criterion", "Incidents de paiement", "status", "A_VERIFIER", "value", "inconnu",
                       "threshold", "0", "explanation", "À vérifier à la centrale des risques.", "blocking", false)));
        r.put("capacity", Map.of("maxMonthlyPayment", 1440, "remainingMonthly", 1334.5,
                "maxAmountForDuration", 16000, "referenceDuration", 12));
        r.put("simulations", List.of(
                Map.of("duration", 12, "monthlyPayment", 105.498, "dti", 2.2, "totalCost", 1265.976,
                       "status", "OK", "isRequested", true),
                Map.of("duration", 24, "monthlyPayment", 55.37, "dti", 1.2, "totalCost", 1328.88,
                       "status", "OK", "isRequested", false)));
        r.put("strengths", List.of(Map.of("title", "CDI depuis 4 ans", "detail", "Emploi stable.")));
        r.put("weaknesses", List.of("Aucune épargne visible"));
        r.put("risks", List.of(Map.of("level", "LOW", "description", "Risque interne confidentiel")));
        r.put("conditions", List.of("Assurance décès-invalidité"));
        r.put("calculationNote", "Mensualités calculées avec un taux annuel de 10.00 %.");
        return r;
    }

    private String texte(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
        }
    }

    // ── Format ───────────────────────────────────────────────────────────────

    @Test
    void le_resultat_est_un_vrai_pdf() {
        byte[] pdf = service.generer(dossier, resultatEligible(), RapportPdfService.Version.AGENT);

        assertTrue(pdf.length > 1000);
        assertEquals("%PDF", new String(pdf, 0, 4));
    }

    // ── Version client ───────────────────────────────────────────────────────

    @Test
    void version_client_contient_la_decision_l_explication_et_les_chiffres_de_la_demande() throws Exception {
        String t = texte(service.generer(dossier, resultatEligible(), RapportPdfService.Version.CLIENT));

        assertTrue(t.contains("Attijari Bank"));
        assertTrue(t.contains("Réponse à votre demande de crédit"));
        assertTrue(t.contains("Meriem Rehouma"));
        assertTrue(t.contains("ÉLIGIBLE"));
        assertTrue(t.contains("Score de crédit : 78 / 100"));
        assertTrue(t.contains("Votre revenu couvre largement la mensualité demandée."));
        assertTrue(t.contains("1 200,000 DT"));                  // montant demandé
        assertTrue(t.contains("12 mois"));
        assertTrue(t.contains("105,498 DT"));                    // mensualité estimée
        assertTrue(t.contains("10 %"));                          // taux appliqué
        assertTrue(t.contains("Assurance décès-invalidité"));    // condition avant décaissement
        assertFalse(t.contains("Attijariwafa"));
    }

    @Test
    void version_client_ne_contient_aucune_donnee_interne() throws Exception {
        String t = texte(service.generer(dossier, resultatEligible(), RapportPdfService.Version.CLIENT));

        assertFalse(t.toUpperCase().contains("CONTRÔLES RÉGLEMENTAIRES"));
        assertFalse(t.contains("Risque interne confidentiel"));
        assertFalse(t.contains("Aucune épargne visible"));       // point de vigilance
        assertFalse(t.contains("Capacité d'emprunt".toUpperCase()));
        assertFalse(t.contains("Revenu mensuel net"));
        assertFalse(t.contains("12015060"), "le CIN complet n'a pas à figurer dans le PDF du client");
        assertFalse(t.contains("usage interne"));
    }

    @Test
    void version_client_a_completer_pas_de_score_et_pas_de_reglage_technique() throws Exception {
        Map<String, Object> r = new HashMap<>(resultatEligible());
        r.put("eligibility", "A_COMPLETER");
        r.put("eligibilityScore", 59);
        r.put("donneesManquantes", List.of("Dettes existantes — relevé bancaire des 3 derniers mois",
                "Taux d'intérêt annuel — à renseigner par l'administrateur (réglage du service d'analyse)"));
        r.put("rawExplanation", "Pour finaliser l'étude de votre dossier, il manque : Dettes existantes.");

        String t = texte(service.generer(dossier, r, RapportPdfService.Version.CLIENT));

        assertTrue(t.contains("DOSSIER À COMPLÉTER"));
        assertFalse(t.contains("Score de crédit"), "un score provisoire ne doit pas partir chez le client");
        assertFalse(t.contains("59 / 100"));
        assertTrue(t.contains("Dettes existantes — relevé bancaire"));
        assertFalse(t.contains("administrateur"), "le client ne peut pas fournir le taux : c'est un réglage de la banque");
        assertFalse(t.contains("A_COMPLETER"));
    }

    // ── Version agent ────────────────────────────────────────────────────────

    @Test
    void version_agent_contient_le_rapport_complet() throws Exception {
        String t = texte(service.generer(dossier, resultatEligible(), RapportPdfService.Version.AGENT));

        assertTrue(t.contains("usage interne"));
        assertTrue(t.contains("12015060"));                          // CIN pour le conseiller
        assertTrue(t.contains("Dossier solide : capacité de remboursement confortable."));
        assertTrue(t.toUpperCase().contains("CONTRÔLES RÉGLEMENTAIRES"));
        assertTrue(t.contains("Plafond du montant"));
        assertTrue(t.contains("Conforme"));
        assertTrue(t.contains("À vérifier"));
        assertTrue(t.contains("<= 5 × salaire"), "≤ n'existe pas dans la police : remplacé par <=");
        assertTrue(t.toUpperCase().contains("CAPACITÉ D'EMPRUNT"));
        assertTrue(t.contains("16 000,000 DT"));
        assertTrue(t.contains("(demandée)"));
        assertTrue(t.contains("CDI depuis 4 ans"));
        assertTrue(t.contains("Aucune épargne visible"));
        assertTrue(t.contains("Risque interne confidentiel"));
        assertTrue(t.contains("Mensualités calculées avec un taux annuel de 10.00 %."));
        assertTrue(t.contains("Revenu mensuel net"));
        assertTrue(t.contains("4 800,000 DT"));
    }

    @Test
    void version_agent_a_completer_montre_le_score_provisoire_et_tout_ce_qui_manque() throws Exception {
        Map<String, Object> r = new HashMap<>(resultatEligible());
        r.put("eligibility", "A_COMPLETER");
        r.put("eligibilityScore", 59);
        r.put("donneesManquantes", List.of("Dettes existantes — relevé bancaire",
                "Taux d'intérêt annuel — à renseigner par l'administrateur (réglage du service d'analyse)"));

        String t = texte(service.generer(dossier, r, RapportPdfService.Version.AGENT));

        assertTrue(t.contains("Score provisoire : 59 / 100"));
        assertTrue(t.contains("Dettes existantes — relevé bancaire"));
        assertTrue(t.contains("à renseigner par l'administrateur"));
    }

    // ── Chiffres jamais inventés ─────────────────────────────────────────────

    @Test
    void une_mensualite_nulle_est_annoncee_non_calculee() throws Exception {
        Map<String, Object> r = new HashMap<>(resultatEligible());
        r.put("financialMetrics", Map.of("requestedAmount", 1200, "duration", 12, "monthlyPayment", 0, "dti", 0));

        String t = texte(service.generer(dossier, r, RapportPdfService.Version.AGENT));

        assertTrue(t.contains("Non calculée"));
        assertTrue(t.contains("Non calculé"));
        assertFalse(t.contains("0,000 DT Taux"), "pas de « 0 DT » pour une valeur non calculée");
    }

    @Test
    void sans_taux_applique_la_ligne_du_taux_n_apparait_pas() throws Exception {
        Map<String, Object> r = new HashMap<>(resultatEligible());
        r.remove("tauxAnnuelApplique");

        String t = texte(service.generer(dossier, r, RapportPdfService.Version.CLIENT));

        assertFalse(t.contains("Taux d'intérêt annuel appliqué"));
    }

    // ── Robustesse ───────────────────────────────────────────────────────────

    @Test
    void un_resultat_vide_ou_partiel_ne_fait_pas_planter_la_generation() {
        for (RapportPdfService.Version version : RapportPdfService.Version.values()) {
            byte[] pdf = service.generer(dossier, new HashMap<>(), version);
            assertEquals("%PDF", new String(pdf, 0, 4));
        }
        byte[] sansClient = service.generer(Dossier.builder().build(), resultatEligible(), RapportPdfService.Version.CLIENT);
        assertEquals("%PDF", new String(sansClient, 0, 4));
    }

    @Test
    void des_donnees_mal_formees_sont_ignorees() {
        Map<String, Object> r = new HashMap<>(resultatEligible());
        r.put("regulatoryChecks", "pas une liste");
        r.put("simulations", List.of("texte", 42, new HashMap<>()));
        r.put("financialMetrics", "pas une carte");
        r.put("strengths", List.of(new HashMap<>()));

        assertEquals("%PDF", new String(service.generer(dossier, r, RapportPdfService.Version.AGENT), 0, 4));
    }

    @Test
    void un_long_rapport_tient_sur_plusieurs_pages() throws Exception {
        Map<String, Object> r = new HashMap<>(resultatEligible());
        r.put("weaknesses", java.util.stream.IntStream.range(0, 120)
                .mapToObj(i -> "Point de vigilance numéro " + i + " avec un texte assez long pour remplir la page")
                .toList());

        byte[] pdf = service.generer(dossier, r, RapportPdfService.Version.AGENT);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertTrue(document.getNumberOfPages() > 1);
        }
        assertTrue(texte(pdf).contains("Point de vigilance numéro 119"));   // rien n'est tronqué
    }

    // ── Aides ────────────────────────────────────────────────────────────────

    @Test
    void format_des_montants_en_dinars() {
        assertEquals("2 100,000 DT", RapportPdfService.dt(2100));
        assertEquals("1 234 567,500 DT", RapportPdfService.dt(1234567.5));
        assertEquals("105,498 DT", RapportPdfService.dt("105.498"));
        assertEquals("—", RapportPdfService.dt(null));
        assertEquals("—", RapportPdfService.dt("abc"));
        assertEquals("—", RapportPdfService.dt(Double.NaN));
        assertFalse(RapportPdfService.dt(2100).contains(" "), "espace fine insécable : absente de la police");
    }

    @Test
    void nettoyage_des_caracteres_hors_police() {
        assertEquals("<= 5 et >= 3 -> ok", RapportPdfService.nettoyer("≤ 5 et ≥ 3 → ok"));
        assertEquals("é à ç — « test »", RapportPdfService.nettoyer("é à ç — « test »"));   // le français est conservé
        assertEquals("????? ", RapportPdfService.nettoyer("ياسين "));                        // 5 lettres arabes : absentes de la police
        assertEquals("a b c", RapportPdfService.nettoyer("a b c"));
        assertEquals("", RapportPdfService.nettoyer(null));
    }

    @Test
    void libelles_des_decisions() {
        assertEquals("ÉLIGIBLE", RapportPdfService.libelleDecision("ELIGIBLE"));
        assertEquals("DOSSIER À COMPLÉTER", RapportPdfService.libelleDecision("A_COMPLETER"));
        assertEquals("EN COURS D'ANALYSE", RapportPdfService.libelleDecision("INDETERMINE"));
        assertEquals("EN COURS D'ANALYSE", RapportPdfService.libelleDecision(null));
    }

    // ── Exemples à consulter ─────────────────────────────────────────────────

    /** Écrit des rapports d'exemple dans target/rapports-exemples/ : à ouvrir pour juger la mise en page. */
    @Test
    void ecrit_des_exemples_consultables() throws Exception {
        java.nio.file.Path dossierSortie = java.nio.file.Path.of("target", "rapports-exemples");
        java.nio.file.Files.createDirectories(dossierSortie);

        Map<String, Object> aCompleter = new HashMap<>(resultatEligible());
        aCompleter.put("eligibility", "A_COMPLETER");
        aCompleter.put("eligibilityScore", 59);
        aCompleter.put("donneesManquantes", List.of("Dettes existantes — relevé bancaire des 3 derniers mois"));
        aCompleter.put("rawExplanation", "Pour finaliser l'étude de votre dossier, il manque : Dettes existantes.");

        java.nio.file.Files.write(dossierSortie.resolve("eligible-client.pdf"),
                service.generer(dossier, resultatEligible(), RapportPdfService.Version.CLIENT));
        java.nio.file.Files.write(dossierSortie.resolve("eligible-agent.pdf"),
                service.generer(dossier, resultatEligible(), RapportPdfService.Version.AGENT));
        java.nio.file.Files.write(dossierSortie.resolve("a-completer-client.pdf"),
                service.generer(dossier, aCompleter, RapportPdfService.Version.CLIENT));

        assertTrue(java.nio.file.Files.size(dossierSortie.resolve("eligible-agent.pdf")) > 1000);
    }

    // ── Propositions d'ajustement (dossier conditionnel) ─────────────────────

    private Map<String, Object> resultatConditionnel() {
        Map<String, Object> r = resultatEligible();
        r.put("eligibility", "CONDITIONNEL");
        r.put("adjustedOffers", Map.of(
                "applicable", true,
                "message", "Propositions indicatives, calculées par les règles, sous réserve de validation par l'agent.",
                "unresolved", List.of(),
                "offers", List.of(
                        Map.of("kind", "MONTANT_REDUIT", "label", "Montant réduit, même durée", "amount", 6800,
                               "duration", 12, "monthlyPayment", 597.828, "dti", 29.89, "totalCost", 7173.936,
                               "explanation", "x"),
                        Map.of("kind", "DUREE_ALLONGEE", "label", "Même montant, durée allongée", "amount", 10000,
                               "duration", 24, "monthlyPayment", 461.449, "dti", 23.07, "totalCost", 11074.782,
                               "explanation", "y"))));
        return r;
    }

    @Test
    void lePdfAgentContientLesPropositionsDAjustement() throws Exception {
        String t = texte(service.generer(dossier, resultatConditionnel(), RapportPdfService.Version.AGENT));
        assertTrue(t.toUpperCase().contains("PROPOSITIONS D'AJUSTEMENT"), t);     // titres de section en capitales
        assertTrue(t.contains("Montant réduit, même durée"), t);
        assertTrue(t.contains("Même montant, durée allongée"), t);
        assertTrue(t.contains("sous réserve de validation"), t);
        assertTrue(t.contains("24 mois"), t);
    }

    @Test
    void lePdfClientContientLesPropositionsEtInviteARepondreDepuisSonEspace() throws Exception {
        String t = texte(service.generer(dossier, resultatConditionnel(), RapportPdfService.Version.CLIENT));
        assertTrue(t.toUpperCase().contains("PROPOSITIONS DE VOTRE CONSEILLER"), t);
        assertTrue(t.contains("Montant réduit, même durée"), t);
        assertTrue(t.contains("Même montant, durée allongée"), t);
        assertTrue(t.contains("espace client"), t);
        assertTrue(t.contains("Aucune n'est appliquée sans votre accord"), t);
        // le message interne destiné à l'agent n'est pas repris
        assertFalse(t.contains("sous réserve de validation par l'agent"), t);
        assertFalse(t.toUpperCase().contains("PROPOSITIONS D'AJUSTEMENT"), t);
    }

    @Test
    void lePdfClientNAffichePasDePropositionQuandIlNYEnAPas() throws Exception {
        Map<String, Object> r = resultatConditionnel();
        r.put("adjustedOffers", Map.of("applicable", false, "offers", List.of(), "unresolved", List.of(),
                "message", "L'endettement est déjà respecté : message interne."));
        String t = texte(service.generer(dossier, r, RapportPdfService.Version.CLIENT));
        assertFalse(t.toUpperCase().contains("PROPOSITIONS"), t);
        assertFalse(t.contains("message interne"), t);
    }

    @Test
    void lePdfClientNeMontrePasDePropositionPourUneAutreDecision() throws Exception {
        Map<String, Object> r = resultatConditionnel();
        r.put("eligibility", "ELIGIBLE");
        String t = texte(service.generer(dossier, r, RapportPdfService.Version.CLIENT));
        assertFalse(t.toUpperCase().contains("PROPOSITIONS DE VOTRE CONSEILLER"), t);
    }

    @Test
    void lePdfClientConditionnelUtiliseLeResumeEtNonLeTexteLongDeLIA() throws Exception {
        Map<String, Object> r = resultatConditionnel();
        r.put("summary", "Résumé prudent sans chiffres.");
        r.put("rawExplanation", "Mensualité de 1 666,67 DT et endettement de 34,7 % calculés par l'IA.");
        String t = texte(service.generer(dossier, r, RapportPdfService.Version.CLIENT));
        assertTrue(t.contains("Résumé prudent sans chiffres."), t);
        assertFalse(t.contains("34,7 %"), t);
    }

    @Test
    void lePdfClientNonConditionnelGardeLExplicationDetaillee() throws Exception {
        Map<String, Object> r = resultatEligible();
        String t = texte(service.generer(dossier, r, RapportPdfService.Version.CLIENT));
        assertTrue(t.contains("Votre revenu couvre largement la mensualité demandée."), t);
    }

    @Test
    void sansPropositionsAucuneSectionNEstAjoutee() throws Exception {
        String t = texte(service.generer(dossier, resultatEligible(), RapportPdfService.Version.AGENT));
        assertFalse(t.toUpperCase().contains("PROPOSITIONS D'AJUSTEMENT"), t);
    }

    @Test
    void sansOffreLeMessageEstAfficheSansTableau() throws Exception {
        Map<String, Object> r = resultatEligible();
        r.put("adjustedOffers", Map.of("applicable", false, "offers", List.of(), "unresolved", List.of(),
                "message", "L'endettement, le plafond et la durée sont déjà respectés."));
        String t = texte(service.generer(dossier, r, RapportPdfService.Version.AGENT));
        assertTrue(t.contains("déjà respectés"), t);
        assertFalse(t.contains("Proposition Montant"), t);            // pas d'en-tête du tableau des offres
    }

    @Test
    void desPropositionsMalFormeesNePlantentPas() throws Exception {
        Map<String, Object> r = resultatEligible();
        r.put("adjustedOffers", Map.of("applicable", true, "message", "m", "unresolved", List.of(),
                "offers", List.of("texte", 42, new HashMap<>())));
        assertTrue(service.generer(dossier, r, RapportPdfService.Version.AGENT).length > 0);
    }

    // ── Détail du score (grille à points) ────────────────────────────────────

    private Map<String, Object> resultatAvecDetailScore(boolean provisoire, boolean prototype) {
        Map<String, Object> r = resultatEligible();
        List<Map<String, Object>> criteres = new java.util.ArrayList<>(List.of(
                Map.of("id", "endettement", "libelle", "Taux d'endettement", "points", 36, "maximum", 40, "connu", true, "valeur", "22,77 %", "explication", "x"),
                Map.of("id", "contrat", "libelle", "Stabilité du contrat", "points", 20, "maximum", 20, "connu", true, "valeur", "CDI", "explication", "x")));
        Map<String, Object> inconnu = new HashMap<>();
        inconnu.put("id", "incidents"); inconnu.put("libelle", "Incidents de paiement"); inconnu.put("points", null);
        inconnu.put("maximum", 15); inconnu.put("connu", false); inconnu.put("valeur", "inconnus"); inconnu.put("explication", "x");
        criteres.add(inconnu);
        r.put("scoreDetail", Map.of("methode", "grille-v1", "total", 78, "provisoire", provisoire, "prototype", prototype,
                "criteres", criteres));
        return r;
    }

    @Test
    void lePdfAgentDetailleLeScoreParCritere() throws Exception {
        String t = texte(service.generer(dossier, resultatAvecDetailScore(true, true), RapportPdfService.Version.AGENT));
        assertTrue(t.toUpperCase().contains("DÉTAIL DU SCORE"), t);
        assertTrue(t.contains("Taux d'endettement"), t);
        assertTrue(t.contains("Stabilité du contrat"), t);
        assertTrue(t.contains("écarté"), "un critère inconnu n'est pas affiché comme 0 : " + t);
        assertTrue(t.contains("Score : 78 / 100"), t);
        assertTrue(t.contains("provisoire"), t);
    }

    @Test
    void lePdfAgentSignaleQueLaGrilleEstUnPrototype() throws Exception {
        String prototype = texte(service.generer(dossier, resultatAvecDetailScore(false, true), RapportPdfService.Version.AGENT));
        assertTrue(prototype.contains("Prototype"), prototype);
        String valide = texte(service.generer(dossier, resultatAvecDetailScore(false, false), RapportPdfService.Version.AGENT));
        assertFalse(valide.contains("Prototype"), valide);
    }

    @Test
    void lePdfClientNeContientPasLeDetailDuScore() throws Exception {
        String t = texte(service.generer(dossier, resultatAvecDetailScore(false, true), RapportPdfService.Version.CLIENT));
        assertFalse(t.toUpperCase().contains("DÉTAIL DU SCORE"), t);
        assertFalse(t.contains("Stabilité du contrat"), t);
    }

    @Test
    void sansDetailDeScoreAucuneSectionNEstAjoutee() throws Exception {
        String t = texte(service.generer(dossier, resultatEligible(), RapportPdfService.Version.AGENT));
        assertFalse(t.toUpperCase().contains("DÉTAIL DU SCORE"), t);
    }

    @Test
    void unDetailDeScoreMalFormeNePlantePas() throws Exception {
        Map<String, Object> r = resultatEligible();
        r.put("scoreDetail", Map.of("total", 50, "criteres", List.of("texte", 42, new HashMap<>())));
        assertTrue(service.generer(dossier, r, RapportPdfService.Version.AGENT).length > 0);
    }
}
