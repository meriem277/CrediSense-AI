import { Component, HostListener, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { ClientAuthService } from '../../../../services/Externe/Client-auth.service';
import { environment } from '../../../../../environments/environment';

export type FiltreStatut = 'tous' | 'etude' | 'approuve' | 'refuse';
export type TriDate = 'recent' | 'ancien';

/** Les trois étapes vues par le client : sa demande est reçue, étudiée, puis tranchée. */
export const ETAPES_DEMANDE = ['Reçue', 'En étude', 'Décision'] as const;

const TYPES_DOCUMENT: Record<string, string> = {
  CIN: "Carte d'identité nationale",
  FICHE_PAIE: 'Fiche de paie',
  RELEVE_BANCAIRE: 'Relevé bancaire',
  ATTESTATION_EMPLOI: "Attestation d'emploi",
  JUSTIFICATIF_DOMICILE: 'Justificatif de domicile',
  AUTRE: 'Autre document',
};

@Component({
  selector: 'app-historique',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './historique.html',
  styleUrl: './historique.scss'
})
export class Historique implements OnInit {

  readonly etapes = ETAPES_DEMANDE;
  readonly filtres: { id: FiltreStatut; nom: string }[] = [
    { id: 'tous', nom: 'Toutes' }, { id: 'etude', nom: 'En étude' },
    { id: 'approuve', nom: 'Approuvées' }, { id: 'refuse', nom: 'Refusées' },
  ];

  dossiers: any[] = [];
  loading = true;
  errorMsg = '';
  clientNom = '';
  clientInitiales = '';
  selectedDossier: any = null;
  fichiers: any[] = [];
  loadingFichiers = false;

  // Filtres de la liste
  filtre: FiltreStatut = 'tous';
  recherche = '';
  tri: TriDate = 'recent';

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
      `${environment.apiUrl}/api/clients/historique?email=${encodeURIComponent(email)}`
    ).subscribe({
      next: (data) => {
        this.dossiers = data ?? [];
        this.loading = false;
      },
      error: () => {
        this.errorMsg = 'Erreur lors du chargement de l\'historique.';
        this.loading = false;
      }
    });
  }

  // ── Compteurs ─────────────────────────────────────────────────────────────
  get totalDossiers(): number { return this.dossiers.length; }

  /** Une demande « en étude » est reçue mais pas encore tranchée : en attente ou en cours. */
  get enEtude(): number {
    return this.dossiers.filter(d => d.statut === 'EN_ATTENTE' || d.statut === 'EN_COURS').length;
  }
  get enAttente(): number { return this.dossiers.filter(d => d.statut === 'EN_ATTENTE').length; }
  get enCours(): number   { return this.dossiers.filter(d => d.statut === 'EN_COURS').length; }
  get approuves(): number { return this.dossiers.filter(d => d.statut === 'APPROUVE').length; }
  get refuses(): number   { return this.dossiers.filter(d => d.statut === 'REFUSE').length; }

  compteurFiltre(filtre: FiltreStatut): number {
    return { tous: this.totalDossiers, etude: this.enEtude, approuve: this.approuves, refuse: this.refuses }[filtre];
  }

  // ── Liste filtrée, recherchée et triée ───────────────────────────────────
  private correspondAuFiltre(d: any): boolean {
    switch (this.filtre) {
      case 'etude':    return d.statut === 'EN_ATTENTE' || d.statut === 'EN_COURS';
      case 'approuve': return d.statut === 'APPROUVE';
      case 'refuse':   return d.statut === 'REFUSE';
      default:         return true;
    }
  }

  get dossiersAffiches(): any[] {
    const terme = this.recherche.trim().toLowerCase().replace(/^n°\s*/, '');
    return this.dossiers
      .filter(d => this.correspondAuFiltre(d))
      .filter(d => !terme || String(d.dossierId ?? '').toLowerCase().includes(terme))
      .sort((a, b) => {
        const ecart = this.horodatage(a.createdAt) - this.horodatage(b.createdAt);
        return this.tri === 'recent' ? -ecart : ecart;
      });
  }

  /** Le dossier le plus récent, mis en avant en haut de la page. */
  get dernierDossier(): any | null {
    if (this.dossiers.length === 0) return null;
    return [...this.dossiers].sort((a, b) => this.horodatage(b.createdAt) - this.horodatage(a.createdAt))[0];
  }

  choisirFiltre(filtre: FiltreStatut): void { this.filtre = filtre; }

  reinitialiserFiltres(): void {
    this.filtre = 'tous';
    this.recherche = '';
  }

  // ── Présentation d'un dossier ────────────────────────────────────────────
  libelleType(type: string | null | undefined): string {
    const t = (type ?? '').trim().toUpperCase();
    if (!t) return 'Demande de crédit';
    if (t === 'CONSOMMATION') return 'Crédit à la consommation';
    if (t === 'IMMOBILIER') return 'Crédit immobilier';
    return `Crédit ${t.charAt(0)}${t.slice(1).toLowerCase()}`;
  }

  /** Référence courte et lisible : les huit premiers caractères de l'identifiant. */
  reference(dossier: any): string {
    return String(dossier?.dossierId ?? '').slice(0, 8).toUpperCase();
  }

  private horodatage(raw: string): number {
    const t = new Date(raw).getTime();
    return Number.isFinite(t) ? t : 0;
  }

  private dateValide(raw: string): Date | null {
    if (!raw) return null;
    const d = new Date(raw);
    return Number.isNaN(d.getTime()) ? null : d;
  }

  /** « 6 octobre 2026 » */
  formatDateLongue(raw: string): string {
    const d = this.dateValide(raw);
    return d ? d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' }) : '';
  }

  /** « 20:34 » */
  formatHeure(raw: string): string {
    const d = this.dateValide(raw);
    return d ? d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }) : '';
  }

  /** « 6 octobre 2026 à 20:34 » : jamais les microsecondes de la base. */
  formatDate(raw: string): string {
    const jour = this.formatDateLongue(raw);
    const heure = this.formatHeure(raw);
    return jour && heure ? `${jour} à ${heure}` : jour;
  }

  // ── Statut : classe, libellé, étape et phrase d'explication ──────────────
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
      case 'EN_ATTENTE': return 'Reçue';
      case 'EN_COURS':   return 'En étude';
      case 'APPROUVE':   return 'Approuvée';
      case 'REFUSE':     return 'Refusée';
      default:           return statut;
    }
  }

  /** Étape atteinte (1 à 3) ; 0 si le statut est inconnu. */
  etapeActive(statut: string): number {
    switch (statut) {
      case 'EN_ATTENTE': return 1;
      case 'EN_COURS':   return 2;
      case 'APPROUVE':
      case 'REFUSE':     return 3;
      default:           return 0;
    }
  }

  messageStatut(statut: string): string {
    switch (statut) {
      case 'EN_ATTENTE': return 'Nous avons bien reçu votre demande. Elle sera étudiée par un conseiller.';
      case 'EN_COURS':   return 'Un conseiller étudie votre dossier. Vous serez informé par e-mail de la décision.';
      case 'APPROUVE':   return 'Votre demande a été approuvée. Le détail de la réponse vous est communiqué par e-mail.';
      case 'REFUSE':     return 'Votre demande n\'a pas pu être acceptée. Le détail de la réponse vous est communiqué par e-mail.';
      default:           return '';
    }
  }

  // ── Documents d'un dossier ───────────────────────────────────────────────
  libelleDocument(fichier: any): string {
    const type = String(fichier?.typeDocument ?? '').toUpperCase();
    return TYPES_DOCUMENT[type] ?? fichier?.nomFichier ?? 'Document';
  }

  getFileUrl(fichierId: string): string {
    return `${environment.apiUrl}/api/public/fichiers/${fichierId}/download`;
  }

  voirDetails(dossier: any): void {
    this.selectedDossier = dossier;
    this.loadingFichiers = true;
    this.fichiers = [];

    this.http.get<any[]>(
      `${environment.apiUrl}/api/public/demande/${dossier.dossierId}/fichiers`
    ).subscribe({
      next: (data) => { this.fichiers = data ?? []; this.loadingFichiers = false; },
      error: () => { this.loadingFichiers = false; }
    });
  }

  @HostListener('document:keydown.escape')
  fermerDetails(): void {
    this.selectedDossier = null;
    this.fichiers = [];
  }

  nouvelleDemande() {
    this.router.navigate(['/client/demande']);
  }

  logout() {
    this.clientAuth.logout();
  }
}
