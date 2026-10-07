import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';

export type StatutEtape = 'attente' | 'en_cours' | 'valide' | 'erreur';

export interface DocumentPipeline {
  nomOriginal: string;
  typeDocument: string;   // CIN, FICHE_PAIE, RELEVE_BANCAIRE, ATTESTATION_EMPLOI, JUSTIFICATIF_DOMICILE, AUTRE
  jsonData?: {
    nomClient?: string | null;
    prenomClient?: string | null;
    cin?: string | null;
    [key: string]: any;
  } | null;
  // ✅ Calculé côté backend — true/false si ce fichier a un "cin" comparable
  // au CIN officiel du client, null si ce document n'a pas de champ cin.
  cinCoherent?: boolean | null;
}

export interface EtapePipeline {
  id: string;
  titre: string;
  statut: StatutEtape;
  detail: string;
}

// Types de documents attendus après validation du CIN
const TYPES_REQUIS = ['FICHE_PAIE', 'RELEVE_BANCAIRE', 'ATTESTATION_EMPLOI', 'JUSTIFICATIF_DOMICILE'];

@Component({
  selector: 'app-pipeline-analyse',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './pipeline-analyse.html',
  styleUrls: ['./pipeline-analyse.scss']
})
export class PipelineAnalyseComponent implements OnChanges {

  // ── Entrées ────────────────────────────────────────────────────────────
  @Input() documents: DocumentPipeline[] = [];
  @Input() analyseEnCours = false;
  @Input() analyseTerminee = false;
  @Input() scoreDisponible = false;

  // ── Sortie : ce que le parent peut autoriser ──────────────────────────────
  //  - peutAnalyser : les étapes 1 et 2 sont validées
  //  - peutAnalyserAvecConfirmation : seul le CIN est incohérent (tout est lu et les
  //    documents requis sont là) : l'agent peut poursuivre APRÈS avoir confirmé
  @Output() etatChange = new EventEmitter<{
    peutAnalyser: boolean;
    peutAnalyserAvecConfirmation: boolean;
  }>();

  etapes: EtapePipeline[] = [];

  /** Vrai quand l'étape CIN est en erreur uniquement à cause d'une incohérence de numéro. */
  private cinIncoherenceSeule = false;

  ngOnChanges(_: SimpleChanges): void {
    this.calculerEtapes();
    this.etatChange.emit({
      peutAnalyser: this.peutLancerAnalyse,
      peutAnalyserAvecConfirmation: this.peutLancerAvecConfirmation
    });
  }

  // ── Calcul de l'état des 4 étapes ─────────────────────────────────────
  private calculerEtapes(): void {
    this.cinIncoherenceSeule = false;
    const etapeCin     = this.evaluerEtapeCin();
    const etapeDocs     = this.evaluerEtapeDocuments(etapeCin.statut === 'valide');
    const etapeAnalyse = this.evaluerEtapeAnalyse(etapeDocs.statut === 'valide');
    const etapeScore     = this.evaluerEtapeScore(etapeAnalyse.statut === 'valide');

    this.etapes = [etapeCin, etapeDocs, etapeAnalyse, etapeScore];
  }

  // ── Étape 1 : CIN doit contenir nom + prénom + numéro CIN, ET ce numéro
  // doit être cohérent avec le CIN officiel du client sur TOUS les documents ──
  private evaluerEtapeCin(): EtapePipeline {
    const cin = this.documents.find(d => d.typeDocument === 'CIN');

    if (!cin) {
      return { id: 'cin', titre: 'Vérification CIN', statut: 'attente', detail: 'En attente du document CIN' };
    }

    // Document présent mais pas encore lu par l'IA : ce n'est pas une erreur, c'est
    // l'étape « Vérifier les documents » qui n'a pas encore été lancée
    if (!cin.jsonData) {
      return {
        id: 'cin',
        titre: 'Vérification CIN',
        statut: 'attente',
        detail: 'Documents à vérifier — cliquez sur « Vérifier les documents »'
      };
    }

    const donnees  = cin.jsonData;
    const nomOk     = !!donnees?.nomClient?.trim();
    const prenomOk = !!donnees?.prenomClient?.trim();
    const cinOk     = !!donnees?.cin?.trim();

    if (!nomOk || !prenomOk || !cinOk) {
      const manquants = [
        !nomOk    ? 'nom'         : null,
        !prenomOk ? 'prénom'     : null,
        !cinOk    ? 'numéro CIN' : null
      ].filter(Boolean).join(', ');

      return {
        id: 'cin',
        titre: 'Vérification CIN',
        statut: 'erreur',
        detail: `Informations manquantes : ${manquants}`
      };
    }

    // ✅ Cohérence du CIN sur TOUS les documents — pas seulement le document
    // CIN lui-même. cinCoherent est déjà calculé côté backend (comparaison
    // au CIN officiel du dossier) ; ici on ne fait qu'agréger le résultat.
    const incoherents = this.documents.filter(d => d.cinCoherent === false);

    if (incoherents.length > 0) {
      this.cinIncoherenceSeule = true;
      const types = incoherents.map(d => d.typeDocument).join(', ');
      return {
        id: 'cin',
        titre: 'Vérification CIN',
        statut: 'erreur',
        detail: `CIN incohérent sur : ${types} — vérification manuelle recommandée`
      };
    }

    return {
      id: 'cin',
      titre: 'Vérification CIN',
      statut: 'valide',
      detail: `${donnees!.prenomClient} ${donnees!.nomClient} — CIN ${donnees!.cin} (cohérent sur tous les documents)`
    };
  }

  // ── Étape 2 : classification des autres documents ─────────────────────
  private evaluerEtapeDocuments(cinValide: boolean): EtapePipeline {
    if (!cinValide) {
      return {
        id: 'docs',
        titre: 'Validation des documents',
        statut: 'attente',
        detail: "Verrouillé — CIN à valider d'abord"
      };
    }

    const manquants = this.typesRequisManquants();

    if (manquants.length === 0) {
      return {
        id: 'docs',
        titre: 'Validation des documents',
        statut: 'valide',
        detail: `${TYPES_REQUIS.length}/${TYPES_REQUIS.length} documents classifiés et validés`
      };
    }

    return {
      id: 'docs',
      titre: 'Validation des documents',
      statut: 'en_cours',
      detail: `Manquant ou non vérifié : ${manquants.join(', ')}`
    };
  }

  /** Types requis absents, ou présents mais pas encore lus par l'IA (pas de jsonData). */
  private typesRequisManquants(): string[] {
    return TYPES_REQUIS.filter(type =>
      !this.documents.some(d => d.typeDocument === type && !!d.jsonData)
    );
  }

  // ── Étape 3 : analyse (OCR + extraction + RAG + agent) ────────────────
  private evaluerEtapeAnalyse(docsValides: boolean): EtapePipeline {
    if (this.analyseTerminee) {
      return { id: 'analyse', titre: 'Analyse IA', statut: 'valide', detail: 'Analyse terminée' };
    }
    if (this.analyseEnCours) {
      return { id: 'analyse', titre: 'Analyse IA', statut: 'en_cours', detail: 'Extraction, RAG et scoring en cours...' };
    }
    if (!docsValides) {
      return { id: 'analyse', titre: 'Analyse IA', statut: 'attente', detail: "Verrouillé — documents à valider d'abord" };
    }
    return { id: 'analyse', titre: 'Analyse IA', statut: 'attente', detail: 'Prêt à lancer' };
  }

  // ── Étape 4 : score crédit ─────────────────────────────────────────────
  private evaluerEtapeScore(analyseValide: boolean): EtapePipeline {
    if (this.scoreDisponible) {
      return { id: 'score', titre: 'Score crédit', statut: 'valide', detail: 'Décision disponible' };
    }
    return {
      id: 'score',
      titre: 'Score crédit',
      statut: analyseValide ? 'en_cours' : 'attente',
      detail: analyseValide ? 'Calcul du score...' : "En attente de l'analyse"
    };
  }

  // ── Utilisé par le parent pour activer/désactiver le bouton "Analyser" ──
  get peutLancerAnalyse(): boolean {
    const docsEtape = this.etapes.find(e => e.id === 'docs');
    return docsEtape?.statut === 'valide' && !this.analyseEnCours && !this.analyseTerminee;
  }

  // ── Seul le CIN est incohérent : l'agent peut poursuivre en confirmant ───
  get peutLancerAvecConfirmation(): boolean {
    return this.cinIncoherenceSeule
      && this.typesRequisManquants().length === 0
      && !this.analyseEnCours
      && !this.analyseTerminee;
  }
}
