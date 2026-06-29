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

export interface CreditAnalysisResult {
  creditType: string;
  eligibility: 'ELIGIBLE' | 'REFUS' | 'CONDITIONNEL' | 'INDETERMINE';
  eligibilityScore: number;
  financialMetrics: Record<string, any>;
  risks: Array<string | RiskItem>;
  recommendedPlan: Array<string | PlanItem>;
  documentSources?: DocumentSource[];
  rawExplanation: string;
}
