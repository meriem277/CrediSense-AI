export interface AnalysisResponse {
  summary?: string;
  solvabilite?: number;
  revenus?: number;
  historique?: number;
  endettement?: number;
  scoreGlobal?: number;
  verdict?: string;
  details?: string;
}

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
}
