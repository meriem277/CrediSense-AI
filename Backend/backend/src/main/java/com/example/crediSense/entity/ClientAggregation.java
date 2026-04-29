package com.example.crediSense.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "client_aggregations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientAggregation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String cin;

    @Column(columnDefinition = "jsonb")
    private String aggregatedJson;

    private Integer nbFichiers;
    private LocalDateTime lastUpdated;

    @OneToOne
    @JoinColumn(name = "client_id")
    @ToString.Exclude
    private Client client;

    @OneToMany(mappedBy = "clientAggregation")
    @ToString.Exclude
    private List<DecisionFinale> decisions;
}