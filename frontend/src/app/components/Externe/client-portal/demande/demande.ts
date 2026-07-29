// src/app/components/client-portal/demande/demande.ts
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { DemandeService } from '../../../../services/Externe/Demande.service';
import { ClientAuthService } from '../../../../services/Externe/Client-auth.service';

interface DocumentItem {
  id:          string;
  label:       string;
  description: string;
  obligatoire: boolean;
  condition?:  string;
  fichier?:    File;
  statut:      'vide' | 'uploade' | 'uploading' | 'erreur';
}

@Component({
  selector: 'app-demande',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './demande.html',
  styleUrl: './demande.scss'
})
export class Demande implements OnInit {
clientNom      = '';
clientInitiales = '';
clientPhotoUrl  = '';
showNotifs = false;
showMenu   = false;

  currentStep = 1;
  loading     = false;
  errorMsg    = '';

  form = {
    cin: '', nom: '', prenom: '', dateNaissance: '',
    telephone: '', adresse: '', typeContrat: '',
    nationalite: 'TN', montantCredit: '', dureeCredit: ''
  };
notifications = [
  { message: 'Votre dossier a été créé avec succès.', date: 'Aujourd\'hui', lue: false },
  { message: 'Documents en attente de validation.', date: 'Hier', lue: false },
];

get notifCount(): number {
  return this.notifications.filter(n => !n.lue).length;
}
  typesContrat = [
    { value: 'CDI',           label: 'CDI — Contrat à Durée Indéterminée' },
    { value: 'CDD',           label: 'CDD — Contrat à Durée Déterminée' },
    { value: 'FONCTIONNAIRE', label: 'Fonctionnaire' },
    { value: 'INDEPENDANT',   label: 'Indépendant / Gérant' },
  ];

  documents: DocumentItem[] = [];
  dossierId = '';

  constructor(
    private clientAuth:   ClientAuthService,
    private demandeService: DemandeService,
    private router:       Router
  ) {}

  ngOnInit(): void {
    if (!this.clientAuth.isLoggedIn()) {
      this.router.navigate(['/client/login']);
      return;
    }
    const user = this.clientAuth.getUser();
    if (user?.nom)    this.form.nom    = user.nom;
    if (user?.prenom) this.form.prenom = user.prenom;
     this.clientNom      = `${user?.prenom || ''} ${user?.nom || ''}`.trim();
  this.clientPhotoUrl = user?.photoUrl || '';
  this.clientInitiales = [user?.prenom?.[0], user?.nom?.[0]]
    .filter(Boolean).join('').toUpperCase();
}


  get age(): number {
    if (!this.form.dateNaissance) return 0;
    return Math.floor(
      (Date.now() - new Date(this.form.dateNaissance).getTime())
      / (1000 * 60 * 60 * 24 * 365.25)
    );

  }

  get step1Valide(): boolean {
    return !!(this.form.cin && this.form.nom && this.form.prenom &&
              this.form.dateNaissance && this.form.telephone &&
              this.form.typeContrat && this.form.montantCredit);
  }

  genererChecklist(): void {
    const docs: DocumentItem[] = [
      { id: 'CIN',               label: 'Carte d\'identité nationale (CIN)',   description: 'Copie recto/verso valide',                      obligatoire: true,  statut: 'vide' },
      { id: 'FICHE_PAIE',        label: 'Fiches de paie (3 derniers mois)',     description: 'Les 3 dernières fiches de paie',                obligatoire: true,  statut: 'vide' },
      { id: 'RELEVE_BANCAIRE',   label: 'Relevé bancaire (6 derniers mois)',    description: '6 derniers mois de votre compte principal',     obligatoire: true,  statut: 'vide' },
      { id: 'ATTESTATION_EMPLOI',label: 'Attestation d\'emploi',               description: 'Délivrée par votre employeur',                  obligatoire: true,  statut: 'vide' },
      { id: 'JUSTIFICATIF_DOMICILE', label: 'Justificatif de domicile',        description: 'Facture électricité, eau ou téléphone récente', obligatoire: true,  statut: 'vide' },
    ];

    if (this.form.typeContrat === 'CDD') {
      docs.push({ id: 'CONTRAT_TRAVAIL', label: 'Contrat de travail', description: 'Avec durée restante visible', obligatoire: true, condition: 'Requis pour les CDD', statut: 'vide' });
    }
    if (this.form.typeContrat === 'INDEPENDANT') {
      docs.push({ id: 'BILAN_COMPTABLE',    label: 'Bilan comptable',      description: 'Des 2 dernières années', obligatoire: true, condition: 'Requis pour les indépendants', statut: 'vide' });
      docs.push({ id: 'DECLARATION_FISCALE', label: 'Déclaration fiscale', description: 'Déclaration annuelle',   obligatoire: true, condition: 'Requis pour les indépendants', statut: 'vide' });
    }
    if (this.age > 60) {
      docs.push({ id: 'ASSURANCE_VIE', label: 'Assurance vie / décès-invalidité', description: 'Police couvrant la durée du crédit', obligatoire: true, condition: `Requis car vous avez ${this.age} ans`, statut: 'vide' });
    }
    if (this.form.nationalite !== 'TN') {
      docs.push({ id: 'TITRE_SEJOUR', label: 'Titre de séjour', description: 'En cours de validité + permis de travail', obligatoire: true, condition: 'Requis pour les résidents étrangers', statut: 'vide' });
    }

    this.documents = docs;
  }

  allerStep2(): void {
    if (!this.step1Valide) { this.errorMsg = 'Veuillez remplir tous les champs obligatoires.'; return; }
    if (this.age > 70)     { this.errorMsg = `Votre âge (${this.age} ans) dépasse la limite autorisée.`; return; }

    this.loading  = true;
    this.errorMsg = '';

    const user = this.clientAuth.getUser();

    this.demandeService.creerDemande({
      cin: this.form.cin, nom: this.form.nom, prenom: this.form.prenom,
      dateNaissance: this.form.dateNaissance, telephone: this.form.telephone,
      adresse: this.form.adresse, typeContrat: this.form.typeContrat,
      nationalite: this.form.nationalite, montantCredit: this.form.montantCredit,
      dureeCredit: this.form.dureeCredit, clientEmail: user?.email || ''
    }).subscribe({
      next: (res) => {
        this.dossierId = res.dossierId;
         localStorage.setItem('dossierId', res.dossierId);
        this.genererChecklist();
        this.currentStep = 2;
        this.loading     = false;
      },
      error: (err) => {
        this.errorMsg = err?.error?.message || 'Erreur lors de la création du dossier';
        this.loading  = false;
      }
    });
  }

  retourStep1(): void { this.currentStep = 1; this.errorMsg = ''; }

  onFileSelected(event: any, doc: DocumentItem): void {
    const file = event.target.files[0];
    if (!file) return;
    doc.fichier = file;
    doc.statut  = 'uploading';

    this.demandeService.uploadDocument(file, this.form.cin, this.dossierId, doc.id)
      .subscribe({
        next:  () => { doc.statut = 'uploade'; },
        error: () => { doc.statut = 'erreur';  }
      });
  }

  get documentsUploades(): number {
    return this.documents.filter(d => d.statut === 'uploade').length;
  }

  get tousDocumentsObligatoires(): boolean {
    return this.documents.filter(d => d.obligatoire).every(d => d.statut === 'uploade');
  }

  get progression(): number {
    if (!this.documents.length) return 0;
    return Math.round((this.documentsUploades / this.documents.length) * 100);
  }

  soumettreDossier(): void {
    if (!this.tousDocumentsObligatoires) {
      this.errorMsg = 'Veuillez uploader tous les documents obligatoires.';
      return;
    }
    this.loading = true;
    this.demandeService.soumettreDossier(this.dossierId).subscribe({
      next: () => {
        this.loading = false;
        this.router.navigate(['/client/confirmation']);
      },
      error: () => {
        this.loading  = false;
        this.errorMsg = 'Erreur lors de la soumission. Veuillez réessayer.';
      }
    });
  }
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
