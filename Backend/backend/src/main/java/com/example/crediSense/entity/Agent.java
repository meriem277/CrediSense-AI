package com.example.crediSense.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "agents")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Agent {
        @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String nom;
    private String email;
   @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoleType role;
   private String password;


    @CreationTimestamp
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "agent", cascade = CascadeType.ALL)
    private List<Fichier> fichiers;

    
}
