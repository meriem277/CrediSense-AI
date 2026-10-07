package com.example.crediSense.entity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ocr_results")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OcrResult {
       @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(columnDefinition = "text")
    private String texteBrut;

    @Column(columnDefinition = "text")
    private String texteNettoye;

    private String statut;

    // Raison de l'échec quand statut = "FAILED" (format non supporté, image illisible, OCR indisponible…)
    @Column(columnDefinition = "text")
    private String erreur;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @OneToOne
    @JoinColumn(name = "fichier_id")
    @ToString.Exclude
    private Fichier fichier;
}
    
