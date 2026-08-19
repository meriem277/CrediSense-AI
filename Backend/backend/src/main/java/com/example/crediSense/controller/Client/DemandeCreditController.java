package com.example.crediSense.controller.Client;

import com.example.crediSense.dto.request.DemandeRequest;
import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.entity.Fichier;
import com.example.crediSense.repository.ClientRepository;
import com.example.crediSense.repository.DossierRepository;
import com.example.crediSense.repository.FichierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
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

    // ─── Étape 1 : Création du dossier ───────────────────────────────────────
    @PostMapping("/demande")
    public ResponseEntity<Map<String, Object>> creerDemande(
            @RequestBody DemandeRequest request) {

        log.info("Nouvelle demande crédit — CIN={}", request.cin());

        Client client = clientRepository.findByEmail(request.clientEmail())
                .orElseThrow(() -> new RuntimeException(
                        "Client introuvable — veuillez vous reconnecter"));

        if (client.getCin() == null || client.getCin().isEmpty()) {
            client.setCin(request.cin());
            clientRepository.save(client);
        }

        Dossier dossier = Dossier.builder()
                .typeCredit("CONSOMMATION")
                .statut("EN_ATTENTE")
                .montantCredit(request.montantCredit())
                .dureeCredit(request.dureeCredit())
                .typeContrat(request.typeContrat())
                .client(client)
                .build();

        Dossier saved = dossierRepository.save(dossier);
        log.info("Dossier créé — id={}", saved.getId());

        return ResponseEntity.ok(Map.of(
                "clientId",  client.getId().toString(),
                "dossierId", saved.getId().toString(),
                "statut",    "EN_ATTENTE",
                "createdAt", saved.getCreatedAt().toString(),
                "message",   "Dossier créé avec succès"
        ));
    }

    // ─── Étape 2 : Upload fichiers client ─────────────────────────────────────
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadFichierClient(
            @RequestParam("file")       MultipartFile file,
            @RequestParam("cin")        String cin,
            @RequestParam("dossierId")  UUID dossierId,
            @RequestParam(value = "typeDocument", required = false,
                    defaultValue = "AUTRE") String typeDocument) {
        try {
            Dossier dossier = dossierRepository.findById(dossierId)
                    .orElseThrow(() -> new RuntimeException("Dossier introuvable"));

            String uploadDir = "uploads/client-uploads/" + cin;
            Files.createDirectories(Paths.get(uploadDir));

            String fileName  = UUID.randomUUID() + "_" + file.getOriginalFilename();
            java.nio.file.Path savedPath = Paths.get(uploadDir, fileName);
            Files.copy(file.getInputStream(), savedPath,
                    StandardCopyOption.REPLACE_EXISTING);

            Fichier fichier = Fichier.builder()
                    .cin(cin)
                    .nomOriginal(file.getOriginalFilename())
                    .typeOriginal(file.getContentType())
                    .typeDocument(typeDocument)
                    .cheminPdf(savedPath.toString())
                    .dossier(dossier)
                    .build();

            fichierRepository.save(fichier);

            log.info("Fichier client uploadé — {}", file.getOriginalFilename());

            return ResponseEntity.ok(Map.of(
                    "message",      "Fichier uploadé avec succès",
                    "nomOriginal",  file.getOriginalFilename(),
                    "typeDocument", typeDocument,
                    "statut",       "SUCCESS"
            ));

        } catch (Exception e) {
            log.error("Erreur upload client: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Étape 3 : Liste fichiers d'un dossier ───────────────────────────────
    @GetMapping("/demande/{dossierId}/fichiers")
    public ResponseEntity<?> getFichiersDossier(@PathVariable String dossierId) {
        List<Map<String, Object>> result = fichierRepository
                .findByDossierId(UUID.fromString(dossierId))
                .stream()
                .map(f -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("id", f.getId().toString());
                    m.put("nomOriginal", f.getNomOriginal());
                    m.put("typeDocument", f.getTypeDocument());
                    return m;
                })
                .collect(java.util.stream.Collectors.toList());

        return ResponseEntity.ok(result);
    }
    @GetMapping("/fichiers/{fichierId}/download")
    public ResponseEntity<org.springframework.core.io.Resource> downloadFichier(
            @PathVariable UUID fichierId) throws java.io.IOException {

        Fichier fichier = fichierRepository.findById(fichierId)
                .orElseThrow(() -> new RuntimeException("Fichier introuvable"));

        java.nio.file.Path path = Paths.get(fichier.getCheminPdf());
        org.springframework.core.io.Resource resource =
                new org.springframework.core.io.UrlResource(path.toUri());

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "inline; filename=\"" + fichier.getNomOriginal() + "\"")
                .body(resource);
    }
}