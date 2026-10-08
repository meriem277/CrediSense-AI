import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { environment } from '../../../../environments/environment';

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

describe("CreditResult — chiffres clés", () => {
  let fixture: ComponentFixture<CreditResult>;
  let etat: CreditStateService;

  const afficher = (metriques: Record<string, unknown>) => {
    etat.setResult(mapperResultat({ ...RESULTAT_COMPLET, financialMetrics: metriques }));
    fixture.detectChanges();
  };
  const cases = () => Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.cr-metric'))
    .map(c => ({
      libelle: c.querySelector('.cr-metric-key')?.textContent?.trim() ?? '',
      valeur: c.querySelector('.cr-metric-val')?.textContent?.trim() ?? '',
      manquante: c.classList.contains('cr-metric-manquante'),
    }));
  const valeurDe = (libelle: string) => cases().find(c => c.libelle === libelle)?.valeur;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CreditResult],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(CreditResult);
    etat = TestBed.inject(CreditStateService);
    fixture.detectChanges();
  });

  it("libellés lisibles : plus de noms techniques ni de date d'embauche prise pour un type d'emploi", () => {
    afficher({ clientAge: 25, contractType: 'CDI', employmentStartDate: '02/09/2022', paymentIncidents: 0 });

    expect(valeurDe('Âge du client')).toBe('25 ans');
    expect(valeurDe('Type de contrat')).toBe('CDI');
    expect(valeurDe("Date d'embauche")).toBe('02/09/2022');
    expect(valeurDe('Incidents de paiement')).toBe('0');           // un nombre d'incidents n'est pas un montant
    const texte = (fixture.nativeElement as HTMLElement).textContent!.toUpperCase();
    expect(texte).not.toContain('CLIENTAGE');
    expect(texte).not.toContain('CONTRACTTYPE');
  });

  it("un chiffre calculé qui manque est annoncé « non calculé », jamais 0 ni une case vide", () => {
    afficher({ dti: null, monthlyPayment: null, existingDebts: null, monthlyIncome: 4800 });

    expect(valeurDe("Taux d'endettement")).toBe('Non calculé');
    expect(valeurDe('Mensualité estimée')).toBe('Non calculée');
    expect(valeurDe('Dettes existantes')).toBe('Inconnues');
    expect(valeurDe('Revenu mensuel net')).toContain('4');
    expect(cases().filter(c => c.manquante).length).toBe(3);
  });

  it("une mensualité à 0 est une mensualité qui n'a pas été calculée (ancien résultat enregistré)", () => {
    afficher({ monthlyPayment: 0, requestedAmount: 1200 });

    expect(valeurDe('Mensualité estimée')).toBe('Non calculée');
    expect(valeurDe('Mensualité estimée')).not.toContain('0 TND');
  });

  it("des dettes connues à 0 restent affichées 0 : c'est une vraie valeur", () => {
    afficher({ existingDebts: 0 });

    expect(valeurDe('Dettes existantes')).toContain('0 TND');
    expect(cases()[0].manquante).toBe(false);
  });

  it("un champ absent qui n'est pas un chiffre calculé n'affiche aucune case", () => {
    afficher({ clientAge: null, contractType: null, employmentStartDate: undefined, monthlyIncome: 2100 });

    expect(cases().map(c => c.libelle)).toEqual(['Revenu mensuel net']);
  });

  it("aucune case sans libellé ni sans valeur", () => {
    afficher({ dti: null, monthlyPayment: null, clientAge: 36, duration: 12, requestedAmount: 1200 });

    for (const c of cases()) {
      expect(c.libelle).not.toBe('');
      expect(c.valeur).not.toBe('');
    }
  });

  it("une mensualité calculée s'affiche normalement", () => {
    afficher({ monthlyPayment: 105.498, dti: 22.77 });

    expect(valeurDe('Mensualité estimée')).toContain('TND');
    expect(valeurDe("Taux d'endettement")).toContain('22.77');
    expect(cases().every(c => !c.manquante)).toBe(true);
  });
});

describe("CreditResult — réponse envoyée au client", () => {
  let fixture: ComponentFixture<CreditResult>;
  let etat: CreditStateService;

  const afficher = (notification: unknown) => {
    etat.setResult(mapperResultat({ ...RESULTAT_COMPLET, notification }));
    fixture.detectChanges();
  };
  const texte = () => (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  const banniere = () => fixture.nativeElement.querySelector('.cr-notification') as HTMLElement | null;
  const boutonEnvoi = () => (fixture.nativeElement as HTMLElement).querySelector('.cr-send-btn') as HTMLButtonElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CreditResult],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(CreditResult);
    etat = TestBed.inject(CreditStateService);
    fixture.detectChanges();
  });

  it("envoi automatique réussi : l'agent voit à qui, quand, et que le PDF est joint", () => {
    afficher({ statut: 'ENVOYE', destinataire: 'client@example.com', envoyeAt: '2026-10-07T22:10:05.123', decision: 'ELIGIBLE' });

    expect(banniere()).toBeTruthy();
    expect(banniere()!.className).toContain('cr-notification-ok');
    expect(texte()).toContain('Réponse envoyée au client');
    expect(texte()).toContain('client@example.com');
    expect(texte()).toMatch(/07\/10\/2026.*22:10/);
    expect(texte()).toContain('rapport PDF en pièce jointe');
  });

  it("réponse déjà envoyée : le bouton propose de la renvoyer, pas de l'envoyer", () => {
    afficher({ statut: 'ENVOYE', destinataire: 'client@example.com', envoyeAt: '2026-10-07T22:10:05' });

    expect(boutonEnvoi().textContent).toContain('Renvoyer la réponse au client');
    expect(boutonEnvoi().disabled).toBe(false);
  });

  it("envoi automatique en échec : l'erreur est montrée en rouge, avec la marche à suivre", () => {
    afficher({ statut: 'ECHEC', detail: "L'envoi a échoué : SMTP indisponible" });

    expect(banniere()!.className).toContain('cr-notification-erreur');
    expect(texte()).toContain("L'envoi automatique a échoué");
    expect(texte()).toContain('SMTP indisponible');
    expect(texte()).toContain('Envoyer la réponse au client');
    expect(boutonEnvoi().textContent).toContain('Envoyer la réponse au client');
    expect(boutonEnvoi().textContent).not.toContain('Renvoyer');
  });

  it("client sans adresse, envoi désactivé, décision non définitive : message adapté", () => {
    afficher({ statut: 'NON_ENVOYE', detail: 'Aucun e-mail envoyé : Email client introuvable' });
    expect(texte()).toContain('Réponse non envoyée');
    expect(texte()).toContain('Email client introuvable');

    afficher({ statut: 'DESACTIVE', detail: "L'envoi automatique est désactivé : utilisez « Envoyer la réponse au client »." });
    expect(texte()).toContain('Envoi automatique désactivé');

    afficher({ statut: 'AUCUN', detail: 'Décision non définitive : aucun e-mail envoyé au client.' });
    expect(texte()).toContain('Aucun e-mail envoyé');
    expect(banniere()!.className).toContain('cr-notification-info');
  });

  it("aucune notification : pas de bandeau, et le bouton reste « Envoyer la réponse au client »", () => {
    afficher(null);

    expect(banniere()).toBeNull();
    expect(boutonEnvoi().textContent).toContain('Envoyer la réponse au client');
  });

  it("une date illisible n'empêche pas d'afficher l'envoi", () => {
    afficher({ statut: 'ENVOYE', destinataire: 'client@example.com', envoyeAt: 'pas une date' });

    expect(texte()).toContain('E-mail envoyé automatiquement à client@example.com');
    expect(texte()).not.toContain('Invalid');
  });

  it("le bouton d'export reçoit le dossier ouvert", () => {
    fixture.componentRef.setInput('dossierId', 'dossier-42');
    afficher({ statut: 'ENVOYE', destinataire: 'client@example.com' });

    const export_ = fixture.debugElement.query((e) => e.name === 'app-export-button');
    expect(export_).toBeTruthy();
    expect(export_.componentInstance.dossierId).toBe('dossier-42');
  });
});

describe("CreditResult — validation avant envoi et journal", () => {
  let fixture: ComponentFixture<CreditResult>;
  let etat: CreditStateService;
  let http: HttpTestingController;

  const REFUS = {
    ...RESULTAT_COMPLET, eligibility: 'REFUS', eligibilityScore: 30,
    summary: 'Taux d\'endettement trop élevé.',
  };
  const EN_ATTENTE = { statut: 'EN_ATTENTE_VALIDATION', destinataire: 'client@example.com', decision: 'REFUS',
                       detail: 'Cette réponse attend votre validation avant d\'être envoyée au client.' };
  const PROGRAMME = { statut: 'PROGRAMME', destinataire: 'client@example.com', decision: 'ELIGIBLE',
                      programmeA: '2026-10-07T14:35:00', detail: 'Envoi prévu à 14:35. Vous pouvez l\'envoyer maintenant ou l\'annuler.' };

  const afficher = (resultat: any, notification: unknown) => {
    etat.setResult(mapperResultat({ ...resultat, notification }));
    fixture.detectChanges();
  };
  const texte = () => (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  const requete = (s: string) => (fixture.nativeElement as HTMLElement).querySelector(s) as HTMLElement | null;
  const bouton = (classe: string) => requete(`button.${classe}`) as HTMLButtonElement;
  const url = (suffixe: string) => `${environment.apiUrl}/api/dossiers/dossier-1/${suffixe}`;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CreditResult],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(CreditResult);
    fixture.componentRef.setInput('dossierId', 'dossier-1');
    etat = TestBed.inject(CreditStateService);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    // le résultat enregistré et le journal demandés à l'ouverture : on les ignore, sauf dans les tests dédiés
    http.match(() => true);
  });

  afterEach(() => TestBed.resetTestingModule());

  // ── Réponse en attente de validation ───────────────────────────────────────

  it("un refus en attente : bandeau ambre, boutons « Valider et envoyer » et « Ne pas envoyer »", () => {
    afficher(REFUS, EN_ATTENTE);

    expect(requete('.cr-notification')!.className).toContain('cr-notification-attente');
    expect(texte()).toContain('Réponse en attente de votre validation');
    expect(texte()).toContain('Le client ne recevra rien tant que vous n\'avez pas validé');
    expect(texte()).toContain('client@example.com');
    expect(bouton('cr-valider').textContent).toContain('Valider et envoyer');
    expect(bouton('cr-annuler').textContent).toContain('Ne pas envoyer');
  });

  it("pendant l'attente, le bouton d'envoi direct disparaît : on ne contourne pas la validation", () => {
    afficher(REFUS, EN_ATTENTE);

    expect(requete('.cr-send-btn')).toBeNull();
  });

  it("valider envoie la décision LUE à l'écran et le bandeau passe à « envoyé »", () => {
    afficher(REFUS, EN_ATTENTE);

    bouton('cr-valider').click();
    const r = http.expectOne(url('reponse/valider'));
    expect(r.request.method).toBe('POST');
    expect(r.request.body).toEqual({ decision: 'REFUS' });
    r.flush({ success: true, notification: { statut: 'ENVOYE', destinataire: 'client@example.com',
                                              envoyeAt: '2026-10-07T22:10:00', mode: 'VALIDATION', acteur: 'agent@attijari.com' } });
    fixture.detectChanges();

    expect(requete('.cr-notification')!.className).toContain('cr-notification-ok');
    expect(texte()).toContain('Réponse envoyée au client');
    expect(requete('.cr-validation')).toBeNull();
    expect(requete('.cr-send-btn')!.textContent).toContain('Renvoyer la réponse au client');
    http.match(() => true);                                   // le journal est relu
  });

  it("les boutons sont bloqués pendant la requête, pas de double validation", () => {
    afficher(REFUS, EN_ATTENTE);

    bouton('cr-valider').click();
    fixture.detectChanges();
    expect(bouton('cr-valider').disabled).toBe(true);
    expect(bouton('cr-annuler').disabled).toBe(true);
    expect(bouton('cr-valider').textContent).toContain('Envoi');

    fixture.componentInstance.validerEnvoi();                  // second appel ignoré
    http.expectOne(url('reponse/valider')).flush({ success: true, notification: { statut: 'ENVOYE' } });
    http.match(() => true);
  });

  it("une décision qui a changé entre-temps (409) : message du serveur, et le dossier est relu", () => {
    afficher(REFUS, EN_ATTENTE);

    bouton('cr-valider').click();
    http.expectOne(url('reponse/valider')).flush(
      { success: false, error: 'La décision a changé depuis votre lecture (elle est maintenant « ELIGIBLE »).' },
      { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(texte()).toContain('La décision a changé depuis votre lecture');
    http.expectOne(`${environment.apiUrl}/api/dossiers/dossier-1/resultat`);   // état réel rechargé
    http.match(() => true);
  });

  it("panne serveur à la validation : message clair, on peut réessayer", () => {
    afficher(REFUS, EN_ATTENTE);

    bouton('cr-valider').click();
    http.expectOne(url('reponse/valider')).error(new ProgressEvent('error'), { status: 500, statusText: 'Erreur' });
    fixture.detectChanges();

    expect(texte()).toContain('La réponse n\'a pas pu être validée');
    expect(bouton('cr-valider').disabled).toBe(false);
  });

  it("validation prise en compte mais envoi échoué : le bandeau devient rouge avec la raison", () => {
    afficher(REFUS, EN_ATTENTE);

    bouton('cr-valider').click();
    http.expectOne(url('reponse/valider')).flush({
      success: false, message: 'x',
      notification: { statut: 'ECHEC', detail: 'L\'envoi a échoué : SMTP indisponible' } });
    fixture.detectChanges();

    expect(requete('.cr-notification')!.className).toContain('cr-notification-erreur');
    expect(texte()).toContain('SMTP indisponible');
    http.match(() => true);
  });

  // ── Ne pas envoyer ─────────────────────────────────────────────────────────

  it("« Ne pas envoyer » : le bandeau dit que le client n'a rien reçu", () => {
    afficher(REFUS, EN_ATTENTE);

    bouton('cr-annuler').click();
    const r = http.expectOne(url('reponse/annuler'));
    expect(r.request.method).toBe('POST');
    r.flush({ success: true, notification: { statut: 'ANNULE', acteur: 'agent@attijari.com',
                                              detail: 'Envoi annulé par agent@attijari.com : le client n\'a rien reçu.' } });
    fixture.detectChanges();

    expect(texte()).toContain('Envoi annulé');
    expect(texte()).toContain('le client n\'a rien reçu');
    expect(requete('.cr-validation')).toBeNull();
    http.match(() => true);
  });

  it("annulation refusée (409, déjà traitée) : message du serveur", () => {
    afficher(REFUS, EN_ATTENTE);

    bouton('cr-annuler').click();
    http.expectOne(url('reponse/annuler')).flush({ success: false, error: 'Rien à annuler' },
                                                  { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(texte()).toContain('Rien à annuler');
    http.match(() => true);
  });

  // ── Envoi différé ──────────────────────────────────────────────────────────

  it("envoi différé : l'heure prévue, « Envoyer maintenant » et « Annuler l'envoi »", () => {
    afficher({ ...RESULTAT_COMPLET }, PROGRAMME);

    expect(texte()).toContain('Envoi programmé');
    expect(texte()).toContain('Envoi prévu à 14:35');
    expect(bouton('cr-valider').textContent).toContain('Envoyer maintenant');
    expect(bouton('cr-annuler').textContent).toContain('Annuler l\'envoi');
    expect(requete('.cr-send-btn')).toBeNull();
  });

  it("« Envoyer maintenant » passe par la même validation que « Valider et envoyer »", () => {
    afficher({ ...RESULTAT_COMPLET }, PROGRAMME);

    bouton('cr-valider').click();
    const r = http.expectOne(url('reponse/valider'));
    expect(r.request.body).toEqual({ decision: 'ELIGIBLE' });
    r.flush({ success: true, notification: { statut: 'ENVOYE', destinataire: 'client@example.com' } });
    http.match(() => true);
  });

  // ── Les autres états ne montrent pas de boutons de validation ──────────────

  it("réponse envoyée, annulée, en échec ou désactivée : aucun bouton de validation", () => {
    for (const statut of ['ENVOYE', 'ANNULE', 'ECHEC', 'NON_ENVOYE', 'DESACTIVE', 'AUCUN']) {
      afficher(REFUS, { statut, detail: 'x' });
      expect(requete('.cr-validation'), statut).toBeNull();
    }
  });

  it("valider sans réponse en attente ne fait rien", () => {
    afficher(REFUS, { statut: 'ENVOYE', destinataire: 'client@example.com' });

    fixture.componentInstance.validerEnvoi();
    fixture.componentInstance.annulerEnvoi();

    http.expectNone(url('reponse/valider'));
    http.expectNone(url('reponse/annuler'));
  });

  // ── Journal du dossier ─────────────────────────────────────────────────────

  const JOURNAL = [
    { id: '1', date: '2026-10-07T22:10:05', type: 'ANALYSE', acteur: 'agent@attijari.com', decision: 'REFUS', score: 30,
      versionRegles: '2026-10-a', libelle: 'Analyse terminée : décision REFUS, score 30/100 (IA : groq)' },
    { id: '2', date: '2026-10-07T22:10:06', type: 'EMAIL_EN_ATTENTE', acteur: 'SYSTEME', decision: 'REFUS',
      libelle: 'Réponse REFUS mise en attente : une réponse REFUS doit être validée par un agent avant l\'envoi' },
    { id: '3', date: '2026-10-07T22:30:00', type: 'EMAIL_ENVOYE', acteur: 'agent@attijari.com', decision: 'REFUS',
      libelle: 'Réponse REFUS envoyée à client@example.com (après validation), rapport PDF joint' },
  ];

  it("le journal est demandé pour le dossier dès qu'un résultat est affiché", () => {
    afficher(REFUS, EN_ATTENTE);

    const requetes = http.match(`${environment.apiUrl}/api/dossiers/dossier-1/audit`);
    expect(requetes.length).toBeGreaterThan(0);
    expect(requetes[0].request.method).toBe('GET');
  });

  it("la frise montre pour chaque événement la date, l'acteur, la phrase et la version des règles", () => {
    afficher(REFUS, EN_ATTENTE);
    fixture.componentInstance.chargerJournal();
    http.match(`${environment.apiUrl}/api/dossiers/dossier-1/audit`).forEach(r => r.flush(JOURNAL));
    fixture.detectChanges();

    expect(requete('.cr-journal summary')!.textContent).toContain('Journal du dossier (3)');
    const lignes = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.cr-journal li'));
    expect(lignes.length).toBe(3);

    const premiere = lignes[0].textContent!.replace(/\s+/g, ' ');
    expect(premiere).toContain('07/10/2026');
    expect(premiere).toContain('22:10');
    expect(premiere).toContain('agent@attijari.com');
    expect(premiere).toContain('Analyse terminée : décision REFUS, score 30/100');
    expect(premiere).toContain('règles 2026-10-a');

    const deuxieme = lignes[1].textContent!.replace(/\s+/g, ' ');
    expect(deuxieme).toContain('Système');                       // jamais le code interne « SYSTEME »
    expect(deuxieme).not.toContain('règles');                    // pas de version quand il n'y en a pas
    expect(lignes[2].textContent).toContain('après validation');
  });

  it("sans événement, pas de journal affiché", () => {
    afficher(REFUS, EN_ATTENTE);
    http.match(() => true).forEach(r => { if (r.request.url.endsWith('/audit')) r.flush([]); });
    fixture.detectChanges();

    expect(requete('.cr-journal')).toBeNull();
  });

  it("un journal indisponible n'empêche pas de travailler", () => {
    afficher(REFUS, EN_ATTENTE);
    http.match(() => true).forEach(r => {
      if (r.request.url.endsWith('/audit')) r.error(new ProgressEvent('error'), { status: 500, statusText: 'Erreur' });
    });
    fixture.detectChanges();

    expect(requete('.cr-journal')).toBeNull();
    expect(bouton('cr-valider')).toBeTruthy();                   // le reste fonctionne
  });

  it("une réponse du serveur qui n'est pas une liste est ignorée", () => {
    afficher(REFUS, EN_ATTENTE);
    http.match(() => true).forEach(r => { if (r.request.url.endsWith('/audit')) r.flush({ pas: 'une liste' }); });
    fixture.detectChanges();

    expect(fixture.componentInstance.journal).toEqual([]);
  });

  it("aides de présentation du journal", () => {
    const c = fixture.componentInstance;

    expect(c.acteurLisible('SYSTEME')).toBe('Système');
    expect(c.acteurLisible(null)).toBe('Système');
    expect(c.acteurLisible('agent@attijari.com')).toBe('agent@attijari.com');
    expect(c.dateJournal('2026-10-07T22:10:05')).toMatch(/07\/10\/2026.*22:10/);
    expect(c.dateJournal('pas une date')).toBe('');
    expect(c.dateJournal(null)).toBe('');
  });
});

describe("CreditResult — propositions d'ajustement", () => {
  let fixture: ComponentFixture<CreditResult>;
  let etat: CreditStateService;

  const PROPOSITIONS = {
    applicable: true,
    message: "Propositions indicatives, calculées par les règles, sous réserve de validation par l'agent.",
    unresolved: [],
    offers: [
      { kind: 'MONTANT_REDUIT', label: 'Montant réduit, même durée', amount: 6800, duration: 12,
        monthlyPayment: 597.828, dti: 29.89, totalCost: 7173.936, explanation: 'Sur 12 mois, 6 800 DT est le montant le plus élevé.' },
      { kind: 'DUREE_ALLONGEE', label: 'Même montant, durée allongée', amount: 10000, duration: 24,
        monthlyPayment: 461.449, dti: 23.07, totalCost: 11074.782, explanation: 'En allongeant à 24 mois, le montant passe.' },
    ],
  };
  const CONDITIONNEL = { ...RESULTAT_COMPLET, eligibility: 'CONDITIONNEL', eligibilityScore: 55, adjustedOffers: PROPOSITIONS };

  const afficher = (resultat: unknown) => { etat.setResult(mapperResultat(resultat)); fixture.detectChanges(); };
  const texte = () => (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  const requete = (s: string) => (fixture.nativeElement as HTMLElement).querySelectorAll(s);

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CreditResult],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    fixture = TestBed.createComponent(CreditResult);
    etat = TestBed.inject(CreditStateService);
    fixture.detectChanges();
  });

  it("affiche chaque proposition avec son montant, sa durée et son taux d'endettement", () => {
    afficher(CONDITIONNEL);
    expect(requete('.cr-table-offres tbody tr').length).toBe(2);
    const t = texte();
    expect(t).toContain("Propositions d'ajustement");
    expect(t).toContain('Montant réduit, même durée');
    expect(t).toContain('Même montant, durée allongée');
    expect(t).toContain('24 mois');
    expect(t).toContain('29.89 %');
  });

  it('rappelle que les propositions sont indicatives et soumises à validation', () => {
    afficher(CONDITIONNEL);
    expect(texte()).toContain('sous réserve de validation');
  });

  it('quand rien ne peut être ajusté, affiche le message sans tableau', () => {
    afficher({ ...CONDITIONNEL, adjustedOffers: {
      applicable: false, offers: [], unresolved: ["Ancienneté dans l'emploi"],
      message: "L'endettement, le plafond et la durée sont déjà respectés : changer le montant ou la durée ne suffit pas." } });
    expect(requete('.cr-table-offres').length).toBe(0);
    expect(texte()).toContain('déjà respectés');
  });

  it("n'affiche aucun panneau pour une décision sans propositions", () => {
    afficher(RESULTAT_COMPLET);
    expect(requete('.cr-offres').length).toBe(0);
    expect(texte()).not.toContain("Propositions d'ajustement");
  });

  it('conserve les propositions lors de la conversion du résultat', () => {
    expect(mapperResultat(CONDITIONNEL).adjustedOffers?.offers.length).toBe(2);
    expect(mapperResultat(RESULTAT_COMPLET).adjustedOffers).toBeUndefined();
  });
});


describe("CreditResult — présentation visuelle", () => {
  let fixture: ComponentFixture<CreditResult>;
  let etat: CreditStateService;

  const afficher = (resultat: unknown) => { etat.setResult(mapperResultat(resultat)); fixture.detectChanges(); };
  const composant = () => fixture.componentInstance;
  const requete = (s: string) => (fixture.nativeElement as HTMLElement).querySelectorAll(s);
  const sansEspaces = (t: string) => t.replace(/\s/g, "");
  const avecDti = (dti: number | null) => ({ ...RESULTAT_COMPLET, financialMetrics: { ...RESULTAT_COMPLET.financialMetrics, dti } });

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CreditResult],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    fixture = TestBed.createComponent(CreditResult);
    etat = TestBed.inject(CreditStateService);
    fixture.detectChanges();
  });

  it("dessine l'arc du score proportionnellement au score", () => {
    afficher({ ...RESULTAT_COMPLET, eligibilityScore: 50 });
    const [arc, circonference] = composant().scoreTrait.split(" ").map(Number);
    expect(arc / circonference).toBeCloseTo(0.5, 5);
    expect(requete(".cr-score-centre strong")[0].textContent).toContain("50");
  });

  it("borne l'arc du score entre 0 et 100", () => {
    afficher({ ...RESULTAT_COMPLET, eligibilityScore: 140 });
    const [arc, circonference] = composant().scoreTrait.split(" ").map(Number);
    expect(arc).toBeCloseTo(circonference, 5);
  });

  it("classe le taux d'endettement selon les seuils du moteur", () => {
    afficher(avecDti(25));
    expect(composant().dtiStatut).toBe("ok");
    afficher(avecDti(32));
    expect(composant().dtiStatut).toBe("warn");
    afficher(avecDti(36.63));
    expect(composant().dtiStatut).toBe("ko");
    expect(composant().dtiMessage).toContain("au-dessus du maximum de 35 %");
  });

  it("place le repère sur la règle graduée et le plafonne à la fin de l'échelle", () => {
    afficher(avecDti(25));
    expect(composant().dtiPosition).toBeCloseTo(50, 5);          // 25 % sur une échelle de 0 à 50 %
    afficher(avecDti(80));
    expect(composant().dtiPosition).toBe(100);
  });

  it("n'affiche pas la règle d'endettement quand le taux n'est pas calculé", () => {
    afficher({ ...avecDti(0), capacity: undefined });
    expect(requete(".cr-dti").length).toBe(0);
    expect(requete(".dti-repere").length).toBe(0);
  });

  it("affiche les contrôles en cartes avec une icône et un bilan chiffré", () => {
    afficher(RESULTAT_COMPLET);
    expect(requete(".controle").length).toBe(3);
    expect(requete(".controle-icone")[0].textContent).toContain("✓");        // conforme
    expect(requete(".controle-icone")[2].textContent).toContain("?");        // à vérifier
    expect(composant().compteursControles).toEqual({ ok: 2, warn: 0, ko: 0, todo: 1 });
    expect(requete(".cr-bilan")[0].textContent).toContain("2 conformes");
  });

  it("garde le tableau des contrôles, replié, comme vue de remplacement", () => {
    afficher(RESULTAT_COMPLET);
    const vue = requete("details.cr-vue-tableau")[0] as HTMLDetailsElement;
    expect(vue.open).toBe(false);
    expect(vue.querySelectorAll("tbody tr").length).toBe(3);
  });

  it("compare la mensualité demandée à la capacité et signale le dépassement", () => {
    afficher({ ...RESULTAT_COMPLET,
      financialMetrics: { ...RESULTAT_COMPLET.financialMetrics, dti: 36.63, monthlyPayment: 1758.318 },
      capacity: { ...RESULTAT_COMPLET.capacity, maxMonthlyPayment: 1440, remainingMonthly: -318.318 } });
    expect(composant().depasseLaCapacite).toBe(true);
    expect(composant().largeurComparaison(1440)).toBe(82);                    // 1 440 ÷ 1 758 de la barre pleine
    expect(requete(".comp-barre.depasse").length).toBe(1);
    expect(requete(".cr-capacity-figures .negatif").length).toBe(1);          // « Reste après la demande » négatif
  });

  it("ne signale pas de dépassement quand la mensualité tient dans la capacité", () => {
    afficher({ ...RESULTAT_COMPLET,
      financialMetrics: { ...RESULTAT_COMPLET.financialMetrics, monthlyPayment: 228.3 },
      capacity: { ...RESULTAT_COMPLET.capacity, maxMonthlyPayment: 380 } });
    expect(composant().depasseLaCapacite).toBe(false);
    expect(requete(".comp-barre.depasse").length).toBe(0);
  });

  it("décrit l'écart de chaque proposition par rapport à la demande", () => {
    afficher({ ...RESULTAT_COMPLET, financialMetrics: { ...RESULTAT_COMPLET.financialMetrics, requestedAmount: 20000, duration: 12 } });
    expect(sansEspaces(composant().ecartMontant(16300))).toBe(sansEspaces("−3 700 DT par rapport à la demande"));
    expect(composant().ecartMontant(20000)).toBe("montant demandé conservé");
    expect(composant().ecartDuree(18)).toBe("+6 mois");
    expect(composant().ecartDuree(12)).toBe("durée demandée conservée");
  });

  it("n'invente aucun écart quand la demande est inconnue", () => {
    afficher({ ...RESULTAT_COMPLET, financialMetrics: { dti: 20 } });
    expect(composant().ecartMontant(16300)).toBe("");
    expect(composant().ecartDuree(18)).toBe("");
  });

  it("trace une colonne par durée simulée et repère la durée demandée", () => {
    afficher(RESULTAT_COMPLET);
    expect(requete(".sim-col").length).toBe(2);
    expect(requete(".sim-col.demandee").length).toBe(1);
    expect(requete(".sim-ligne").length).toBe(2);                              // seuils de 30 % et de 35 %
    expect(composant().hauteurDti(25)).toBeCloseTo(50, 5);
    expect(composant().hauteurDti(90)).toBe(100);                              // plafonnée à l'échelle
    expect(composant().hauteurDti(-3)).toBe(0);
  });

  it("replie l'analyse rédigée par l'IA et la présente comme indicative", () => {
    afficher({ ...RESULTAT_COMPLET, rawExplanation: "Texte rédigé par le modèle." });
    const analyse = requete("details.cr-explication-section")[0] as HTMLDetailsElement;
    expect(analyse.open).toBe(false);
    expect(analyse.textContent).toContain("indicative");
    expect(analyse.textContent).toContain("Texte rédigé par le modèle.");
  });

  it("formate un pourcentage à la française", () => {
    expect(composant().pct(36.63)).toBe("36,63 %");
    expect(composant().pct(null)).toBe("—");
  });
});


describe("CreditResult — réponse du client aux propositions", () => {
  let fixture: ComponentFixture<CreditResult>;
  let etat: CreditStateService;

  const OFFRE = { kind: 'DUREE_ALLONGEE', label: 'Même montant, durée allongée', amount: 20000, duration: 18,
                  monthlyPayment: 1201.142, dti: 25.02, totalCost: 21620.549, explanation: 'x' };
  const CONDITIONNEL = { ...RESULTAT_COMPLET, eligibility: 'CONDITIONNEL', eligibilityScore: 55 };

  const afficher = (resultat: unknown) => { etat.setResult(mapperResultat(resultat)); fixture.detectChanges(); };
  const texte = () => (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, " ");
  const bandeau = () => (fixture.nativeElement as HTMLElement).querySelector(".cr-reponse-client") as HTMLElement | null;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CreditResult],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    fixture = TestBed.createComponent(CreditResult);
    etat = TestBed.inject(CreditStateService);
    fixture.detectChanges();
  });

  it("montre à l'agent l'offre acceptée par le client", () => {
    afficher({ ...CONDITIONNEL, reponseClient: { statut: 'ACCEPTEE', repondueLe: '2026-10-08T10:12:00', offre: OFFRE } });
    expect(bandeau()).not.toBeNull();
    const t = bandeau()!.textContent!.replace(/\s+/g, " ");
    expect(t).toContain("Le client a accepté une proposition");
    expect(t).toContain("Même montant, durée allongée");
    expect(t).toContain("18 mois");
    expect(t).toContain("25,02 %");
    expect(t).toContain("08/10/2026");
    expect(bandeau()!.className).toContain("cr-reponse-ACCEPTEE");
  });

  it("précise que le dossier n'est pas modifié automatiquement", () => {
    afficher({ ...CONDITIONNEL, reponseClient: { statut: 'ACCEPTEE', repondueLe: '2026-10-08T10:12:00', offre: OFFRE } });
    expect(bandeau()!.textContent).toContain("n'est pas modifié automatiquement");
  });

  it("signale le refus des propositions", () => {
    afficher({ ...CONDITIONNEL, reponseClient: { statut: 'REFUSEE', repondueLe: '2026-10-08T10:12:00', offre: null } });
    const t = bandeau()!.textContent!.replace(/\s+/g, " ");
    expect(t).toContain("Le client a refusé les propositions");
    expect(t).toContain("à vous de le recontacter");
    expect(bandeau()!.className).toContain("cr-reponse-REFUSEE");
  });

  it("n'affiche rien tant que le client n'a pas répondu", () => {
    afficher(CONDITIONNEL);
    expect(bandeau()).toBeNull();
    afficher({ ...CONDITIONNEL, reponseClient: null });
    expect(bandeau()).toBeNull();
    expect(texte()).not.toContain("Le client a");
  });

  it("conserve la réponse du client lors de la conversion du résultat", () => {
    const r = mapperResultat({ ...CONDITIONNEL, reponseClient: { statut: 'REFUSEE', repondueLe: null } });
    expect(r.reponseClient?.statut).toBe('REFUSEE');
    expect(mapperResultat(CONDITIONNEL).reponseClient).toBeNull();
  });
});
