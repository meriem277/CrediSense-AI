export interface Dossier {
  id: string;
  typeCredit: string;
  statut: string;
  clientId: string;
  createdAt: string;
    fichiers?:   string[];  
}

export interface DossierRequest {
  typeCredit: string;
  statut: string;
  clientId: string;
}
