import { Component, Input, OnChanges, SimpleChanges, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { environment }         from '../../../../environments/environment';
import { CreditStateService } from '../../../services/credit-state.service';
import { mapperResultat } from '../../../models/credit-analysis-result.model';
import { DocumentPipeline, PipelineAnalyseComponent } from '../pipeline-analyse/pipeline-analyse';

// ✅ Libellés lisibles pour chaque type de document détecté par le pipeline IA
const LABELS_TYPE_DOCUMENT: Record<string, string> = {
  CIN:                    'CIN',
  FICHE_PAIE:             'Fiche de paie',
  RELEVE_BANCAIRE:        'Relevé bancaire',
  ATTESTATION_EMPLOI:     'Attestation d\'emploi',
  CONTRAT_TRAVAIL:        'Contrat de travail',
  ASSURANCE_VIE:          'Assurance vie',
  BILAN_COMPTABLE:        'Bilan comptable',
  DECLARATION_FISCALE:    'Déclaration fiscale',
  JUSTIFICATIF_DOMICILE:  'Justificatif de domicile',
  TITRE_SEJOUR:           'Titre de séjour',
};

@Component({
  selector: 'app-upload-section',
  standalone: true,
  imports: [CommonModule, PipelineAnalyseComponent],
  templateUrl: './upload-section.html',
  styleUrl:    './upload-section.scss'
})
export class UploadSection implements OnChanges {

  @Input()  dossierId = '';
  @Input()  cin       = '';
  @Input()  statutDossier = '';   // ✅ nouveau — 'APPROUVE'/'REFUSE' = dossier déjà tranché
  @Output() analysisComplete = new EventEmitter<void>(); // ✅ notifie le dashboard

  analyseLoading = false;
  analyseSuccess = '';
  analyseError   = '';
  analyseDone    = false;
  scoreResult: any = null;

  // ✅ Alimenté par loadFichiers() — chaque fichier avec son typeDocument,
  // son jsonData extrait, et cinCoherent, consommés par <app-pipeline-analyse>
  documentsAvecJson: DocumentPipeline[] = [];

  // Alimentés par le pipeline : les étapes 1 et 2 sont validées / seul le CIN est incohérent
  peutAnalyser                 = false;
  peutAnalyserAvecConfirmation = false;

  // Étape « Vérifier les documents »
  verifierLoading = false;
  verifierSuccess = '';
  verifierError   = '';

  // Message affiché quand l'agent doit confirmer pour poursuivre malgré un CIN incohérent
  confirmationIncoherence: string | null = null;

  fichiers: any[] = [];
  loading  = false;

  constructor(
    private http:        HttpClient,
    private creditState: CreditStateService
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['dossierId'] && this.dossierId) {
      this.loadFichiers();
      this.scoreResult    = null;
      this.analyseDone    = false;
      this.analyseSuccess = '';
      this.analyseError   = '';
      this.verifierSuccess = '';
      this.verifierError   = '';
      this.confirmationIncoherence = null;
    }
  }

  // ── État reçu du pipeline ──────────────────────────────────────────────────
  onEtatPipeline(etat: { peutAnalyser: boolean; peutAnalyserAvecConfirmation: boolean }): void {
    this.peutAnalyser                 = etat.peutAnalyser;
    this.peutAnalyserAvecConfirmation = etat.peutAnalyserAvecConfirmation;
  }

  /** Le bouton « Analyser » n'est actif que si le pipeline l'autorise (directement ou après confirmation). */
  get analyseAutorisee(): boolean {
    return this.peutAnalyser || this.peutAnalyserAvecConfirmation;
  }

  // ✅ Nouveau — un dossier déjà APPROUVE/REFUSE a forcément été analysé et
  // scoré, même si analyseDone/scoreResult (état de session) sont encore à
  // leurs valeurs par défaut après un simple "Consulter". Le pipeline doit
  // refléter cet état déjà acquis, pas seulement ce qui vient de se passer
  // dans la session en cours.
  get analyseTermineeEffective(): boolean {
    return this.analyseDone || ['APPROUVE', 'REFUSE'].includes(this.statutDossier);
  }

  get scoreDisponibleEffective(): boolean {
    return !!this.scoreResult || ['APPROUVE', 'REFUSE'].includes(this.statutDossier);
  }

  loadFichiers(): void {
    this.loading = true;
    this.http.get<any[]>(
      `${environment.apiUrl}/api/dossiers/${this.dossierId}/fichiers`
    ).subscribe({
      next: (data) => {
        this.fichiers = data;

        // ✅ Reconstruit la liste consommée par le pipeline à chaque
        // rechargement — indispensable après une analyse pour que le
        // stepper reflète les jsonData fraîchement extraits.
        this.documentsAvecJson = (data || []).map(f => ({
          nomOriginal:  f.nomOriginal,
          typeDocument: f.typeDocument,
          jsonData:     f.jsonData ?? null,
          cinCoherent:  f.cinCoherent ?? null,
          typeDetecte:  f.typeDetecte ?? null,
          typeConflit:  f.typeConflit ?? null
        }));

        this.loading = false;
      },
      error: () => { this.loading = false; }
    });
  }

  getFileUrl(chemin: string): string {
    return `${environment.apiUrl}/api/fichiers/view/${chemin}`;
  }

  // ✅ Est-ce que ce fichier a été identifié avec succès par le pipeline IA ?
  // Un document dont le CIN ne correspond pas à celui du client n'est PAS « validé »,
  // même si l'IA a pu le lire (sinon le badge contredit l'étape « Vérification CIN »).
  estVerifie(fichier: any): boolean {
    return fichier.verifie === true && fichier.cinCoherent !== false && fichier.typeConflit !== true;
  }

  private libelleType(type: string): string {
    return LABELS_TYPE_DOCUMENT[type] || type;
  }

  // ✅ Message affiché au-dessus du fichier — "CIN vérifié et validé", etc.
  getMessageVerification(fichier: any): string {
    // L'OCR a échoué (format non supporté, image illisible, service indisponible…) :
    // on affiche la raison plutôt qu'un « en attente » trompeur.
    if (fichier.ocrStatut === 'FAILED') {
      return `Lecture impossible : ${fichier.ocrErreur || 'document illisible'}`;
    }
    if (fichier.cinCoherent === false) {
      return 'CIN incohérent avec celui du client : vérification manuelle recommandée';
    }
    // Le contenu du document ne correspond pas à l'emplacement choisi par le client
    if (fichier.typeConflit === true) {
      return `Type incohérent : déposé comme « ${this.libelleType(fichier.typeDocument)} », ` +
             `le contenu ressemble à « ${this.libelleType(fichier.typeDetecte)} » : vérification manuelle recommandée`;
    }
    if (!this.estVerifie(fichier)) {
      return 'En attente de vérification';
    }
    if (fichier.typeDocument === 'AUTRE') {
      return 'Type de document non reconnu';
    }
    const libelle = LABELS_TYPE_DOCUMENT[fichier.typeDocument] || fichier.typeDocument;
    return `${libelle} vérifié et validé`;
  }

  // ── Étape 1 : vérifier les documents (lecture, extraction, cohérence du CIN) ─
  handleVerifier(): void {
    if (!this.dossierId) return;

    this.verifierLoading = true;
    this.verifierSuccess = '';
    this.verifierError   = '';
    this.analyseSuccess  = '';
    this.analyseError    = '';
    this.confirmationIncoherence = null;

    this.http.post<any>(
      `${environment.apiUrl}/api/fichiers/verifier-dossier/${this.dossierId}`,
      {}
    ).subscribe({
      next: (res) => {
        this.verifierLoading = false;
        if (res.success) {
          this.verifierSuccess = res.message;
          this.loadFichiers();   // le pipeline se met à jour avec ce qui vient d'être lu
        } else {
          this.verifierError = res.message || 'Erreur lors de la vérification des documents.';
        }
      },
      error: (err: any) => {
        this.verifierLoading = false;
        this.verifierError = err.error?.message || 'Erreur lors de la vérification des documents.';
      }
    });
  }

  // ── Étape 2 : clic sur « Analyser » ────────────────────────────────────────
  // Tout est validé : on lance. Seul le CIN est incohérent : on demande d'abord
  // confirmation à l'agent (il a pu vérifier manuellement et décider de poursuivre).
  onClickAnalyser(): void {
    if (this.peutAnalyser) {
      this.handleAnalyse();
      return;
    }
    if (this.peutAnalyserAvecConfirmation) {
      this.confirmationIncoherence = this.messageIncoherence();
    }
  }

  /** Ce qui est incohérent dans le dossier : CIN d'un document et/ou type d'un document. */
  private messageIncoherence(): string {
    const parties: string[] = [];

    const cin = this.documentsAvecJson.filter(d => d.cinCoherent === false)
      .map(d => this.libelleType(d.typeDocument));
    if (cin.length > 0) {
      parties.push(`Le numéro de CIN lu ne correspond pas à celui du client (${cin.join(', ')}).`);
    }

    const types = this.documentsAvecJson.filter(d => d.typeConflit === true)
      .map(d => `${this.libelleType(d.typeDocument)} → ${this.libelleType(d.typeDetecte ?? '')}`);
    if (types.length > 0) {
      parties.push(`Le contenu de certains documents ne correspond pas à leur type déclaré (${types.join(', ')}).`);
    }

    parties.push('Vérifiez les documents manuellement avant de poursuivre.');
    return parties.join(' ');
  }

  confirmerAnalyse(): void {
    this.confirmationIncoherence = null;
    this.handleAnalyse(true);
  }

  annulerConfirmation(): void {
    this.confirmationIncoherence = null;
  }

  // ── Analyser ───────────────────────────────────────────────────────────────
  handleAnalyse(confirmerIncoherence = false): void {
    if (!this.dossierId) return;

    this.analyseLoading = true;
    this.analyseSuccess = '';
    this.analyseError   = '';
    this.verifierSuccess = '';
    this.verifierError   = '';

    const parametre = confirmerIncoherence ? '?confirmerIncoherence=true' : '';

    this.http.post<any>(
      `${environment.apiUrl}/api/fichiers/analyser-dossier/${this.dossierId}${parametre}`,
      {}
    ).subscribe({
      next: (res) => {
        this.analyseLoading = false;

        if (res.success) {
          this.analyseSuccess = '✅ ' + res.message;
          this.analyseDone    = true;

          // ✅ FIX — sans cet appel, documentsAvecJson reste figé sur l'état
          // chargé à l'ouverture de la page : le pipeline (étapes 1 et 2)
          // ne voit jamais les jsonData fraîchement extraits par l'analyse,
          // même si le score (étapes 3 et 4) est déjà disponible.
          this.loadFichiers();

          this.analysisComplete.emit(); // ✅ rafraîchit la liste du parent

          if (res.scoreResult && Object.keys(res.scoreResult).length > 0) {
            this.scoreResult = res.scoreResult;
            this._setScore(res.scoreResult);
          }
        } else {
          this.analyseError = '❌ ' + (res.message || 'Erreur lors de l\'analyse.');
        }
      },
      error: (err: any) => {
        this.analyseLoading = false;

        // Le serveur a suspendu l'analyse : CIN incohérent non confirmé. On demande
        // à l'agent de décider, au lieu d'afficher une erreur.
        if (err.status === 409 && ['CIN_INCOHERENT', 'TYPE_INCOHERENT'].includes(err.error?.code)) {
          this.confirmationIncoherence = err.error.message;
          this.loadFichiers();
          return;
        }

        this.analyseError = '❌ ' + (err.error?.message || 'Erreur lors de l\'analyse.');
      }
    });
  }

  // ── Score Crédit ───────────────────────────────────────────────────────────
  // Affiche le score déjà calculé. Ne lance PLUS l'analyse : le bouton est inactif
  // tant que l'analyse n'a pas été faite (le résultat enregistré est rechargé
  // par l'écran du résultat à l'ouverture d'un dossier déjà analysé).
  handleScore(): void {
    if (!this.dossierId || !this.scoreDisponibleEffective) return;

    if (this.scoreResult) {
      this._setScore(this.scoreResult);
    }
    this.creditState.triggerNavigateToScore();
  }

  // ── Helper ─────────────────────────────────────────────────────────────────
  private _setScore(score: any): void {
    this.creditState.setResult(mapperResultat(score));
  }

}
