package com.example.crediSense.entity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "rag_contexts")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagContext {
       @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String cin;

    @Column(columnDefinition = "text")
    private String contexte;

    private String source;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @ManyToOne
    @JoinColumn(name = "dossier_id")
    @ToString.Exclude
    private Dossier dossier;
    
}
