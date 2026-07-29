// src/app/components/client-portal/confirmation/confirmation.ts
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';

@Component({
  selector: 'app-confirmation',
  imports: [CommonModule],
  templateUrl: './confirmation.html',
  styleUrl: './confirmation.scss',
})
export class Confirmation  implements OnInit {

  client: any = {};
  dossierId = '';

  constructor(private router: Router) {}

  ngOnInit(): void {
    this.client = JSON.parse(localStorage.getItem('client_user') || '{}');
    this.dossierId = localStorage.getItem('dossierId') || '';  // ✅ ajouté
  }



  retourAccueil(): void {
      localStorage.removeItem('dossierId');
    this.router.navigate(['/client/login']);
  }

  nouvelleDemandeOuSuivi(): void {
        localStorage.removeItem('dossierId');
    this.router.navigate(['/client/demande']);
  }}
