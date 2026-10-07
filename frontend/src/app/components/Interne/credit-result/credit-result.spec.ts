import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';

import { CreditResult } from './credit-result';
import { CreditStateService } from '../../../services/credit-state.service';
import { mapperResultat } from '../../../models/credit-analysis-result.model';

/** Résultat complet et favorable, tel que le renvoie le service IA. */
const RESULTAT_COMPLET = {
  eligibility: 'ELIGIBLE', eligibilityScore: 78, creditType: 'CONSOMMATION',
  summary: 'Dossier solide, capacité de remboursement confortable.',
  financialMetrics: { dti: 22.8, monthlyIncome: 2100 },
  regulatoryChecks: [
    { criterion: "Taux d'endettement", status: 'OK', value: '22.80 %', threshold: '< 30 %', explanation: 'Sous le seuil.', blocking: true },
    { criterion: 'Ancienneté dans l\'emploi', status: 'OK', value: '5 an(s) 1 mois', threshold: '≥ 6 mois', explanation: 'Stable.', blocking: true },
    { criterion: 'Incidents de paiement', status: 'A_VERIFIER', value: 'inconnu', threshold: '0', explanation: 'À vérifier à la centrale des risques.', blocking: false },
  ],
  capacity: { maxMonthlyPayment: 380, remainingMonthly: 151.7, maxAmountForDuration: 14000, referenceDuration: 48, explanation: 'Vous pouvez emprunter jusqu\'à 14 000 DT.' },
  simulations: [
    { duration: 36, monthlyPayment: 290.4, dti: 25.7, totalCost: 10454, status: 'OK', isRequested: false },
    { duration: 48, monthlyPayment: 228.3, dti: 22.8, totalCost: 10958, status: 'OK', isRequested: true },
  ],
  strengths: [{ title: 'CDI depuis 5 ans', detail: 'Emploi stable.', source: 'Attestation de travail' }],
  weaknesses: ['Aucune épargne visible'],
  conditions: ['Assurance décès-invalidité'],
  calculationNote: 'Mensualités calculées avec un taux annuel de 10.00 % (configuration du service).',
  risks: [], recommendedPlan: [], documentSources: [], rawExplanation: 'Analyse détaillée.',
};

/** Dossier sans relevé bancaire : aucune décision n'est rendue. */
const RESULTAT_A_COMPLETER = {
  eligibility: 'A_COMPLETER', eligibilityScore: 59, scoreProvisoire: true, creditType: 'CONSOMMATION',
  summary: 'Décision impossible pour l\'instant : des informations indispensables manquent (Dettes existantes).',
  donneesManquantes: ['Dettes existantes — relevé bancaire des 3 derniers mois'],
  financialMetrics: { dti: null }, regulatoryChecks: [], simulations: [], risks: [], recommendedPlan: [],
  rawExplanation: 'Pour finaliser l\'étude de votre dossier, il manque : Dettes existantes.',
};

describe('CreditResult — affichage', () => {
  let fixture: ComponentFixture<CreditResult>;
  let etat: CreditStateService;

  const afficher = (resultat: unknown) => {
    etat.setResult(mapperResultat(resultat));
    fixture.detectChanges();
  };
  const texte = () => (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  const requete = (selecteur: string) => (fixture.nativeElement as HTMLElement).querySelectorAll(selecteur);

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CreditResult],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(CreditResult);
    etat = TestBed.inject(CreditStateService);
    fixture.detectChanges();
  });

  it('résultat complet : affiche tout ce que le moteur calcule', () => {
    afficher(RESULTAT_COMPLET);

    expect(requete('.cr-badge')[0].textContent).toContain('ÉLIGIBLE');
    expect(texte()).toContain('Dossier solide, capacité de remboursement confortable.');   // résumé
    expect(texte()).toContain('Contrôles réglementaires');
    expect(requete('.cr-table tbody tr').length).toBe(3 + 2);        // 3 contrôles + 2 simulations
    expect(texte()).toContain('Conforme');
    expect(texte()).toContain('À vérifier');
    expect(texte()).toContain('Capacité d\'emprunt');
    expect(texte()).toContain('380,000 DT');                          // formaté en millimes
    expect(texte()).toContain('Simulation par durée');
    expect(requete('.cr-row-demandee').length).toBe(1);               // la durée demandée est repérée
    expect(texte()).toContain('demandée');
    expect(texte()).toContain('Points forts');
    expect(texte()).toContain('CDI depuis 5 ans');
    expect(texte()).toContain('Points de vigilance');
    expect(texte()).toContain('Aucune épargne visible');             // point de vigilance écrit en texte simple
    expect(texte()).toContain('Conditions avant décaissement');
    expect(texte()).toContain('Mensualités calculées avec un taux annuel de 10.00 %');
  });

  it('résultat complet : aucun bloc « à compléter » ni « provisoire »', () => {
    afficher(RESULTAT_COMPLET);

    expect(requete('.cr-donnees-manquantes').length).toBe(0);
    expect(requete('.cr-provisoire').length).toBe(0);
  });

  it('dossier à compléter : badge, liste des manques, score provisoire, rien d\'inventé', () => {
    afficher(RESULTAT_A_COMPLETER);

    expect(requete('.cr-badge')[0].textContent).toContain('À COMPLÉTER');
    expect(requete('.cr-badge')[0].className).toContain('color-acompleter');
    expect(requete('.cr-donnees-manquantes').length).toBe(1);
    expect(texte()).toContain('Dettes existantes — relevé bancaire des 3 derniers mois');
    expect(requete('.cr-provisoire').length).toBe(1);
    expect(texte()).not.toContain('A_COMPLETER');                    // le code interne ne s'affiche jamais
    // Rien n'est calculé : pas de tableaux vides
    expect(requete('.cr-table').length).toBe(0);
    expect(texte()).not.toContain('Capacité d\'emprunt');
  });

  it('les getters d\'affichage', () => {
    const c = fixture.componentInstance;
    expect(c.controleLabel('KO')).toBe('Non conforme');
    expect(c.controleLabel('A_VERIFIER')).toBe('À vérifier');
    expect(c.controleClass('ATTENTION')).toBe('ctl-warn');
    expect(c.formatDT(2100)).toContain('2');
    expect(c.formatDT(2100)).toContain('100,000');
    expect(c.formatDT(null)).toBe('—');
    expect(c.pointTitre('Texte simple')).toBe('Texte simple');
    expect(c.pointTitre({ title: 'T', detail: 'D' })).toBe('T');
    expect(c.pointDetail({ title: 'T', detail: 'D' })).toBe('D');
    expect(c.pointDetail('Texte simple')).toBe('');
  });
});
