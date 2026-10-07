import { DocumentPipeline, PipelineAnalyseComponent } from './pipeline-analyse';

// Identité lue sur la CIN
const IDENTITE = { nomClient: 'Ben Ali', prenomClient: 'Sami', cin: '12345678' };

function doc(typeDocument: string, jsonData: any, cinCoherent: boolean | null = null): DocumentPipeline {
  return { nomOriginal: `${typeDocument}.pdf`, typeDocument, jsonData, cinCoherent };
}

/** Les 4 documents requis après la CIN, tous lus par l'IA. */
function documentsRequisLus(): DocumentPipeline[] {
  return [
    doc('FICHE_PAIE',            { revenuMensuelNet: 1820 }),
    doc('RELEVE_BANCAIRE',       { soldeMoyenCompte: 1770 }),
    doc('ATTESTATION_EMPLOI',    { employeur: 'ACME' }),
    doc('JUSTIFICATIF_DOMICILE', { nomClient: 'Ben Ali' }),
  ];
}

/** Crée le composant, lui donne ses entrées, et renvoie l'état émis vers le parent. */
function evaluer(
  documents: DocumentPipeline[],
  options: { analyseEnCours?: boolean; analyseTerminee?: boolean } = {}
) {
  const composant = new PipelineAnalyseComponent();
  composant.documents       = documents;
  composant.analyseEnCours  = options.analyseEnCours  ?? false;
  composant.analyseTerminee = options.analyseTerminee ?? false;

  let etat: { peutAnalyser: boolean; peutAnalyserAvecConfirmation: boolean } | undefined;
  composant.etatChange.subscribe(e => (etat = e));
  composant.ngOnChanges({});

  const etape = (id: string) => composant.etapes.find(e => e.id === id)!;
  return { composant, etat: etat!, etape };
}

describe('PipelineAnalyseComponent — verrouillage de l\'analyse', () => {

  it('sans document : rien n\'est autorisé', () => {
    const { etat, etape } = evaluer([]);

    expect(etape('cin').statut).toBe('attente');
    expect(etat.peutAnalyser).toBe(false);
    expect(etat.peutAnalyserAvecConfirmation).toBe(false);
  });

  it('documents déposés mais pas encore lus : « à vérifier », pas une erreur', () => {
    const { etat, etape } = evaluer([doc('CIN', null), ...documentsRequisLus().map(d => ({ ...d, jsonData: null }))]);

    expect(etape('cin').statut).toBe('attente');
    expect(etape('cin').detail).toContain('Vérifier les documents');
    expect(etat.peutAnalyser).toBe(false);
    expect(etat.peutAnalyserAvecConfirmation).toBe(false);
  });

  it('tout est lu et cohérent : l\'analyse est autorisée sans confirmation', () => {
    const { etat, etape } = evaluer([doc('CIN', IDENTITE, true), ...documentsRequisLus()]);

    expect(etape('cin').statut).toBe('valide');
    expect(etape('docs').statut).toBe('valide');
    expect(etat.peutAnalyser).toBe(true);
    expect(etat.peutAnalyserAvecConfirmation).toBe(false);
  });

  it('CIN incohérent, le reste est complet : autorisé UNIQUEMENT avec confirmation', () => {
    const { etat, etape } = evaluer([doc('CIN', IDENTITE, false), ...documentsRequisLus()]);

    expect(etape('cin').statut).toBe('erreur');
    expect(etape('cin').detail).toContain('incohérent');
    expect(etat.peutAnalyser).toBe(false);
    expect(etat.peutAnalyserAvecConfirmation).toBe(true);
  });

  it('CIN incohérent sur un autre document (pas la CIN) : même règle', () => {
    const docs = documentsRequisLus();
    docs[0] = doc('FICHE_PAIE', { revenuMensuelNet: 1820, cin: '99999999' }, false);
    const { etat } = evaluer([doc('CIN', IDENTITE, true), ...docs]);

    expect(etat.peutAnalyser).toBe(false);
    expect(etat.peutAnalyserAvecConfirmation).toBe(true);
  });

  it('CIN incohérent ET document requis manquant : rien n\'est autorisé', () => {
    const sansReleve = documentsRequisLus().filter(d => d.typeDocument !== 'RELEVE_BANCAIRE');
    const { etat } = evaluer([doc('CIN', IDENTITE, false), ...sansReleve]);

    expect(etat.peutAnalyser).toBe(false);
    expect(etat.peutAnalyserAvecConfirmation).toBe(false);
  });

  it('CIN lu mais nom manquant : bloqué, pas de confirmation possible', () => {
    const { etat, etape } = evaluer([doc('CIN', { ...IDENTITE, nomClient: '' }, true), ...documentsRequisLus()]);

    expect(etape('cin').statut).toBe('erreur');
    expect(etape('cin').detail).toContain('nom');
    expect(etat.peutAnalyser).toBe(false);
    expect(etat.peutAnalyserAvecConfirmation).toBe(false);
  });

  it('document requis présent mais non lu : l\'étape documents n\'est pas validée', () => {
    const docs = documentsRequisLus();
    docs[1] = doc('RELEVE_BANCAIRE', null);   // déposé, pas encore lu
    const { etat, etape } = evaluer([doc('CIN', IDENTITE, true), ...docs]);

    expect(etape('docs').statut).toBe('en_cours');
    expect(etape('docs').detail).toContain('RELEVE_BANCAIRE');
    expect(etat.peutAnalyser).toBe(false);
  });

  it('analyse en cours ou terminée : plus d\'autorisation à relancer', () => {
    const documents = [doc('CIN', IDENTITE, true), ...documentsRequisLus()];

    const enCours = evaluer(documents, { analyseEnCours: true });
    expect(enCours.etat.peutAnalyser).toBe(false);

    const terminee = evaluer(documents, { analyseTerminee: true });
    expect(terminee.etat.peutAnalyser).toBe(false);
    expect(terminee.etat.peutAnalyserAvecConfirmation).toBe(false);
  });
});

describe('PipelineAnalyseComponent — type de document contredit', () => {

  /** Un relevé bancaire déposé dans l'emplacement « fiche de paie ». */
  function documentsAvecFichePaieEnConflit(): DocumentPipeline[] {
    const docs = documentsRequisLus();
    docs[0] = { ...doc('FICHE_PAIE', { revenuMensuelNet: 1820 }), typeDetecte: 'RELEVE_BANCAIRE', typeConflit: true };
    return docs;
  }

  it('type contredit, CIN cohérent : l\'analyse est bloquée mais possible après confirmation', () => {
    const { etat, etape } = evaluer([doc('CIN', IDENTITE, true), ...documentsAvecFichePaieEnConflit()]);

    expect(etape('cin').statut).toBe('valide');
    expect(etape('docs').statut).toBe('erreur');
    expect(etape('docs').detail).toContain('FICHE_PAIE → RELEVE_BANCAIRE');
    expect(etat.peutAnalyser).toBe(false);
    expect(etat.peutAnalyserAvecConfirmation).toBe(true);
  });

  it('type contredit ET CIN incohérent : une seule confirmation couvre les deux', () => {
    const { etat, etape } = evaluer([doc('CIN', IDENTITE, false), ...documentsAvecFichePaieEnConflit()]);

    expect(etape('cin').statut).toBe('erreur');
    expect(etat.peutAnalyser).toBe(false);
    expect(etat.peutAnalyserAvecConfirmation).toBe(true);
  });

  it('un verdict incertain (conflit null) ne bloque rien', () => {
    const docs = documentsRequisLus();
    docs[0] = { ...docs[0], typeDetecte: 'RELEVE_BANCAIRE', typeConflit: null };
    const { etat, etape } = evaluer([doc('CIN', IDENTITE, true), ...docs]);

    expect(etape('docs').statut).toBe('valide');
    expect(etat.peutAnalyser).toBe(true);
    expect(etat.peutAnalyserAvecConfirmation).toBe(false);
  });

  it('un type confirmé conforme (conflit false) ne bloque rien', () => {
    const docs = documentsRequisLus();
    docs[0] = { ...docs[0], typeDetecte: 'FICHE_PAIE', typeConflit: false };
    const { etat } = evaluer([doc('CIN', IDENTITE, true), ...docs]);

    expect(etat.peutAnalyser).toBe(true);
  });

  it('un document requis manquant passe avant le conflit de type', () => {
    const docs = documentsAvecFichePaieEnConflit().filter(d => d.typeDocument !== 'RELEVE_BANCAIRE');
    const { etat, etape } = evaluer([doc('CIN', IDENTITE, true), ...docs]);

    expect(etape('docs').statut).toBe('en_cours');
    expect(etape('docs').detail).toContain('RELEVE_BANCAIRE');
    expect(etat.peutAnalyserAvecConfirmation).toBe(false);
  });

  it('analyse déjà terminée : le conflit de type ne permet plus de relancer', () => {
    const { etat } = evaluer([doc('CIN', IDENTITE, true), ...documentsAvecFichePaieEnConflit()],
                             { analyseTerminee: true });

    expect(etat.peutAnalyser).toBe(false);
    expect(etat.peutAnalyserAvecConfirmation).toBe(false);
  });
});
