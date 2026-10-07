// credit-analysis-result.model.ts

export interface DocumentSource {
  field?: string;
  value?: any;
  foundIn?: string;
}

export interface PlanItem {
  priority?: number;
  action?: string;
  rationale?: string;
  source?: string;
}

export interface RiskItem {
  level?: 'HIGH' | 'MEDIUM' | 'LOW';
  description?: string;
  source?: string;
}

/** Point fort ou point de vigilance : « titre, détail chiffré, document d'origine ». */
export interface PointAnalyse {
  title?: string;
  detail?: string;
  source?: string;
}

/** Un critère vérifié par le moteur de règles (calculé en Python, pas par le LLM). */
export interface ControleReglementaire {
  criterion: string;
  status: 'OK' | 'ATTENTION' | 'KO' | 'A_VERIFIER';
  value: string;
  threshold: string;
  explanation: string;
  blocking: boolean;
}

export interface CapaciteEmprunt {
  maxMonthlyPayment?: number;
  remainingMonthly?: number;
  maxAmountForDuration?: number;
  referenceDuration?: number;
  salaryCap?: number | null;
  explanation?: string;
}

/** Mensualité et taux d'endettement pour une durée donnée. */
export interface SimulationDuree {
  duration: number;
  monthlyPayment: number;
  dti: number;
  totalCost: number;
  status: 'OK' | 'ATTENTION' | 'KO';
  isRequested: boolean;
}

export interface CreditAnalysisResult {
  messageIdentite?: string;
  alerteIdentite?: boolean;
  creditType: string;
  /** A_COMPLETER : des informations indispensables manquent, aucune décision n'est rendue. */
  eligibility: 'ELIGIBLE' | 'REFUS' | 'CONDITIONNEL' | 'A_COMPLETER' | 'INDETERMINE';
  eligibilityScore: number;
  /** Vrai quand le score n'est pas définitif (dossier à compléter). */
  scoreProvisoire?: boolean;
  summary?: string;
  financialMetrics: Record<string, any>;
  risks: Array<string | RiskItem>;
  recommendedPlan: Array<string | PlanItem>;
  documentSources?: DocumentSource[];
  strengths?: Array<string | PointAnalyse>;
  weaknesses?: Array<string | PointAnalyse>;
  conditions?: string[];
  regulatoryChecks?: ControleReglementaire[];
  capacity?: CapaciteEmprunt;
  simulations?: SimulationDuree[];
  calculationNote?: string;
  tauxAnnuelApplique?: number | null;
  /** Ce qui manque pour pouvoir décider, avec où le trouver. */
  donneesManquantes?: string[];
  /** Ex : « Le document X a été raccourci… » — le texte envoyé à l'IA a dépassé la taille maximale */
  avertissements?: string[];
  /** Texte du LLM avant que les contrôles ne remplacent sa décision (non destiné au client). */
  analysePreliminaire?: string;
  rawExplanation: string;
}

/**
 * Convertit la réponse du service IA (ou le résultat enregistré en base) en résultat affichable.
 * UN SEUL endroit : avant, deux composants recopiaient les champs un par un et oubliaient
 * régulièrement les nouveaux (le résultat s'affichait sans eux).
 */
export function mapperResultat(src: any): CreditAnalysisResult {
  const s = src ?? {};
  return {
    eligibility:        s.eligibility || 'INDETERMINE',
    eligibilityScore:   s.eligibilityScore ?? s.eligibility_score ?? 0,
    scoreProvisoire:    s.scoreProvisoire === true,
    creditType:         s.creditType || 'CONSOMMATION',
    summary:            s.summary || undefined,
    financialMetrics:   s.financialMetrics || s.financial_metrics || {},
    risks:              s.risks || [],
    recommendedPlan:    s.recommendedPlan || s.recommended_plan || [],
    documentSources:    s.documentSources || s.document_sources || [],
    strengths:          s.strengths || [],
    weaknesses:         s.weaknesses || [],
    conditions:         s.conditions || [],
    regulatoryChecks:   s.regulatoryChecks || [],
    capacity:           s.capacity || undefined,
    simulations:        s.simulations || [],
    calculationNote:    s.calculationNote || undefined,
    tauxAnnuelApplique: s.tauxAnnuelApplique ?? null,
    donneesManquantes:  s.donneesManquantes || [],
    avertissements:     s.avertissements || [],
    alerteIdentite:     s.alerteIdentite,
    messageIdentite:    s.messageIdentite,
    analysePreliminaire: s.analysePreliminaire || undefined,
    rawExplanation:     s.rawExplanation || s.explanation || '',
  };
}
