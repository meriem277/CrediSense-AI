import { Component, HostListener, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { ClientAuthService } from '../../../../services/Externe/Client-auth.service';
import { environment } from '../../../../../environments/environment';
import { OffreProposee, PropositionClient, ResumeProposition } from '../../../../models/proposition-client.model';

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

  // Propositions du conseiller (dossier conditionnel) : état par demande, puis détail de celle qui est ouverte
  propositions: Record<string, ResumeProposition['etat']> = {};
  proposition: PropositionClient | null = null;
  loadingProposition = false;
  offreSelectionnee: number | null = null;
  confirmation: 'ACCEPTER' | 'REFUSER' | null = null;
  envoiReponse = false;
  erreurReponse = '';

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
        this.chargerPropositions();
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

  // ── Propositions du conseiller ───────────────────────────────────────────
  /** Quelles demandes ont une proposition, et où en est la réponse. Silencieux en cas d'échec (pas de bandeau). */
  chargerPropositions(): void {
    this.http.get<ResumeProposition[]>(`${environment.apiUrl}/api/clients/mes-demandes/propositions`).subscribe({
      next: (liste) => {
        this.propositions = {};
        for (const l of liste ?? []) this.propositions[l.dossierId] = l.etat;
      },
      error: () => { this.propositions = {}; }
    });
  }

  etatProposition(dossier: any): ResumeProposition['etat'] | null {
    return this.propositions[String(dossier?.dossierId)] ?? null;
  }

  /** Le client doit répondre à une proposition de cette demande. */
  actionRequise(dossier: any): boolean {
    return this.etatProposition(dossier) === 'EN_ATTENTE_REPONSE';
  }

  get demandesAvecActionRequise(): any[] {
    return this.dossiers.filter(d => this.actionRequise(d));
  }

  libelleEtatProposition(dossier: any): string {
    switch (this.etatProposition(dossier)) {
      case 'EN_ATTENTE_REPONSE': return 'Proposition à examiner';
      case 'ACCEPTEE':           return 'Proposition acceptée';
      case 'REFUSEE':            return 'Proposition refusée';
      default:                   return '';
    }
  }

  private chargerProposition(dossier: any, garderMessage = false): void {
    this.proposition = null;
    this.offreSelectionnee = null;
    this.confirmation = null;
    if (!garderMessage) this.erreurReponse = '';
    if (!this.etatProposition(dossier)) return;

    this.loadingProposition = true;
    this.http.get<PropositionClient>(
      `${environment.apiUrl}/api/clients/mes-demandes/${dossier.dossierId}/proposition`
    ).subscribe({
      next: (p) => { this.proposition = p; this.loadingProposition = false; },
      error: () => { this.proposition = null; this.loadingProposition = false; }
    });
  }

  choisirOffre(numero: number): void {
    if (this.proposition?.etat !== 'EN_ATTENTE_REPONSE') return;
    this.offreSelectionnee = numero;
    this.confirmation = null;
    this.erreurReponse = '';
  }

  /** Première étape : on demande confirmation avant d'envoyer, la réponse est définitive. */
  demanderConfirmation(choix: 'ACCEPTER' | 'REFUSER'): void {
    if (choix === 'ACCEPTER' && this.offreSelectionnee === null) return;
    this.confirmation = choix;
    this.erreurReponse = '';
  }

  annulerConfirmation(): void { this.confirmation = null; }

  get offreChoisie(): OffreProposee | null {
    const n = this.offreSelectionnee;
    return n !== null && this.proposition ? (this.proposition.offres[n] ?? null) : null;
  }

  confirmerReponse(): void {
    if (!this.selectedDossier || !this.confirmation || this.envoiReponse) return;
    if (this.confirmation === 'ACCEPTER' && this.offreSelectionnee === null) return;

    this.envoiReponse = true;
    this.erreurReponse = '';
    const corps = { choix: this.confirmation, offre: this.confirmation === 'ACCEPTER' ? this.offreSelectionnee : null };

    this.http.post<PropositionClient>(
      `${environment.apiUrl}/api/clients/mes-demandes/${this.selectedDossier.dossierId}/proposition/repondre`, corps
    ).subscribe({
      next: (p) => {
        this.proposition = p;
        this.propositions[String(this.selectedDossier.dossierId)] = p.etat;
        this.confirmation = null;
        this.envoiReponse = false;
      },
      error: (e) => {
        this.erreurReponse = e?.error?.message
          || 'Votre réponse n\'a pas pu être enregistrée. Veuillez réessayer dans un instant.';
        this.confirmation = null;
        this.envoiReponse = false;
        // 409 : la réponse existe déjà (autre onglet) ; on relit l'état réel
        if (e?.status === 409) this.chargerProposition(this.selectedDossier, true);
      }
    });
  }

  /** « 20 000 DT » : montants entiers, séparateur de milliers. */
  formatMontant(valeur: number | null | undefined): string {
    if (valeur === null || valeur === undefined || isNaN(Number(valeur))) return '—';
    return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(Number(valeur)) + ' DT';
  }

  formatPourcent(valeur: number | null | undefined): string {
    if (valeur === null || valeur === undefined || isNaN(Number(valeur))) return '—';
    return `${Number(valeur).toFixed(2).replace('.', ',')} %`;
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
    this.chargerProposition(dossier);

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
    this.proposition = null;
    this.offreSelectionnee = null;
    this.confirmation = null;
    this.erreurReponse = '';
  }

  nouvelleDemande() {
    this.router.navigate(['/client/demande']);
  }

  logout() {
    this.clientAuth.logout();
  }
}
