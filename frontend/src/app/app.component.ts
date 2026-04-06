import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClientModule } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { DocumentService } from './services/document.service';
import { AnalysisResponse, ChatMessage } from './models/analysis.model';

type Tab = 'upload' | 'chat' | 'result';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, HttpClientModule, FormsModule],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.scss']
})
export class AppComponent {

  activeTab: Tab = 'upload';
  uploadedFiles: File[] = [];
  messages: ChatMessage[] = [];
  currentMessage = '';
  analysis?: AnalysisResponse;
  creditResult?: AnalysisResponse;
  isLoading = false;
  isDragging = false;

  constructor(private docService: DocumentService) {}

  setTab(tab: Tab) { this.activeTab = tab; }

  onDragOver(e: DragEvent) { e.preventDefault(); this.isDragging = true; }
  onDragLeave() { this.isDragging = false; }

  onDrop(e: DragEvent) {
    e.preventDefault();
    this.isDragging = false;
    const files = Array.from(e.dataTransfer?.files || []);
    this.processFiles(files);
  }

  onFileSelect(e: Event) {
    const input = e.target as HTMLInputElement;
    if (input.files) this.processFiles(Array.from(input.files));
  }

  processFiles(files: File[]) {
    files.forEach(f => {
      if (!this.uploadedFiles.find(u => u.name === f.name)) {
        this.uploadedFiles.push(f);
        this.analyzeFile(f);
      }
    });
  }

  analyzeFile(file: File) {
    this.isLoading = true;
    this.docService.uploadFile(file).subscribe({
      next: (res) => { this.analysis = res; this.isLoading = false; },
      error: () => { this.isLoading = false; }
    });
  }

  removeFile(i: number) { this.uploadedFiles.splice(i, 1); }

  formatSize(bytes: number): string {
    return bytes > 1048576
      ? (bytes / 1048576).toFixed(1) + ' Mo'
      : (bytes / 1024).toFixed(0) + ' Ko';
  }

  sendMessage() {
    if (!this.currentMessage.trim() || this.isLoading) return;
    const msg = this.currentMessage.trim();
    this.messages.push({ role: 'user', content: msg });
    this.currentMessage = '';
    this.isLoading = true;
    const ctx = this.analysis?.summary || '';
    this.docService.chat(msg, ctx).subscribe({
      next: (reply) => {
        this.messages.push({ role: 'assistant', content: reply });
        this.isLoading = false;
      },
      error: () => { this.isLoading = false; }
    });
  }

  analyzeCreditScore() {
    if (!this.uploadedFiles.length) return;
    this.isLoading = true;
    this.docService.getCreditScore(this.uploadedFiles[0]).subscribe({
      next: (res) => {
        this.creditResult = res;
        this.isLoading = false;
        this.setTab('result');
      },
      error: () => { this.isLoading = false; }
    });
  }
}
