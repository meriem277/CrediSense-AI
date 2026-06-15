
export interface Fichier {
  id:           string;
  cin:          string;
  nomOriginal:  string;
  typeOriginal: string;
  cheminPdf:    string;
  agentId:      string;
  dossierId:    string;   
  createdAt:    string;
}

export interface FichierUploadResponse {
  id:           string;
  cin:          string;
  nomOriginal:  string;
  typeOriginal: string;
  cheminPdf:    string;
  agentId:      string;
  dossierId:    string;   // ✅ ajout
  createdAt:    string;
}

export type UploadStatus = 'pending' | 'uploading' | 'success' | 'error';

export interface FileEntry {
  file:     File;
  status:   UploadStatus;
  fichier?: Fichier;
  erreur?:  string;
}
