/** Une proposition d'ajustement faite au client (dossier conditionnel) : montant ou durée adaptés. */
export interface OffreProposee {
  kind: string;
  label: string;
  amount: number;
  duration: number;
  monthlyPayment: number;
  dti: number;
  totalCost: number;
  explanation: string;
}

/** EN_ATTENTE_REPONSE : le client doit répondre ; ACCEPTEE / REFUSEE : il a répondu (une seule fois). */
export type EtatProposition = 'EN_ATTENTE_REPONSE' | 'ACCEPTEE' | 'REFUSEE';

export interface PropositionClient {
  decision: string;
  message: string;
  offres: OffreProposee[];
  etat: EtatProposition;
  /** Numéro (à partir de 0) de l'offre acceptée ; null sinon. */
  choix: number | null;
  repondueLe: string | null;
}

/** Une ligne de la liste des demandes qui ont une proposition. */
export interface ResumeProposition {
  dossierId: string;
  etat: EtatProposition;
}
