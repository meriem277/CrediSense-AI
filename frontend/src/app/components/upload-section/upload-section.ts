import { Component, Input, OnChanges, SimpleChanges, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { environment }         from '../../../environments/environment';
import { CreditStateService } from '../../services/credit-state.service';

@Component({
  selector: 'app-upload-section',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './upload-section.html',
  styleUrl:    './upload-section.scss'
})
export class UploadSection implements OnChanges {

  @Input() dossierId = '';
  @Input() cin       = '';

  fichiers: any[] = [];
  loading  = false;

  constructor(
    private http:        HttpClient,
    private creditState: CreditStateService
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['dossierId'] && this.dossierId) {
      this.loadFichiers();
    }
  }

  loadFichiers(): void {
    this.loading = true;
    this.http.get<any[]>(
      `${environment.apiUrl}/api/dossiers/${this.dossierId}/fichiers`
    ).subscribe({
      next:  (data) => { this.fichiers = data; this.loading = false; },
      error: ()     => { this.loading = false; }
    });
  }

  getFileUrl(chemin: string): string {
    return `${environment.apiUrl}/api/fichiers/view/${chemin}`;
  }

  handleAnalyse(): void {
    // logique analyse existante
  }

handleScore(): void {
  this.creditState.triggerNavigateToScore();  // ✅ nom correct
}
}
