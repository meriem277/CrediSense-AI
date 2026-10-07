package com.example.crediSense.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "fichiers")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Fichier {
        @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String cin;
    private String nomOriginal;
    private String typeOriginal;
    private String typeDocument;

    // Contrôle du type : ce que la classification (embeddings, puis LLM) a trouvé, comparé
    // au type déclaré par le client. typeConflit : true = désaccord fiable, false = accord,
    // null = pas de conclusion (verdict peu fiable ou type non déclaré).
    private String  typeDetecte;
    private Double  confianceType;
    private String  methodeType;
    private Boolean typeConflit;

    private String cheminPdf;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @ManyToOne
    @JoinColumn(name = "agent_id")
    @ToString.Exclude
    private Agent agent;

    @OneToOne(mappedBy = "fichier", cascade = CascadeType.ALL)
    @ToString.Exclude
    private OcrResult ocrResult;

    @OneToMany(mappedBy = "fichier", cascade = CascadeType.ALL)
    @ToString.Exclude
    private List<JsonExtraction> jsonExtractions;

    @ManyToOne
    @JoinColumn(name = "dossier_id")
    @ToString.Exclude
    @JsonIgnoreProperties({"fichiers"})
    private Dossier dossier;
}
    

