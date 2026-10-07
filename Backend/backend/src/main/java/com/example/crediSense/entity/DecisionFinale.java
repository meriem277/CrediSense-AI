package com.example.crediSense.entity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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

    // ✅ Nouveau — réponse JSON complète renvoyée par l'agent Python
    // (/ai/score/consommation) : financialMetrics, risks, recommendedPlan,
    // documentSources, etc. Permet de réafficher le résultat riche sans
    // relancer une analyse quand l'agent revient consulter un dossier déjà
    // tranché.
    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String resultatComplet;

    @CreationTimestamp
    private LocalDateTime createdAt;

    // Date de la DERNIÈRE décision rendue. createdAt est la première analyse ; une nouvelle analyse
    // met à jour cette même ligne. (Pas de @UpdateTimestamp : il bougerait aussi à chaque changement
    // d'état de l'e-mail et fausserait les délais de traitement.) Null sur les anciennes lignes.
    private LocalDateTime decisionLe;

    // ── Réponse envoyée au client (automatiquement dès la décision, ou par le bouton de l'agent) ──
    // ENVOYE | ECHEC | NON_ENVOYE | EN_ATTENTE_VALIDATION | PROGRAMME | ANNULE (null : rien tenté)
    private String        emailStatut;
    private LocalDateTime emailEnvoyeAt;
    private String        emailDecision;      // décision contenue dans le dernier e-mail envoyé
    private String        emailDestinataire;
    @Column(length = 500)
    private String        emailErreur;
    private LocalDateTime emailProgrammeA;    // envoi différé : heure prévue (statut PROGRAMME)
    private String        emailMode;          // AUTO | DELAI | VALIDATION | MANUEL
    private String        emailActeur;        // agent qui a validé, annulé ou envoyé (SYSTEME : automatique)

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