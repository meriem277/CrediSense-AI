import { Component, Input, OnChanges, SimpleChanges, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { environment }         from '../../../../environments/environment';
import { CreditStateService } from '../../../services/credit-state.service';

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
  imports: [CommonModule],
  templateUrl: './upload-section.html',
  styleUrl:    './upload-section.scss'
})
export class UploadSection implements OnChanges {

  @Input()  dossierId = '';
  @Input()  cin       = '';
  @Output() analysisComplete = new EventEmitter<void>(); // ✅ notifie le dashboard

  analyseLoading = false;
  analyseSuccess = '';
  analyseError   = '';
  analyseDone    = false;
  scoreResult: any = null;

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

  loadFichiers(): void {
    this.loading = true;
    this.http.get<any[]>(
      `${environment.apiUrl}/api/dossiers/${this.dossierId}/fichiers`
    ).subscribe({
      next:  (data) => { this.fichiers = data; this.loading = false; },
      error: ()     => { this.loading = false; }
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
          this.analysisComplete.emit(); // ✅ rafraîchit la liste

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
