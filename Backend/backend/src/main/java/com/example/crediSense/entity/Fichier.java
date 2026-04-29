package com.example.crediSense.entity;

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
}
    

