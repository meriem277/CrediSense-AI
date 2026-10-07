import { mapperResultat } from './credit-analysis-result.model';

describe('mapperResultat', () => {

  const COMPLET = {
    eligibility: 'CONDITIONNEL',
    eligibilityScore: 61,
    scoreProvisoire: false,
    creditType: 'CONSOMMATION',
    summary: 'Dossier correct.',
    financialMetrics: { dti: 26.4 },
    risks: [{ level: 'LOW', description: 'r' }],
    recommendedPlan: [{ priority: 1, action: 'a' }],
    documentSources: [{ field: 'Revenu', value: '2 100 DT', foundIn: 'Fiche de paie' }],
    strengths: [{ title: 'CDI' }],
    weaknesses: [{ title: 'Ancienneté' }],
    conditions: ['Assurance décès-invalidité'],
    regulatoryChecks: [{ criterion: 'Taux d\'endettement', status: 'OK', value: '26 %', threshold: '< 30 %', explanation: 'ok', blocking: true }],
    capacity: { maxMonthlyPayment: 630, explanation: 'Capacité' },
    simulations: [{ duration: 48, monthlyPayment: 228.3, dti: 22.8, totalCost: 10958, status: 'OK', isRequested: true }],
    calculationNote: 'Taux 10 %.',
    tauxAnnuelApplique: 0.1,
    donneesManquantes: [],
    avertissements: ['Document raccourci'],
    alerteIdentite: true,
    messageIdentite: 'Nom différent',
    analysePreliminaire: 'texte',
    rawExplanation: 'Analyse',
  };

  it('recopie TOUS les champs (aucun n\'est perdu)', () => {
    const r = mapperResultat(COMPLET);

    expect(r.summary).toBe('Dossier correct.');
    expect(r.strengths!.length).toBe(1);
    expect(r.weaknesses!.length).toBe(1);
    expect(r.conditions).toEqual(['Assurance décès-invalidité']);
    expect(r.regulatoryChecks!.length).toBe(1);
    expect(r.capacity!.maxMonthlyPayment).toBe(630);
    expect(r.simulations![0].isRequested).toBe(true);
    expect(r.calculationNote).toBe('Taux 10 %.');
    expect(r.tauxAnnuelApplique).toBe(0.1);
    expect(r.avertissements).toEqual(['Document raccourci']);
    expect(r.alerteIdentite).toBe(true);
    expect(r.messageIdentite).toBe('Nom différent');
    expect(r.analysePreliminaire).toBe('texte');
    expect(r.rawExplanation).toBe('Analyse');
  });

  it('une réponse vide donne des valeurs par défaut sûres', () => {
    for (const vide of [null, undefined, {}]) {
      const r = mapperResultat(vide);
      expect(r.eligibility).toBe('INDETERMINE');
      expect(r.eligibilityScore).toBe(0);
      expect(r.risks).toEqual([]);
      expect(r.regulatoryChecks).toEqual([]);
      expect(r.simulations).toEqual([]);
      expect(r.donneesManquantes).toEqual([]);
      expect(r.avertissements).toEqual([]);
      expect(r.scoreProvisoire).toBe(false);
      expect(r.tauxAnnuelApplique).toBeNull();
    }
  });

  it('conserve la décision A_COMPLETER et le score provisoire', () => {
    const r = mapperResultat({ eligibility: 'A_COMPLETER', eligibilityScore: 40, scoreProvisoire: true,
                               donneesManquantes: ['Dettes existantes — relevé bancaire'] });
    expect(r.eligibility).toBe('A_COMPLETER');
    expect(r.scoreProvisoire).toBe(true);
    expect(r.donneesManquantes).toEqual(['Dettes existantes — relevé bancaire']);
  });

  it('un score de 0 est conservé (il n\'est pas remplacé par une autre valeur)', () => {
    expect(mapperResultat({ eligibilityScore: 0, eligibility_score: 55 }).eligibilityScore).toBe(0);
  });

  it('accepte les anciens noms de champs en snake_case', () => {
    const r = mapperResultat({ eligibility_score: 70, financial_metrics: { dti: 1 },
                               recommended_plan: [{ action: 'x' }], document_sources: [{ field: 'f' }], explanation: 'e' });
    expect(r.eligibilityScore).toBe(70);
    expect(r.financialMetrics).toEqual({ dti: 1 });
    expect(r.recommendedPlan.length).toBe(1);
    expect(r.documentSources!.length).toBe(1);
    expect(r.rawExplanation).toBe('e');
  });

  it('scoreProvisoire n\'est vrai que si la valeur est exactement true', () => {
    expect(mapperResultat({ scoreProvisoire: 'true' }).scoreProvisoire).toBe(false);
    expect(mapperResultat({ scoreProvisoire: 1 }).scoreProvisoire).toBe(false);
    expect(mapperResultat({ scoreProvisoire: true }).scoreProvisoire).toBe(true);
  });

  it("conserve l'état de l'envoi de la réponse au client", () => {
    const notification = { statut: 'ENVOYE', destinataire: 'client@example.com', envoyeAt: '2026-10-07T22:10:05' };

    expect(mapperResultat({ notification }).notification).toEqual(notification);
    expect(mapperResultat({}).notification).toBeNull();
    expect(mapperResultat({ notification: null }).notification).toBeNull();
  });
});
