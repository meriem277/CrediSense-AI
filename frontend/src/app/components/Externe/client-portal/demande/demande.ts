// src/app/components/client-portal/demande/demande.ts
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { DemandeService } from '../../../../services/Externe/Demande.service';
import { ClientAuthService } from '../../../../services/Externe/Client-auth.service';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../environments/environment';

// ── Types ─────────────────────────────────────────────────────────────────────
interface DocumentItem {
  typeDoc:     string;
  label:       string;
  description: string;
  obligatoire: boolean;
  condition?:  string;
  fichier?:    File;
  statut:      'vide' | 'uploading' | 'uploade' | 'erreur';
}

@Component({
  selector: 'app-demande',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './demande.html',
  styleUrl:    './demande.scss'
})
export class Demande implements OnInit {

  currentStep = 1;
  loading     = false;
  errorMsg    = '';
  dossierId: string | null = null;

  // ── Infos header ──────────────────────────────────────────────────────────
  clientNom       = '';
  clientInitiales = '';
  clientPhotoUrl  = '';
  showNotifs      = false;
  showMenu        = false;

  notifications = [
    { message: 'Votre dossier a été créé avec succès.', date: 'Aujourd\'hui', lue: false },
    { message: 'Documents en attente de validation.',   date: 'Hier',         lue: false },
  ];

  get notifCount(): number {
    return this.notifications.filter(n => !n.lue).length;
  }

  // ── Formulaire step 1 ─────────────────────────────────────────────────────
  form = {
    cin:           '',
    prenom:        '',
    nom:           '',
    dateNaissance: '',
    telephone:     '',
    adresse:       '',
    nationalite:   'TN',
    typeContrat:   '',
    montantCredit: null as number | null,
    dureeCredit:   null as number | null,
  };

  typesContrat = [
    { value: 'CDI',          label: 'CDI — Contrat à Durée Indéterminée' },
    { value: 'CDD',          label: 'CDD — Contrat à Durée Déterminée'   },
    { value: 'INDEPENDANT',  label: 'Indépendant / Profession libérale'  },
    { value: 'GERANT',       label: 'Gérant / Chef d\'entreprise'        },
    { value: 'RETRAITE',     label: 'Retraité'                           },
  ];

  get age(): number {
    if (!this.form.dateNaissance) return 0;
    const diff = Date.now() - new Date(this.form.dateNaissance).getTime();
    return Math.floor(diff / (365.25 * 24 * 3600 * 1000));
  }

  get step1Valide(): boolean {
    return !!(
      this.form.cin          &&
      this.form.prenom       &&
      this.form.nom          &&
      this.form.dateNaissance &&
      this.form.telephone    &&
      this.form.typeContrat  &&
      this.form.montantCredit &&
      this.form.dureeCredit
    );
  }

  // ── Documents step 2 ──────────────────────────────────────────────────────
  documents: DocumentItem[] = [];

  get documentsUploades(): number {
    return this.documents.filter(d => d.statut === 'uploade').length;
  }

  get tousDocumentsObligatoires(): boolean {
    return this.documents
      .filter(d => d.obligatoire)
      .every(d => d.statut === 'uploade');
  }

  get progression(): number {
    if (!this.documents.length) return 0;
    return Math.round((this.documentsUploades / this.documents.length) * 100);
  }

  constructor(
    private http:       HttpClient,
    private clientAuth: ClientAuthService,
    private router:     Router
  ) {}

  ngOnInit(): void {
    if (!this.clientAuth.isLoggedIn()) {
      this.router.navigate(['/client/login']);
      return;
    }
    const user = this.clientAuth.getUser();
    if (user?.nom)    this.form.nom    = user.nom;
    if (user?.prenom) this.form.prenom = user.prenom;
    if (user?.cin) this.form.cin = user.cin;
    this.clientNom      = `${user?.prenom || ''} ${user?.nom || ''}`.trim();
    this.clientPhotoUrl = user?.photoUrl || '';
    this.clientInitiales = [user?.prenom?.[0], user?.nom?.[0]]
      .filter(Boolean).join('').toUpperCase();
  }

  // ── Step 1 → Step 2 ───────────────────────────────────────────────────────
  allerStep2(): void {
    if (!this.step1Valide || this.age > 70) return;
    this.loading  = true;
    this.errorMsg = '';

    const user = this.clientAuth.getUser();

    this.http.post<any>(
      `${environment.apiUrl}/api/public/demande`,
      {
        cin:           this.form.cin,
        nom:           this.form.nom,
        prenom:        this.form.prenom,
        clientEmail:   user?.email,
        telephone:     this.form.telephone,
        adresse:       this.form.adresse,
        typeContrat:   this.form.typeContrat,
        nationalite:   this.form.nationalite,
        dateNaissance: this.form.dateNaissance,
        montantCredit: this.form.montantCredit,
        dureeCredit:   this.form.dureeCredit,
      }
    ).subscribe({
      next: (res) => {
        this.dossierId   = res.dossierId;
        this.loading     = false;
        this.genererChecklist();
        this.currentStep = 2;
      },
      error: (err) => {
        this.errorMsg = err.error?.message || 'Erreur lors de la création du dossier';
        this.loading  = false;
      }
    });
  }

  // ── Checklist documents ───────────────────────────────────────────────────
  genererChecklist(): void {
    this.documents = [
      {
        typeDoc:     'CIN',
        label:       'Carte d\'identité nationale (CIN)',
        description: 'Copie recto/verso valide',
        obligatoire: true,
        statut:      'vide'
      },
      {
        typeDoc:     'FICHE_PAIE',
        label:       'Fiches de paie (3 derniers mois)',
        description: 'Les 3 dernières fiches de paie',
        obligatoire: true,
        statut:      'vide'
      },
      {
        typeDoc:     'RELEVE_BANCAIRE',
        label:       'Relevé bancaire (6 derniers mois)',
        description: '6 derniers mois de votre compte principal',
        obligatoire: true,
        statut:      'vide'
      },
      {
        typeDoc:     'ATTESTATION_EMPLOI',
        label:       'Attestation d\'emploi',
        description: 'Délivrée par votre employeur',
        obligatoire: true,
        statut:      'vide'
      },
      {
        typeDoc:     'JUSTIFICATIF_DOMICILE',
        label:       'Justificatif de domicile',
        description: 'Facture électricité, eau ou téléphone récente',
        obligatoire: true,
        statut:      'vide'
      },
      ...(this.age > 60 ? [{
        typeDoc:     'ASSURANCE_VIE',
        label:       'Assurance vie',
        description: 'Obligatoire pour les clients de plus de 60 ans',
        obligatoire: true,
        condition:   `Âge ${this.age} ans > 60 ans`,
        statut:      'vide' as const
      }] : []),
      ...(['CDD'].includes(this.form.typeContrat) ? [{
        typeDoc:     'CONTRAT_TRAVAIL',
        label:       'Contrat de travail',
        description: 'Contrat CDD en cours',
        obligatoire: true,
        condition:   'Contrat CDD',
        statut:      'vide' as const
      }] : []),
    ];
  }

  // ── Upload fichier ────────────────────────────────────────────────────────
  onFileSelected(event: Event, doc: DocumentItem): void {
  const input = event.target as HTMLInputElement;
  if (!input.files?.length) return;

  const file = input.files[0];
  const nomSansExtension = file.name.substring(0, file.name.lastIndexOf('.')) || file.name;

  if (nomSansExtension.toUpperCase() !== doc.typeDoc.toUpperCase()) {
    this.errorMsg = `Le fichier doit s'appeler "${doc.typeDoc}" (nom actuel : "${nomSansExtension}")`;
    doc.statut = 'erreur';
    input.value = '';
    return;
  }

  this.errorMsg = '';
  doc.fichier = file;
  doc.statut  = 'uploading';

  const formData = new FormData();
  formData.append('file', file);
  formData.append('cin', this.form.cin);
  formData.append('dossierId', this.dossierId!);
  formData.append('typeDocument', doc.typeDoc);

  this.http.post<any>(
    `${environment.apiUrl}/api/public/upload`,
    formData
  ).subscribe({
    next: () => { doc.statut = 'uploade'; },
    error: (err) => {
      console.error('Upload error:', err);
      doc.statut = 'erreur';
    }
  });
}

  // ── Soumettre dossier ─────────────────────────────────────────────────────
  soumettreDossier(): void {
    if (!this.tousDocumentsObligatoires) return;
    this.loading = true;

    localStorage.setItem('dossierId', this.dossierId!);
    this.router.navigate(['/client/confirmation']);
  }

  // ── Retour step 1 ─────────────────────────────────────────────────────────
  retourStep1(): void {
    this.currentStep = 1;
    this.errorMsg    = '';
  }

  // ── Header actions ────────────────────────────────────────────────────────
  toggleNotifs() {
    this.showNotifs = !this.showNotifs;
    this.showMenu   = false;
  }

  toggleMenu() {
    this.showMenu   = !this.showMenu;
    this.showNotifs = false;
  }

  marquerToutesLues() {
    this.notifications.forEach(n => n.lue = true);
  }

  voirHistorique() {
    this.router.navigate(['/client/historique']);
  }

  logout() {
    this.clientAuth.logout();
  }
}
