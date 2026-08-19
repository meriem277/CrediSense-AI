import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { ClientAuthService } from '../../../../services/Externe/Client-auth.service';
import { environment } from '../../../../../environments/environment';

@Component({
  selector: 'app-historique',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './historique.html',
  styleUrl: './historique.scss'
})
export class Historique implements OnInit {

  dossiers: any[] = [];
  loading = true;
  errorMsg = '';
  clientNom = '';
  clientInitiales = '';
  selectedDossier: any = null;
fichiers: any[] = [];
loadingFichiers = false;
sortDateAsc = false; // false = décroissant par défaut


  constructor(
    private http: HttpClient,
    private clientAuth: ClientAuthService,
    private router: Router
  ) {}

  ngOnInit() {
    if (!this.clientAuth.isLoggedIn()) {
      this.router.navigate(['/client/login']);
      return;
    }
    const user = this.clientAuth.getUser();
    this.clientNom = `${user?.prenom || ''} ${user?.nom || ''}`.trim();
    this.clientInitiales = [user?.prenom?.[0], user?.nom?.[0]]
      .filter(Boolean).join('').toUpperCase();
    this.loadHistorique(user?.email || '');
  }

  loadHistorique(email: string) {
    this.loading = true;
    this.http.get<any[]>(
      `${environment.apiUrl}/api/clients/historique?email=${email}`
    ).subscribe({
      next: (data) => {
        this.dossiers = data;
        this.loading = false;
      },
      error: () => {
        this.errorMsg = 'Erreur lors du chargement de l\'historique.';
        this.loading = false;
      }
    });
  }

  get totalDossiers(): number {
    return this.dossiers.length;
  }

  get enAttente(): number {
    return this.dossiers.filter(d => d.statut === 'EN_ATTENTE').length;
  }

  get enCours(): number {
    return this.dossiers.filter(d => d.statut === 'EN_COURS').length;
  }

  get approuves(): number {
    return this.dossiers.filter(d => d.statut === 'APPROUVE').length;
  }

  get refuses(): number {
    return this.dossiers.filter(d => d.statut === 'REFUSE').length;
  }
getFileUrl(fichierId: string): string {
  return `${environment.apiUrl}/api/public/fichiers/${fichierId}/download`;
}
  getStatutClass(statut: string): string {
    switch (statut) {
      case 'EN_ATTENTE': return 'statut-attente';
      case 'EN_COURS':   return 'statut-cours';
      case 'APPROUVE':   return 'statut-approuve';
      case 'REFUSE':     return 'statut-refuse';
      default:           return '';
    }
  }

  getStatutLabel(statut: string): string {
    switch (statut) {
      case 'EN_ATTENTE': return 'En attente';
      case 'EN_COURS':   return 'En cours';
      case 'APPROUVE':   return 'Approuvé';
      case 'REFUSE':     return 'Refusé';
      default:           return statut;
    }
  }

  nouvelleDemande() {
    this.router.navigate(['/client/demande']);
  }

  logout() {
    this.clientAuth.logout();
  }

voirDetails(dossier: any): void {
  this.selectedDossier = dossier;
  this.loadingFichiers = true;
  this.fichiers = [];

  this.http.get<any[]>(
`${environment.apiUrl}/api/public/demande/${dossier.dossierId}/fichiers`  ).subscribe({
    next: (data) => { this.fichiers = data; this.loadingFichiers = false; },
    error: () => { this.loadingFichiers = false; }
  });
}

fermerDetails(): void {
  this.selectedDossier = null;
  this.fichiers = [];
}
toggleSortDate(): void {
  this.sortDateAsc = !this.sortDateAsc;
  this.dossiers.sort((a, b) => {
    const diff = new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
    return this.sortDateAsc ? diff : -diff;
  });
}

formatDate(raw: string): string {
  if (!raw) return '';
  return raw.replace('T', ' ').replace('Z', '');
}


}
