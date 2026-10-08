package com.example.crediSense.Service.impl;

import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.Dossier;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * L'e-mail d'un dossier CONDITIONNEL : il donne les chiffres du moteur, les conditions et les propositions,
 * avec un bouton vers l'espace client. Les autres décisions ne changent pas.
 */
class EmailConditionnelTest {

    private JavaMailSender mailSender;
    private ResultatEmailService service;
    private Dossier dossier;

    @BeforeEach
    void preparer() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
        service = new ResultatEmailService(mailSender, new RapportPdfService());
        ReflectionTestUtils.setField(service, "fromEmail", "banque@example.com");
        ReflectionTestUtils.setField(service, "frontendUrl", "https://credisense.example.tn/");

        Client client = new Client();
        client.setNom("Rehouma");
        client.setPrenom("Meriem");
        client.setEmail("client@example.com");
        dossier = Dossier.builder().id(UUID.fromString("1a2b3c4d-0000-0000-0000-000000000000")).client(client).build();
    }

    private Map<String, Object> conditionnel() {
        Map<String, Object> r = new HashMap<>();
        r.put("eligibility", "CONDITIONNEL");
        r.put("eligibilityScore", 58);
        r.put("creditType", "CONSOMMATION");
        r.put("summary", "Le taux d'endettement dépasse légèrement la limite acceptable.");
        r.put("rawExplanation", "La mensualité de 1 666,67 DT porte le DTI à 34,7 % (calcul de l'IA).");
        r.put("financialMetrics", Map.of("requestedAmount", 20000, "duration", 12, "monthlyPayment", 1758.318, "dti", 36.63));
        r.put("conditions", List.of("Assurance décès-invalidité signée", "Trois derniers relevés bancaires"));
        r.put("adjustedOffers", Map.of("applicable", true, "message", "interne", "unresolved", List.of(),
                "offers", List.of(
                        Map.of("kind", "MONTANT_REDUIT", "label", "Montant réduit, même durée", "amount", 16300,
                               "duration", 12, "monthlyPayment", 1433.029, "dti", 29.85, "totalCost", 17196.348, "explanation", "x"),
                        Map.of("kind", "DUREE_ALLONGEE", "label", "Même montant, durée allongée", "amount", 20000,
                               "duration", 18, "monthlyPayment", 1201.142, "dti", 25.02, "totalCost", 21620.549, "explanation", "y"))));
        return r;
    }

    private String courrier(Map<String, Object> resultat) throws Exception {
        service.envoyer(dossier, resultat);
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        StringBuilder texte = new StringBuilder();
        parcourir(captor.getValue(), texte);
        return texte.toString();
    }

    private void parcourir(Object contenu, StringBuilder texte) throws Exception {
        if (contenu instanceof MimeMessage m) parcourir(m.getContent(), texte);
        else if (contenu instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart p = mp.getBodyPart(i);
                if (p.getFileName() == null) parcourir(p.getContent(), texte);
            }
        } else if (contenu instanceof String s) texte.append(s);
    }

    @Test
    void lEmailDonneLesChiffresDuMoteur() throws Exception {
        String html = courrier(conditionnel());
        assertTrue(html.contains("Votre demande en chiffres"));
        assertTrue(html.contains("20 000 DT"));
        assertTrue(html.contains("12 mois"));
        assertTrue(html.contains("1 758 DT"));
        assertTrue(html.contains("36,63 %"));
    }

    @Test
    void lEmailListeLesConditionsARemplir() throws Exception {
        String html = courrier(conditionnel());
        assertTrue(html.contains("Conditions à remplir"));
        assertTrue(html.contains("Assurance décès-invalidité signée"));
        assertTrue(html.contains("Trois derniers relevés bancaires"));
    }

    @Test
    void lEmailPresenteChaquePropositionEtLeBoutonVersLEspaceClient() throws Exception {
        String html = courrier(conditionnel());
        assertTrue(html.contains("Nos propositions pour votre dossier"));
        assertTrue(html.contains("Option 1") && html.contains("Option 2"));
        assertTrue(html.contains("Montant réduit, même durée"));
        assertTrue(html.contains("16 300 DT"));
        assertTrue(html.contains("18 mois"));
        assertTrue(html.contains("Répondre à la proposition"));
        assertTrue(html.contains("href=\"https://credisense.example.tn/client/historique\""),
                "le lien ne doit pas avoir de double barre");
        assertTrue(html.contains("Aucune de ces propositions n'est appliquée sans votre accord"));
    }

    @Test
    void lEmailMontreLeResumeEtPasLeTexteLongDeLIAQuiCiteSesPropresChiffres() throws Exception {
        String html = courrier(conditionnel());
        assertTrue(html.contains("En résumé"));
        assertTrue(html.contains("Le taux d'endettement dépasse légèrement la limite acceptable."));
        assertFalse(html.contains("34,7 %"), "les chiffres de l'IA ne doivent pas contredire ceux du moteur");
        assertFalse(html.contains("1 666,67"));
    }

    @Test
    void sansResumeLEmailRepliSurLExplicationDetaillee() throws Exception {
        Map<String, Object> r = conditionnel();
        r.remove("summary");
        assertTrue(courrier(r).contains("calcul de l'IA"));
    }

    @Test
    void sansPropositionLEmailNAnnoncePasDeBoutonDeReponse() throws Exception {
        Map<String, Object> r = conditionnel();
        r.put("adjustedOffers", Map.of("applicable", false, "offers", List.of(), "unresolved", List.of(), "message", "interne"));
        String html = courrier(r);
        assertFalse(html.contains("Répondre à la proposition"));
        assertFalse(html.contains("Nos propositions"));
        assertTrue(html.contains("Conditions à remplir"));          // le reste du détail demeure
        assertFalse(html.contains("interne"));
    }

    @Test
    void sansDonneesLesBlocsSontOmisSansErreur() throws Exception {
        Map<String, Object> r = new HashMap<>();
        r.put("eligibility", "CONDITIONNEL");
        r.put("eligibilityScore", 55);
        String html = courrier(r);
        assertFalse(html.contains("Votre demande en chiffres"));
        assertFalse(html.contains("Conditions à remplir"));
        assertFalse(html.contains("Nos propositions"));
    }

    @Test
    void lesAutresDecisionsNeRecoiventPasLesBlocsConditionnels() throws Exception {
        for (String decision : new String[]{"ELIGIBLE", "REFUS", "A_COMPLETER"}) {
            reset(mailSender);
            when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
            Map<String, Object> r = conditionnel();
            r.put("eligibility", decision);
            r.put("rawExplanation", "Explication complète.");
            String html = courrier(r);
            assertFalse(html.contains("Nos propositions"), decision);
            assertFalse(html.contains("Votre demande en chiffres"), decision);
            assertTrue(html.contains("Analyse détaillée"), decision);
            assertTrue(html.contains("Explication complète."), decision);
        }
    }

    @Test
    void leScoreEstMisEnPageEnTableauPourLesMessageries() throws Exception {
        String html = courrier(conditionnel());
        int debut = html.indexOf("Score de crédit");
        String bloc = html.substring(Math.max(0, debut - 400), Math.min(html.length(), debut + 400));
        assertFalse(bloc.contains("display:flex"), "display:flex n'est pas pris en charge par les messageries");
        assertTrue(bloc.contains("<table"));
        assertTrue(html.contains("58 / 100"));
    }

    @Test
    void leTexteVenuDesPropositionsEstNeutralise() throws Exception {
        Map<String, Object> r = conditionnel();
        Map<String, Object> propositions = new HashMap<>((Map<String, Object>) r.get("adjustedOffers"));
        propositions.put("offers", List.of(Map.of("kind", "X", "label", "<script>alert(1)</script>", "amount", 5000,
                "duration", 12, "monthlyPayment", 500, "dti", 20, "totalCost", 6000, "explanation", "e")));
        r.put("adjustedOffers", propositions);
        r.put("conditions", List.of("<b>Garantie</b>"));
        String html = courrier(r);
        assertFalse(html.contains("<script>alert(1)"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertFalse(html.contains("<b>Garantie</b>"));
    }

    @Test
    void formatDesMontantsEtDesPourcentages() {
        assertEquals("16 300 DT", ResultatEmailService.dt(16300));
        assertEquals("1 758 DT", ResultatEmailService.dt(1758.318));
        assertEquals("36,63 %", ResultatEmailService.pourcent(36.63));
        assertNull(ResultatEmailService.dt(0), "un chiffre absent n'est pas affiché comme 0");
        assertNull(ResultatEmailService.dt(null));
        assertNull(ResultatEmailService.dt("abc"));
    }

    @Test
    void sansAdresseDeSiteLeLienResteRelatif() {
        String html = ResultatEmailService.blocPropositions(conditionnel(), "");
        assertTrue(html.contains("href=\"/client/historique\""));
    }
}
