/** Réponse de GET /api/admin/statistiques : le tableau de bord de l'administrateur. */

export interface Periode {
  du: string;      // AAAA-MM-JJ
  au: string;
  jours: number;
}

/** Durées en heures ; null si aucune donnée. */
export interface Delais {
  moyenneHeures: number | null;
  medianeHeures: number | null;
  maxHeures: number | null;
  echantillon: number;
}

export interface Motif {
  decision: string;
  motif: string;
  nombre: number;
}

export interface PointEvolution {
  date: string;          // jour, ou lundi de la semaine
  eligible: number;
  conditionnel: number;
  refus: number;
  aCompleter: number;
  total: number;
}

export interface Envois {
  envoyes: number;
  echecs: number;
  nonEnvoyes: number;
  enAttenteValidation: number;
  programmes: number;
  annules: number;
  sansEnvoi: number;
  /** Toutes périodes confondues : ce qui attend un agent en ce moment. */
  enAttenteValidationTotal: number;
}

export interface StatistiquesDonnees {
  periode: Periode;
  dossiersDeposes: number;
  decisionsTotal: number;
  parDecision: Record<string, number>;
  decisionsDefinitives: number;
  tauxAcceptation: number | null;
  tauxAcceptationAvecConditions: number | null;
  tauxRefus: number | null;
  delaiTraitement: Delais;
  delaiEnvoi: Delais;
  montantMoyenDemande: number | null;
  motifs: Motif[];
  granularite: 'JOUR' | 'SEMAINE';
  evolution: PointEvolution[];
  envois: Envois;
}
