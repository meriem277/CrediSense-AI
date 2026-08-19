package com.example.crediSense.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "dossiers")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Dossier {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private Double  montantCredit;
    private Integer dureeCredit;
    private String typeCredit;
    private String  typeContrat;
    private String statut;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @ManyToOne
    @JoinColumn(name = "client_id")
    @ToString.Exclude
    private Client client;

    @OneToOne(mappedBy = "dossier", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnoreProperties({"dossier"})
    @ToString.Exclude
    private AgentAnalysis agentAnalysis;

    @OneToOne(mappedBy = "dossier", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnoreProperties({"dossier"})
    @ToString.Exclude
    private DecisionFinale decisionFinale;

    @OneToMany(mappedBy = "dossier", cascade = CascadeType.ALL)
    @ToString.Exclude
    private List<RagContext> ragContexts;

    @OneToMany(mappedBy = "dossier", cascade = CascadeType.ALL)
    @ToString.Exclude
    private List<Fichier> fichiers;

    @ManyToOne
    @JoinColumn(name = "agent_traitant_id")
    @ToString.Exclude
    private Agent agentTraitant;
}