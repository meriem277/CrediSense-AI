package com.example.crediSense.Service.impl;

import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.Dossier;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * L'e-mail de décision envoyé au client : nom de la banque, rapport PDF en pièce jointe, et
 * robustesse (pas d'adresse, PDF impossible à générer, texte venu de l'IA).
 */
class ResultatEmailServiceTest {

    private JavaMailSender mailSender;
    private ResultatEmailService service;
    private Dossier dossier;

    @BeforeEach
    void preparer() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));

        service = new ResultatEmailService(mailSender, new RapportPdfService());
        ReflectionTestUtils.setField(service, "fromEmail", "banque@example.com");

        Client client = new Client();
        client.setNom("Rehouma");
        client.setPrenom("Meriem");
        client.setEmail("client@example.com");
        dossier = Dossier.builder().id(UUID.fromString("1a2b3c4d-0000-0000-0000-000000000000")).client(client).build();
    }

    private Map<String, Object> resultat(String decision) {
        Map<String, Object> r = new HashMap<>();
        r.put("eligibility", decision);
        r.put("eligibilityScore", 78);
        r.put("creditType", "CONSOMMATION");
        r.put("rawExplanation", "Explication de la décision.");
        return r;
    }

    private MimeMessage messageEnvoye() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    /** Parcourt le message : texte HTML, et pièces jointes (nom → octets). */
    private void parcourir(Object contenu, StringBuilder texte, Map<String, byte[]> pieces) throws Exception {
        if (contenu instanceof MimeMessage m) {
            parcourir(m.getContent(), texte, pieces);
        } else if (contenu instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart partie = mp.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(partie.getDisposition()) && partie.getFileName() != null) {
                    try (InputStream flux = partie.getInputStream()) {
                        pieces.put(partie.getFileName(), flux.readAllBytes());
                    }
                } else {
                    parcourir(partie.getContent(), texte, pieces);
                }
            }
        } else if (contenu instanceof String s) {
            texte.append(s);
        }
    }

    // ── Nom de la banque ─────────────────────────────────────────────────────

    @Test
    void le_sujet_dit_attijari_bank_pour_chaque_decision() throws Exception {
        for (String decision : new String[]{"ELIGIBLE", "REFUS", "CONDITIONNEL", "A_COMPLETER", "INDETERMINE"}) {
            reset(mailSender);
            when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));

            service.envoyer(dossier, resultat(decision));

            String sujet = messageEnvoye().getSubject();
            assertTrue(sujet.contains("Attijari Bank"), decision + " : " + sujet);
            assertFalse(sujet.contains("Attijariwafa"), decision + " : " + sujet);
        }
    }

    @Test
    void le_corps_de_l_e_mail_dit_attijari_bank_en_tete_et_en_pied() throws Exception {
        service.envoyer(dossier, resultat("ELIGIBLE"));

        StringBuilder texte = new StringBuilder();
        parcourir(messageEnvoye(), texte, new HashMap<>());

        assertTrue(texte.toString().contains("Attijari Bank"));
        assertTrue(texte.toString().contains("© 2026 Attijari Bank Tunisie — CrediSense"));
        assertFalse(texte.toString().contains("Attijariwafa"));
    }

    // ── Pièce jointe ─────────────────────────────────────────────────────────

    @Test
    void le_rapport_pdf_est_joint_avec_un_nom_lisible() throws Exception {
        ResultatEmailService.Envoi envoi = service.envoyer(dossier, resultat("ELIGIBLE"));

        Map<String, byte[]> pieces = new HashMap<>();
        parcourir(messageEnvoye(), new StringBuilder(), pieces);

        assertEquals(1, pieces.size());
        byte[] pdf = pieces.get("Reponse-credit-1A2B3C4D.pdf");
        assertNotNull(pdf, "pièces jointes : " + pieces.keySet());
        assertEquals("%PDF", new String(pdf, 0, 4));
        assertTrue(envoi.pdfJoint());
        assertEquals("client@example.com", envoi.destinataire());
        assertEquals("ELIGIBLE", envoi.decision());
    }

    @Test
    void si_le_pdf_ne_peut_pas_etre_genere_l_e_mail_part_quand_meme() throws Exception {
        RapportPdfService enPanne = mock(RapportPdfService.class);
        when(enPanne.generer(any(), any(), any())).thenThrow(new IllegalStateException("police introuvable"));
        ResultatEmailService sansPdf = new ResultatEmailService(mailSender, enPanne);
        ReflectionTestUtils.setField(sansPdf, "fromEmail", "banque@example.com");

        ResultatEmailService.Envoi envoi = sansPdf.envoyer(dossier, resultat("REFUS"));

        Map<String, byte[]> pieces = new HashMap<>();
        parcourir(messageEnvoye(), new StringBuilder(), pieces);
        assertTrue(pieces.isEmpty());
        assertFalse(envoi.pdfJoint());
        verify(mailSender).send(any(MimeMessage.class));          // la décision est partie
    }

    @Test
    void le_pdf_joint_est_la_version_client() throws Exception {
        RapportPdfService pdf = mock(RapportPdfService.class);
        when(pdf.generer(any(), any(), any())).thenReturn("%PDF-faux".getBytes());
        ResultatEmailService s = new ResultatEmailService(mailSender, pdf);
        ReflectionTestUtils.setField(s, "fromEmail", "banque@example.com");

        s.envoyer(dossier, resultat("ELIGIBLE"));

        verify(pdf).generer(eq(dossier), any(), eq(RapportPdfService.Version.CLIENT));
    }

    // ── Cas d'erreur ─────────────────────────────────────────────────────────

    @Test
    void sans_adresse_e_mail_rien_n_est_envoye() {
        dossier.getClient().setEmail("  ");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.envoyer(dossier, resultat("ELIGIBLE")));

        assertEquals("Email client introuvable", e.getMessage());
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void serveur_de_messagerie_en_panne_l_erreur_remonte() {
        doThrow(new RuntimeException("SMTP indisponible")).when(mailSender).send(any(MimeMessage.class));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.envoyer(dossier, resultat("ELIGIBLE")));

        assertEquals("SMTP indisponible", e.getMessage());
    }

    // ── Texte venu de l'IA ───────────────────────────────────────────────────

    @Test
    void le_html_venu_de_l_ia_ou_de_la_base_est_neutralise() throws Exception {
        dossier.getClient().setPrenom("<b>Meriem</b>");
        Map<String, Object> r = resultat("ELIGIBLE");
        r.put("rawExplanation", "Bien <script>alert(1)</script> & merci");

        service.envoyer(dossier, r);

        StringBuilder texte = new StringBuilder();
        parcourir(messageEnvoye(), texte, new HashMap<>());
        assertFalse(texte.toString().contains("<script>"));
        assertTrue(texte.toString().contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; merci"));
        assertFalse(texte.toString().contains("<b>Meriem</b>"));
    }

    @Test
    void les_apostrophes_et_accents_de_l_explication_sont_conserves() throws Exception {
        Map<String, Object> r = resultat("A_COMPLETER");
        r.put("rawExplanation", "Pour finaliser l'étude de votre dossier, il manque : relevé bancaire.");

        service.envoyer(dossier, r);

        StringBuilder texte = new StringBuilder();
        parcourir(messageEnvoye(), texte, new HashMap<>());
        assertTrue(texte.toString().contains("Pour finaliser l'étude de votre dossier, il manque : relevé bancaire."));
    }

    @Test
    void nom_de_la_piece_jointe_sans_identifiant() {
        assertEquals("Reponse-credit-dossier.pdf", ResultatEmailService.nomPiece(Dossier.builder().build()));
    }
}
