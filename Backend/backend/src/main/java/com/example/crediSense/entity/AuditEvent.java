package com.example.crediSense.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Une ligne du journal d'audit : qui a fait quoi sur un dossier, quand, et avec quel résultat.
 *
 * Le journal est en AJOUT SEUL : l'entité est « immuable » (Hibernate ignore toute modification) et
 * aucun endpoint ne permet de modifier ou de supprimer une ligne. Le dossier est référencé par son
 * identifiant seul (pas de clé étrangère) : le journal survit à la suppression d'un dossier.
 */
@Entity
@Table(name = "audit_events")
@Immutable
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "dossier_id", nullable = false)
    private UUID dossierId;

    /** ANALYSE, EMAIL_ENVOYE, EMAIL_ECHEC, EMAIL_EN_ATTENTE, EMAIL_VALIDE, EMAIL_ANNULE… */
    @Column(nullable = false)
    private String type;

    /** E-mail de l'agent connecté, ou SYSTEME (envoi différé, tâche automatique). */
    @Column(nullable = false)
    private String acteur;

    private String decision;
    private Double score;

    /** Version des règles de calcul avec lesquelles la décision a été rendue. */
    private String versionRegles;

    /** Détails de l'événement (destinataire, mode d'envoi, erreur, fournisseur d'IA…), en JSON. */
    @Column(columnDefinition = "text")
    private String detail;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
