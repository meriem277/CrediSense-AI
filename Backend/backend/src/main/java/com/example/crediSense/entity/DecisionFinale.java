package com.example.crediSense.entity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "decisions_finales")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DecisionFinale {
       @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private Double scoreFinal;
    private String decisionFinale;

    @Column(columnDefinition = "text")
    private String justificationGlobale;

    @Column(columnDefinition = "text")
    private String explicationClient;

    @Column(columnDefinition = "text")
    private String piecesManquantes;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id")
    @JsonIgnoreProperties({"agentAnalysis", "decisionFinale", "fichiers", "ragContexts", "client"})

    @ToString.Exclude
    private Dossier dossier;

    @ManyToOne
    @JoinColumn(name = "client_aggregation_id")
    @ToString.Exclude
    private ClientAggregation clientAggregation;
}
    

