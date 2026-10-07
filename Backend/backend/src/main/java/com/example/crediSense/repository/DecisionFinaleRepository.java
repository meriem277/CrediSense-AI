package com.example.crediSense.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.crediSense.entity.DecisionFinale;

public interface DecisionFinaleRepository extends JpaRepository<DecisionFinale, UUID> {
    Optional<DecisionFinale> findByDossierId(UUID dossierId);

    /** Décisions rendues dans [du, au[ (date de la dernière décision), avec leur dossier et leur client. */
    @Query("select d from DecisionFinale d join fetch d.dossier dos left join fetch dos.client "
         + "where coalesce(d.decisionLe, d.createdAt) >= :du and coalesce(d.decisionLe, d.createdAt) < :au")
    List<DecisionFinale> findDecisionsEntre(@Param("du") LocalDateTime du, @Param("au") LocalDateTime au);

    /** Réponses dont l'envoi différé est arrivé à échéance. */
    @Query("select d from DecisionFinale d join fetch d.dossier dos left join fetch dos.client "
         + "where d.emailStatut = :statut and d.emailProgrammeA <= :maintenant")
    List<DecisionFinale> findProgrammesAEnvoyer(@Param("statut") String statut,
                                                @Param("maintenant") LocalDateTime maintenant);

    long countByEmailStatut(String emailStatut);
}
