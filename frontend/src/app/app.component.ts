import { Component, ViewEncapsulation } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpClientModule } from '@angular/common/http';
import { UploadSection } from "./components/Interne/upload-section/upload-section";
import { CreditResult } from "./components/Interne/credit-result/credit-result";
import { ChatAssistant } from "./components/Interne/chat-assistant/chat-assistant";
import { ExportButton } from "./components/Interne/export-button/export-button";
import { DashboardCard } from './components/dashboard-card/dashboard-card';
import { RouterOutlet } from "@angular/router";
type Tab = 'upload' | 'chat' | 'result';

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, HttpClientModule, FormsModule, RouterOutlet],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.scss'],
    encapsulation: ViewEncapsulation.None   // ← ajoutez cette ligne

})
export class AppComponent {
  activeTab: Tab = 'upload';
  uploadedFiles: File[] = [];
  messages: ChatMessage[] = [];
  currentMessage = '';
  analysis: any = null;
  creditResult: any = null;
  isLoading = false;
  isDragging = false;

  private fastapi = 'http://localhost:8001/api';
  private springboot = 'http://localhost:8081/api';

  constructor(private http: HttpClient) {}

  setTab(tab: Tab) { this.activeTab = tab; }

  onDragOver(e: DragEvent) { e.preventDefault(); this.isDragging = true; }
  onDragLeave() { this.isDragging = false; }

  onDrop(e: DragEvent) {
    e.preventDefault();
    this.isDragging = false;
    this.processFiles(Array.from(e.dataTransfer?.files || []));
  }

  onFileSelect(e: Event) {
    const input = e.target as HTMLInputElement;
    if (input.files) this.processFiles(Array.from(input.files));
  }

  processFiles(files: File[]) {
    files.forEach(f => {
      if (!this.uploadedFiles.find(u => u.name === f.name))
        this.uploadedFiles.push(f);
    });
  }

  removeFile(i: number) { this.uploadedFiles.splice(i, 1); }

  formatSize(bytes: number): string {
    return bytes > 1048576
      ? (bytes / 1048576).toFixed(1) + ' Mo'
      : (bytes / 1024).toFixed(0) + ' Ko';
  }

  analyzeFile() {
    if (!this.uploadedFiles.length) return;
    this.isLoading = true;
    const form = new FormData();
    form.append('file', this.uploadedFiles[0]);
    this.http.post<any>(`${this.fastapi}/analyze`, form).subscribe({
      next: (res) => { this.analysis = res; this.isLoading = false; },
      error: () => { this.isLoading = false; }
    });
  }

  analyzeCreditScore() {
    if (!this.uploadedFiles.length) return;
    this.isLoading = true;
    const form = new FormData();
    form.append('file', this.uploadedFiles[0]);
    this.http.post<any>(`${this.fastapi}/credit-score`, form).subscribe({
      next: (res) => { this.creditResult = res; this.isLoading = false; this.setTab('result'); },
      error: () => {
        // Mode démo sans backend
        this.creditResult = {
          solvabilite: 78, revenus: 65, historique: 90,
          endettement: 45, scoreGlobal: 69.5, verdict: 'APPROUVE',
          details: 'Profil financier satisfaisant.'
        };
        this.isLoading = false;
        this.setTab('result');
      }
    });
  }

  sendMessage() {
    if (!this.currentMessage.trim() || this.isLoading) return;
    const msg = this.currentMessage.trim();
    this.messages.push({ role: 'user', content: msg });
    this.currentMessage = '';
    this.isLoading = true;
    this.http.post<any>(`${this.fastapi}/chat`,
      { message: msg, context: this.analysis?.summary || '' }
    ).subscribe({
      next: (res) => {
        this.messages.push({ role: 'assistant', content: res.response || res });
        this.isLoading = false;
      },
      error: () => {
        this.messages.push({ role: 'assistant', content: 'Service IA non disponible.' });
        this.isLoading = false;
      }
    });
  }
}
