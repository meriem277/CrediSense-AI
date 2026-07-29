package com.example.crediSense.controller.Client;

import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.entity.Fichier;
import com.example.crediSense.repository.ClientRepository;
import com.example.crediSense.repository.DossierRepository;
import com.example.crediSense.repository.FichierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/public")
@CrossOrigin(origins = "http://localhost:4200")
@RequiredArgsConstructor
public class DemandeCreditController {

    private final ClientRepository  clientRepository;
    private final DossierRepository dossierRepository;
    private final FichierRepository fichierRepository;

    @Value("${upload.base-path:uploads}")
    private String basePath;

    // ─── Étape 1 : Créer client + dossier ────────────────────────────────────

    @PostMapping("/demande")
    public ResponseEntity<Map<String, Object>> creerDemande(
            @RequestBody DemandeRequest request) {

        log.info("Nouvelle demande crédit — CIN={}", request.cin());

        // ✅ Récupère le client connecté par email — ne crée jamais un nouveau
        Client client = clientRepository.findByEmail(request.clientEmail())
                .orElseThrow(() -> new RuntimeException("Client introuvable — veuillez vous reconnecter"));

        // ✅ Met à jour le CIN si pas encore renseigné
        if (client.getCin() == null || client.getCin().isEmpty()) {
            client.setCin(request.cin());
            clientRepository.save(client);
        }

        // ✅ Crée uniquement le dossier lié au client existant
        Dossier dossier = Dossier.builder()
                .typeCredit("CONSOMMATION")
                .statut("EN_ATTENTE")
                .client(client)
                .build();

        Dossier saved = dossierRepository.save(dossier);
        log.info("Dossier créé — id={}", saved.getId());

        return ResponseEntity.ok(Map.of(
                "clientId",  client.getId().toString(),
                "dossierId", saved.getId().toString(),
                "statut",    "EN_ATTENTE",
                "message",   "Dossier créé avec succès"
        ));
    }
    // ─── Étape 2 : Upload simple — stockage uniquement ───────────────────────
    // ⚠️ Pas d'ETL, pas de NLP, pas de GROQ
    // L'agent lancera l'analyse depuis son dashboard

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @RequestParam("file")         MultipartFile file,
            @RequestParam("cin")          String cin,
            @RequestParam("dossierId")    String dossierId,
            @RequestParam("typeDocument") String typeDocument) {

        log.info("Upload client — cin={}, type={}", cin, typeDocument);

        try {
            String nomOriginal = file.getOriginalFilename();
            String extension   = getExtension(nomOriginal);

            // Créer dossier de stockage
            Path dossierPath = Paths.get(basePath, "client-uploads", cin);
            Files.createDirectories(dossierPath);

            // Sauvegarder le fichier original
            String fileId    = UUID.randomUUID().toString();
            Path   cheminFichier = dossierPath.resolve(fileId + "." + extension);
            Files.copy(file.getInputStream(), cheminFichier, StandardCopyOption.REPLACE_EXISTING);

            // Récupérer le dossier
            Dossier dossier = dossierRepository.findById(UUID.fromString(dossierId))
                    .orElseThrow(() -> new RuntimeException("Dossier introuvable"));

            // Sauvegarder l'entité Fichier en base — sans ETL
            Fichier fichier = Fichier.builder()
                    .cin(cin)
                    .nomOriginal(nomOriginal)
                    .typeOriginal(extension)
                    .typeDocument(typeDocument)    // type NLP saisi par le client
                    .cheminPdf(cheminFichier.toString())
                    .dossier(dossier)
                    .build();

            fichierRepository.save(fichier);

            log.info("Fichier sauvegardé — id={}, type={}", fichier.getId(), typeDocument);

            return ResponseEntity.ok(Map.of(
                    "statut",    "SUCCESS",
                    "fichierId", fichier.getId().toString(),
                    "type",      typeDocument,
                    "message",   "Document sauvegardé — l'analyse sera effectuée par l'agent"
            ));

        } catch (Exception e) {
            log.error("Erreur upload : {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "statut",  "ERREUR",
                    "message", e.getMessage()
            ));
        }
    }

    // ─── Étape 3 : Soumettre le dossier ──────────────────────────────────────

    @PostMapping("/soumettre/{dossierId}")
    public ResponseEntity<Map<String, Object>> soumettreDossier(
            @PathVariable UUID dossierId) {

        log.info("Soumission dossier — id={}", dossierId);

        Dossier dossier = dossierRepository.findById(dossierId)
                .orElseThrow(() -> new RuntimeException("Dossier introuvable"));

        dossier.setStatut("EN_ATTENTE");
        dossierRepository.save(dossier);

        return ResponseEntity.ok(Map.of(
                "statut",    "EN_ATTENTE",
                "dossierId", dossierId.toString(),
                "message",   "Dossier soumis. Un agent vous contactera sous 48h."
        ));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "pdf";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    // ─── Record ───────────────────────────────────────────────────────────────

    public record DemandeRequest(
            String cin,
            String nom,
            String prenom,
            String dateNaissance,
            String telephone,
            String adresse,
            String typeContrat,
            String nationalite,
            String montantCredit,
            String dureeCredit,
            String clientEmail
    ) {}
}
