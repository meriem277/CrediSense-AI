package com.example.crediSense.controller;

import com.example.crediSense.Service.DossierService;
import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.repository.AgentRepository;
import com.example.crediSense.repository.DecisionFinaleRepository;
import com.example.crediSense.repository.DossierRepository;
import com.example.crediSense.repository.JsonExtractionRepository;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * L'e-mail de résultat part chez le CLIENT. On vérifie ce qu'il contient réellement pour
 * chaque décision, en particulier pour « A_COMPLETER » : pas de score provisoire, pas de
 * code interne, pas de réglage technique.
 */
class DossierControllerEmailTest {

    private JavaMailSender mailSender;
    private DossierController controller;
    private final UUID dossierId = UUID.randomUUID();

    @BeforeEach
    void preparer() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));

        DossierRepository dossierRepository = mock(DossierRepository.class);
        Client client = new Client();
        client.setNom("Trabelsi");
        client.setPrenom("Yassine");
        client.setEmail("client@example.com");
        Dossier dossier = Dossier.builder().id(dossierId).client(client).build();
        when(dossierRepository.findById(dossierId)).thenReturn(Optional.of(dossier));

        controller = new DossierController(
                mock(DossierService.class), dossierRepository, mock(AgentRepository.class),
                mock(DecisionFinaleRepository.class), mock(JsonExtractionRepository.class), mailSender);
        ReflectionTestUtils.setField(controller, "fromEmail", "banque@example.com");
    }

    private String envoyer(String decision, int score, String explication) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eligibility", decision);
        payload.put("eligibilityScore", score);
        payload.put("creditType", "CONSOMMATION");
        payload.put("rawExplanation", explication);

        ResponseEntity<Map<String, Object>> reponse = controller.sendResultEmail(dossierId, payload);
        assertTrue(reponse.getStatusCode().is2xxSuccessful(), "envoi refusé : " + reponse.getBody());

        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(message.capture());
        return texteHtml(message.getValue());
    }

    /** Récupère le HTML d'un message MIME (le contenu est imbriqué dans des Multipart). */
    private static String texteHtml(Object contenu) throws Exception {
        if (contenu instanceof MimeMessage m) return texteHtml(m.getContent());
        if (contenu instanceof Multipart mp) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart partie = mp.getBodyPart(i);
                sb.append(texteHtml(partie.getContent()));
            }
            return sb.toString();
        }
        return String.valueOf(contenu);
    }

    @Test
    void decisionAcompleter_pasDeScore_pasDeCodeInterne_messageAdapte() throws Exception {
        String html = envoyer("A_COMPLETER", 59,
                "Pour finaliser l'étude de votre dossier, il manque : Dettes existantes — relevé bancaire.");

        assertFalse(html.contains("Score de crédit"), "un score provisoire ne doit pas être envoyé");
        assertFalse(html.contains("59 / 100"));
        assertFalse(html.contains("A_COMPLETER"), "le code interne ne doit pas apparaître");
        assertTrue(html.contains("DOSSIER À COMPLÉTER"));
        assertTrue(html.contains("incomplet"));
        assertTrue(html.contains("Pour finaliser l'étude de votre dossier"));
    }

    @Test
    void decisionEligible_afficheToujoursLeScore_commeAvant() throws Exception {
        String html = envoyer("ELIGIBLE", 82, "Dossier solide.");

        assertTrue(html.contains("Score de crédit"));
        assertTrue(html.contains("82 / 100"));
        assertTrue(html.contains("ELIGIBLE"));
        assertTrue(html.contains("Félicitations"));
    }

    @Test
    void decisionRefus_afficheLeScore() throws Exception {
        String html = envoyer("REFUS", 20, "Taux d'endettement trop élevé.");

        assertTrue(html.contains("20 / 100"));
        assertTrue(html.contains("REFUS"));
    }
}
