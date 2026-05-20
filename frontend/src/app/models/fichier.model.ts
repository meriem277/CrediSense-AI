// src/app/models/fichier.model.ts

export interface Fichier {
  id: string;
  cin: string;
  nomOriginal: string;
  typeOriginal: string;
  cheminPdf: string;
  agentId: string;
  createdAt: string;
}

export interface FichierUploadResponse {
  id: string;
  cin: string;
  nomOriginal: string;
  typeOriginal: string;
  cheminPdf: string;
  agentId: string;
  createdAt: string;
}

// Statut local pour l'affichage dans l'UI
export type UploadStatus = 'pending' | 'uploading' | 'success' | 'error';

export interface FileEntry {
  file: File;
  status: UploadStatus;
  fichier?: Fichier;   // rempli après upload réussi
  erreur?: string;     // rempli en cas d'erreur
}
