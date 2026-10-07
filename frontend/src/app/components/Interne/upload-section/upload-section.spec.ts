import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { UploadSection } from './upload-section';
import { environment } from '../../../../environments/environment';

/** Document classé par les règles, en désaccord avec l'emplacement choisi par le client. */
const FICHIER_EN_CONFLIT = {
  nomOriginal: 'FICHE_PAIE.pdf',
  typeDocument: 'FICHE_PAIE',
  typeDetecte: 'RELEVE_BANCAIRE',
  typeConflit: true,
  verifie: true,
  cinCoherent: true,
  jsonData: { cin: '12015060' },
  classification: {
    type_document: 'RELEVE_BANCAIRE',
    confiance: 0.9,
    methode: 'regles',
    controle: { fiable: true, concordant: false, typeRetenu: 'RELEVE_BANCAIRE' },
    trace: [
      { niveau: 'regles', decisif: true, type: 'RELEVE_BANCAIRE', score: 20,
        mots_cles: ['releve de compte', 'ancien solde'], raison: 'verdict net',
        seuil_score: 5, seuil_ecart: 3,
        classement: [{ type: 'RELEVE_BANCAIRE', score: 20 }, { type: 'JUSTIFICATIF_DOMICILE', score: 2 }] },
    ],
  },
};

/** Document passé par les trois niveaux : le LLM a tranché. */
const FICHIER_AVEC_LLM = {
  nomOriginal: 'attestation.pdf',
  typeDocument: 'ATTESTATION_EMPLOI',
  typeDetecte: 'ATTESTATION_EMPLOI',
  typeConflit: false,
  classification: {
    type_document: 'ATTESTATION_EMPLOI',
    confiance: 0.85,
    methode: 'llm_fallback',
    controle: { fiable: true, concordant: true, typeRetenu: 'ATTESTATION_EMPLOI' },
    trace: [
      { niveau: 'regles', decisif: false, raison: 'score trop bas (3 : 5 requis)', mots_cles: [], classement: [] },
      { niveau: 'embeddings', decisif: false, type: 'CIN', confiance: 0.41, seuil: 0.55,
        top3: [{ label: 'CIN', score: 0.41 }], raison: 'zone grise, le LLM est consulté' },
      { niveau: 'llm', decisif: true, echec: false, type: 'ATTESTATION_EMPLOI', confiance: 0.85,
        justification: 'attestation d\'un employeur', fournisseur: 'groq', raison: 'le modèle de langage a tranché' },
    ],
  },
};

describe('UploadSection — détail de la classification', () => {
  let component: UploadSection;
  let fixture: ComponentFixture<UploadSection>;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UploadSection],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(UploadSection);
    component = fixture.componentInstance;
    component.cin = '12015060';
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const texte = () => (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // ── Verdict ────────────────────────────────────────────────────────────────

  it('verdict : contradiction, conforme, sans conclusion, complété, pas classé', () => {
    expect(component.verdictClassification(FICHIER_EN_CONFLIT).code).toBe('conflit');
    expect(component.verdictClassification(FICHIER_AVEC_LLM).code).toBe('concordant');
    expect(component.verdictClassification({ typeDetecte: 'CIN', typeConflit: null,
      classification: { controle: { fiable: false } } }).code).toBe('incertain');
    expect(component.verdictClassification({ typeDetecte: 'CIN', typeConflit: null,
      classification: { controle: { fiable: true } } }).code).toBe('complete');
    expect(component.verdictClassification({ typeDocument: 'CIN' }).code).toBe('non_classe');
  });

  it('l\'explication d\'une contradiction nomme les deux types', () => {
    const e = component.verdictClassification(FICHIER_EN_CONFLIT).explication;

    expect(e).toContain('Relevé bancaire');
    expect(e).toContain('Fiche de paie');
  });

  // ── Ligne de résumé ────────────────────────────────────────────────────────

  it('résumé : type détecté, méthode et confiance en pourcentage pour les règles', () => {
    const r = component.resumeClassification(FICHIER_EN_CONFLIT);

    expect(r).toContain('Détecté : Relevé bancaire');
    expect(r).toContain('règles par mots-clés');
    expect(r).toContain('confiance 90 %');
  });

  it('résumé : une similarité d\'embeddings n\'est pas présentée comme un pourcentage', () => {
    const r = component.resumeClassification({
      typeDetecte: 'CIN', typeConflit: false,
      classification: { methode: 'embeddings', confiance: 0.56 },
    });

    expect(r).toContain('similarité 0,56');
    expect(r).not.toContain('%');
  });

  it('résumé d\'un document pas encore classé', () => {
    expect(component.resumeClassification({ typeDocument: 'CIN' })).toContain('en attente');
  });

  // ── Cascade ────────────────────────────────────────────────────────────────

  it('cascade : les règles ont tranché, les deux autres niveaux sont « non consultés »', () => {
    const niveaux = component.niveauxCascade(FICHIER_EN_CONFLIT);

    expect(niveaux.map(n => n.cle)).toEqual(['regles', 'embeddings', 'llm']);
    expect(niveaux[0].etat).toBe('A tranché');
    expect(niveaux[1].consulte).toBe(false);
    expect(niveaux[1].etat).toBe('Non consulté');
    expect(niveaux[1].raison).toContain('les règles ont tranché');
    expect(niveaux[2].consulte).toBe(false);
  });

  it('cascade : trois niveaux consultés, le dernier tranche', () => {
    const niveaux = component.niveauxCascade(FICHIER_AVEC_LLM);

    expect(niveaux.map(n => n.etat)).toEqual(['N\'a pas tranché', 'N\'a pas tranché', 'A tranché']);
    expect(niveaux[0].raison).toContain('score trop bas');
    expect(niveaux[2].detail.justification).toBe('attestation d\'un employeur');
  });

  it('cascade : un LLM en panne est signalé « indisponible »', () => {
    const niveaux = component.niveauxCascade({
      classification: { trace: [{ niveau: 'llm', echec: true, decisif: false, raison: 'quota dépassé' }] },
    });

    expect(niveaux[2].etat).toBe('Indisponible');
    expect(niveaux[2].raison).toBe('quota dépassé');
  });

  it('document sans trace : on le sait, pour proposer de relancer la vérification', () => {
    expect(component.aTrace(FICHIER_EN_CONFLIT)).toBe(true);
    expect(component.aTrace({ classification: { methode: 'regles' } })).toBe(false);
    expect(component.aTrace({})).toBe(false);
  });

  // ── Popup ──────────────────────────────────────────────────────────────────

  it('le popup s\'ouvre, montre ce qui s\'est passé, et se ferme', () => {
    component.ouvrirDetail(FICHIER_EN_CONFLIT);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.modal-classif')).toBeTruthy();
    expect(texte()).toContain('Détail de la classification');
    expect(texte()).toContain('Type déclaré par le client');
    expect(texte()).toContain('Fiche de paie');
    expect(texte()).toContain('Relevé bancaire');
    expect(texte()).toContain('Contradiction');
    expect(texte()).toContain('Règles par mots-clés');
    expect(texte()).toContain('releve de compte');                      // mot-clé trouvé
    expect(texte()).toContain('Non consulté : les règles ont tranché');

    component.fermerDetail();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.modal-classif')).toBeNull();
  });

  it('le popup montre le CIN lu face au CIN du client', () => {
    component.ouvrirDetail({ ...FICHIER_EN_CONFLIT, cinCoherent: false, jsonData: { cin: '99999999' } });
    fixture.detectChanges();

    expect(texte()).toContain('99999999');
    expect(texte()).toContain('12015060');
    expect(texte()).toContain('Incohérent');
  });

  it('le popup montre la similarité, les candidats et la justification du LLM', () => {
    component.ouvrirDetail(FICHIER_AVEC_LLM);
    fixture.detectChanges();

    expect(texte()).toContain('similarité 0,41');
    expect(texte()).toContain('attestation d\'un employeur');
    expect(texte()).toContain('via groq');
  });

  it('popup d\'un document sans détail : message clair au lieu d\'un écran vide', () => {
    component.ouvrirDetail({ nomOriginal: 'x.pdf', typeDocument: 'CIN' });
    fixture.detectChanges();

    expect(texte()).toContain('pas encore été classé');
  });

  it('le popup se ferme au clic sur le fond mais pas sur son contenu', () => {
    component.ouvrirDetail(FICHIER_EN_CONFLIT);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.modal-classif') as HTMLElement).click();
    fixture.detectChanges();
    expect(component.fichierDetail).not.toBeNull();

    (fixture.nativeElement.querySelector('.modal-overlay') as HTMLElement).click();
    fixture.detectChanges();
    expect(component.fichierDetail).toBeNull();
  });

  // ── Confirmation de l'agent ────────────────────────────────────────────────

  it('confirmer l\'analyse marque l\'incohérence comme confirmée (pipeline vert)', () => {
    component.dossierId = 'dossier-1';
    expect(component.incoherenceConfirmee).toBe(false);

    component.confirmerAnalyse();
    http.expectOne(`${environment.apiUrl}/api/fichiers/analyser-dossier/dossier-1?confirmerIncoherence=true`)
        .flush({ success: false, message: 'erreur de test' });

    expect(component.incoherenceConfirmee).toBe(true);
  });

  it('changer de dossier remet la confirmation à zéro', () => {
    component.incoherenceConfirmee = true;
    component.ouvrirDetail(FICHIER_EN_CONFLIT);

    component.dossierId = 'autre-dossier';
    component.ngOnChanges({ dossierId: { currentValue: 'autre-dossier', previousValue: '', firstChange: false,
                                         isFirstChange: () => false } });
    http.match(() => true);   // le rechargement de la liste des fichiers

    expect(component.incoherenceConfirmee).toBe(false);
    expect(component.fichierDetail).toBeNull();
  });
});
