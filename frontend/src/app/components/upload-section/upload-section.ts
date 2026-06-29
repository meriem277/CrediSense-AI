import { CommonModule } from '@angular/common';
import { HttpClient, HttpEventType } from '@angular/common/http';
import { Component, Input } from '@angular/core';
import { Fichier } from '../../models/fichier.model';
import { FichierService } from '../../services/fichier.service';
import { AuthService } from '../../services/auth.service';
import { CreditAgentService } from '../../services/credit-agent.service';
import { CreditStateService }  from '../../services/credit-state.service';
import { Dossier }             from '../../models/dossier.model';

export interface FileEntry {
  id: number;
  file: File;
  progress: number;
  status: 'pending' | 'uploading' | 'done' | 'error';
  fichier?: Fichier;   // rempli après upload réussi
  erreur?: string;
}

export interface Toast {
  message: string;
  type: 'success' | 'error';
}

@Component({
  selector: 'app-upload-section',
  imports: [CommonModule],
  templateUrl: './upload-section.html',
  styleUrl: './upload-section.scss',
})
export class UploadSection {

  files: FileEntry[] = [];
  toasts: Toast[]   = [];
  isDragging        = false;
  private idCounter = 0;

  // CIN du client — à adapter selon ton formulaire ou ta route
@Input() cin:             string = '';
@Input() dossierId:       string = '';
@Input() dossierTypeCredit: string = 'IMMOBILIER'; // ← nouveau

constructor(
  private http: HttpClient,
  private fichierService: FichierService,
  private authService: AuthService,
  private creditAgent: CreditAgentService,   // ← nouveau
  private creditState: CreditStateService,   // ← nouveau
) {}

  // ─── Drag & Drop ──────────────────────────────────────────────

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.isDragging = true;
  }

  onDragLeave(event: DragEvent): void {
    this.isDragging = false;
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.isDragging = false;
    const droppedFiles = Array.from(event.dataTransfer?.files ?? []);
    this.addFiles(droppedFiles);
  }

  // ─── Sélection via input ──────────────────────────────────────

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const selectedFiles = Array.from(input.files ?? []);
    this.addFiles(selectedFiles);
    input.value = '';
  }

  // ─── Ajout & validation ───────────────────────────────────────

  private addFiles(newFiles: File[]): void {
    const allowedTypes = ['image/jpeg', 'image/png', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document','application/pdf'];

    newFiles.forEach(file => {
      if (file.size > 10 * 1024 * 1024) {
        this.showToast(`Fichier trop volumineux : ${file.name}`, 'error');
        return;
      }
//agent
      if (!allowedTypes.includes(file.type)) {
        this.showToast(`Type non supporté : ${file.name} (JPG, PNG, DOCX uniquement)`, 'error');
        return;
      }

      const entry: FileEntry = {
        id: ++this.idCounter,
        file,
        progress: 0,
        status: 'pending',
      };

      this.files.push(entry);
      this.realUpload(entry);
    });
  }

  removeFile(id: number): void {
    this.files = this.files.filter(f => f.id !== id);
  }

  // ─── Upload réel vers le backend ──────────────────────────────

  private realUpload(entry: FileEntry): void {
    //const agentId = this.authService.getUser()?.id;
 // Temporaire : UUID fixe pour les tests
const user    = this.authService.getUser();
const agentId = user?.id;

if (!agentId) {
  this.showToast('Session expirée, veuillez vous reconnecter.', 'error');
  return;
}


    entry.status   = 'uploading';
    entry.progress = 0;

    // Appel au backend : POST /api/fichiers/upload
    // Le JWT est ajouté automatiquement par jwtInterceptor
    this.fichierService.uploadAndConvert(entry.file, this.cin, agentId, this.dossierId).subscribe({
      next: (fichier: Fichier) => {
        entry.status   = 'done';
        entry.progress = 100;
        entry.fichier  = fichier;
        this.showToast(`${entry.file.name} converti en PDF avec succès`, 'success');
      },
      error: (err) => {
        entry.status = 'error';
        entry.erreur = err?.error?.message ?? 'Erreur inconnue';
        this.showToast(`Errkkkkkeur : ${entry.file.name}`, 'error');
      },
    });
  }

  // ─── Actions ─────────────────────────────────────────────────

  get allDone(): boolean {
    return this.files.length > 0 && this.files.every(f => f.status === 'done');
  }

  handleAnalyse(): void {
    const formData = new FormData();
    this.files.forEach(({ file }) => formData.append('files', file));

    this.http.post('/api/analyse', formData).subscribe({
      next: (data) => console.log('Analyse :', data),
      error: () => this.showToast('Erreur lors de l\'analyse', 'error'),
    });
  }

  handleScore(): void {
  // On prend le premier fichier uploadé avec succès
  const entry = this.files.find(f => f.status === 'done' && f.file);
  if (!entry) {
    this.showToast('Aucun fichier disponible pour l\'analyse', 'error');
    return;
  }

  const type = this.dossierTypeCredit === 'CONSOMMATION'
    ? 'consommation'
    : 'immobilier';

  this.creditState.setLoading(true);
  this.creditState.clear();
  // Signal dashboard to navigate to the score tab
  this.creditState.triggerNavigateToScore();

  this.creditAgent.analyse(type, entry.file, this.cin).subscribe({
    next: result => {
      this.creditState.setResult(result);
      this.creditState.setLoading(false);
      this.showToast('Analyse crédit terminée', 'success');
    },
    error: () => {
      this.creditState.setLoading(false);
      this.showToast('Erreur lors du calcul du score crédit', 'error');
    }
  });
}

  // ─── Utilitaires ─────────────────────────────────────────────

  getIcon(filename: string): string {
    const ext = filename.split('.').pop()?.toLowerCase();
    const map: Record<string, string> = {
      pdf: '📄', png: '🖼️', jpg: '🖼️', jpeg: '🖼️', docx: '📝',
    };
    return map[ext ?? ''] ?? '📁';
  }

  formatSize(bytes: number): string {
    if (bytes < 1024)           return `${bytes} B`;
    if (bytes < 1024 * 1024)    return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  getStatusLabel(status: FileEntry['status']): string {
    const labels: Record<FileEntry['status'], string> = {
      pending:   'En attente...',
      uploading: 'Chargement...',
      done:      '✓ Converti en PDF',
      error:     '✗ Erreur',
    };
    return labels[status];
  }

  private showToast(message: string, type: Toast['type']): void {
    const toast: Toast = { message, type };
    this.toasts.push(toast);
    setTimeout(() => {
      this.toasts = this.toasts.filter(t => t !== toast);
    }, 3000);
  }
}
