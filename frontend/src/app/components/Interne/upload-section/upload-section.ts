import { Component, Input, OnChanges, SimpleChanges, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { environment }         from '../../../../environments/environment';
import { CreditStateService } from '../../../services/credit-state.service';
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
  peutAnalyser = false;

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
    }
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
          cinCoherent:  f.cinCoherent ?? null
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
  estVerifie(fichier: any): boolean {
    return fichier.verifie === true;
  }

  // ✅ Message affiché au-dessus du fichier — "CIN vérifié et validé", etc.
  getMessageVerification(fichier: any): string {
    if (!this.estVerifie(fichier)) {
      return 'En attente de vérification';
    }
    if (fichier.typeDocument === 'AUTRE') {
      return 'Type de document non reconnu';
    }
    const libelle = LABELS_TYPE_DOCUMENT[fichier.typeDocument] || fichier.typeDocument;
    return `${libelle} vérifié et validé`;
  }

  // ── Analyser ───────────────────────────────────────────────────────────────
  handleAnalyse(): void {
    if (!this.dossierId) return;

    this.analyseLoading = true;
    this.analyseSuccess = '';
    this.analyseError   = '';

    this.http.post<any>(
      `${environment.apiUrl}/api/fichiers/analyser-dossier/${this.dossierId}`,
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
        this.analyseError   = '❌ ' + (err.error?.message || 'Erreur lors de l\'analyse.');
      }
    });
  }

  // ── Score Crédit ───────────────────────────────────────────────────────────
  handleScore(): void {
    if (!this.dossierId) return;

    if (this.scoreResult) {
      this._setScore(this.scoreResult);
      this.creditState.triggerNavigateToScore();
      return;
    }

    this.analyseLoading = true;
    this.analyseError   = '';
    this.analyseSuccess = '';

    this.http.post<any>(
      `${environment.apiUrl}/api/fichiers/analyser-dossier/${this.dossierId}`,
      {}
    ).subscribe({
      next: (res) => {
        this.analyseLoading = false;

        if (res.scoreResult && Object.keys(res.scoreResult).length > 0) {
          this.scoreResult = res.scoreResult;
          this._setScore(res.scoreResult);
          this.analyseSuccess = '✅ Analyse terminée';
          this.analyseDone    = true;

          // ✅ FIX — même raison que dans handleAnalyse()
          this.loadFichiers();

          this.analysisComplete.emit(); // ✅ rafraîchit la liste
        }

        this.creditState.triggerNavigateToScore();
      },
      error: () => {
        this.analyseLoading = false;
        this.creditState.triggerNavigateToScore();
      }
    });
  }

  // ── Helper ─────────────────────────────────────────────────────────────────
  private _setScore(score: any): void {
    this.creditState.setResult({
      eligibility:      score.eligibility                               || 'INCONNU',
      eligibilityScore: score.eligibilityScore || score.eligibility_score || 0,
      creditType:       score.creditType                               || 'CONSOMMATION',
      financialMetrics: score.financialMetrics || score.financial_metrics || {},
      risks:            score.risks                                    || [],
      recommendedPlan:  score.recommendedPlan  || score.recommended_plan  || [],
      documentSources:  score.documentSources  || score.document_sources  || [],
      rawExplanation:   score.rawExplanation   || score.explanation        || ''
    });
  }

}
