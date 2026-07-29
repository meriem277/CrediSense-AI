package com.example.crediSense.entity;

import jakarta.persistence.*;

import java.time.*;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import lombok.Data;

import lombok.*;

@Entity
@Table(name = "clients")
@Data
@NoArgsConstructor
@AllArgsConstructor

public class Client { 
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String cin;
    private String nom;
    private String prenom;
    @Column(unique = true)
    private String email;
    private String password;
    private String googleId;
    private String photoUrl;
    private String provider;
    @CreationTimestamp
    private LocalDateTime createdAt;
    public String getPrenom() {
        return prenom;
    }

    public void setPrenom(String prenom) {
        this.prenom = prenom;
    }
    public String getCin() {
        return cin;
    }

    public void setCin(String cin) {
        this.cin = cin;
    }


    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL)
    private List<Dossier> dossiers;

    @OneToOne(mappedBy = "client", cascade = CascadeType.ALL)
    private ClientAggregation clientAggregation;
   

  
}