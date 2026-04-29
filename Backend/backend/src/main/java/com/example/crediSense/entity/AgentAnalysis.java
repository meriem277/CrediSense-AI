package com.example.crediSense.entity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent_analyses")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentAnalysis {
        @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String typeAgent;
    private Double score;
    private Double solvabilite;
    private Double revenus;
    private Double endettement;
    private Double historique;
    private String decision;

    @Column(columnDefinition = "text")
    private String justification;

    @Column(columnDefinition = "text")
    private String piecesManquantes;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @OneToOne
    @JoinColumn(name = "dossier_id")
    @ToString.Exclude
    private Dossier dossier;
}
    

