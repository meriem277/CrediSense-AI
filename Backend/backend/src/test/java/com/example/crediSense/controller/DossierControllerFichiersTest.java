package com.example.crediSense.controller;

import com.example.crediSense.Service.DossierService;
import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.entity.Fichier;
import com.example.crediSense.repository.AgentRepository;
import com.example.crediSense.repository.DecisionFinaleRepository;
import com.example.crediSense.repository.DossierRepository;
import com.example.crediSense.repository.JsonExtractionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Ce que l'écran de l'agent reçoit pour chaque document : le détail de la classification
 * (méthode, mots-clés, trace de la cascade) doit être transmis tel que le service IA l'a produit.
 */
class DossierControllerFichiersTest {

    private final UUID dossierId = UUID.randomUUID();
    private DossierController controller;
    private Dossier dossier;

    @BeforeEach
    void preparer() {
        DossierRepository dossierRepository = mock(DossierRepository.class);
        Client client = new Client();
        client.setCin("12015060");
        dossier = Dossier.builder().id(dossierId).client(client).build();
        when(dossierRepository.findById(dossierId)).thenReturn(Optional.of(dossier));

        controller = new DossierController(
                mock(DossierService.class), dossierRepository, mock(AgentRepository.class),
                mock(DecisionFinaleRepository.class), mock(JsonExtractionRepository.class),
                mock(JavaMailSender.class));
    }

    private Fichier fichier(String classificationJson) {
        return Fichier.builder()
                .id(UUID.randomUUID())
                .nomOriginal("FICHE_PAIE.pdf")
                .typeDocument("FICHE_PAIE")
                .typeDetecte("RELEVE_BANCAIRE")
                .confianceType(0.9)
                .methodeType("regles")
                .typeConflit(true)
                .classificationJson(classificationJson)
                .dossier(dossier)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> premierFichier(Fichier f) {
        dossier.setFichiers(List.of(f));
        ResponseEntity<List<Map<String, Object>>> reponse = controller.getFichiersByDossier(dossierId);
        return reponse.getBody().get(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void le_detail_de_la_classification_est_transmis_avec_sa_trace() {
        String json = "{\"type_document\":\"RELEVE_BANCAIRE\",\"methode\":\"regles\","
                + "\"mots_cles\":[\"releve de compte\"],"
                + "\"trace\":[{\"niveau\":\"regles\",\"decisif\":true,\"score\":20}],"
                + "\"controle\":{\"concordant\":false,\"typeRetenu\":\"RELEVE_BANCAIRE\"}}";

        Map<String, Object> fichier = premierFichier(fichier(json));

        Map<String, Object> classification = (Map<String, Object>) fichier.get("classification");
        assertEquals("regles", classification.get("methode"));
        assertEquals(List.of("releve de compte"), classification.get("mots_cles"));
        List<Map<String, Object>> trace = (List<Map<String, Object>>) classification.get("trace");
        assertEquals("regles", trace.get(0).get("niveau"));
        assertEquals(Boolean.TRUE, trace.get(0).get("decisif"));
        // les champs résumés sont toujours là
        assertEquals("RELEVE_BANCAIRE", fichier.get("typeDetecte"));
        assertEquals(Boolean.TRUE, fichier.get("typeConflit"));
    }

    @Test
    void document_pas_encore_classe_classification_nulle() {
        Map<String, Object> fichier = premierFichier(fichier(null));

        assertTrue(fichier.containsKey("classification"));
        assertNull(fichier.get("classification"));
    }

    @Test
    void detail_illisible_ne_fait_pas_echouer_la_liste() {
        Map<String, Object> fichier = premierFichier(fichier("{pas du json"));

        assertNull(fichier.get("classification"));
        assertEquals("FICHE_PAIE.pdf", fichier.get("nomOriginal"));   // le reste du document est servi
    }
}
