package com.example.crediSense.entity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "json_extractions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JsonExtraction {
     @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String cin;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String jsonData;

    private Double confidenceScore;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @ManyToOne
    @JoinColumn(name = "fichier_id")
    @ToString.Exclude
    private Fichier fichier;
    
}
