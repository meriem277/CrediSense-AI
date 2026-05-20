import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';          // ✅ OBLIGATOIRE pour *ngIf
import { Sidebar } from "../sidebar/sidebar";
import { Header } from "../header/header";
import { UploadSection } from "../upload-section/upload-section";
import { HistoryList } from "../history-list/history-list";
import { CreditResult } from "../credit-result/credit-result";
import { ChatAssistant } from "../chat-assistant/chat-assistant";
import { ExportButton } from "../export-button/export-button";
import { DashboardCard } from '../dashboard-card/dashboard-card';
import { ClientModal, ClientResponse } from '../client-modal/client-modal';

@Component({
  selector: 'app-dashboard',
  standalone: true,                                      // ✅ OBLIGATOIRE
  imports: [
    CommonModule,                                        // ✅ pour *ngIf="showClientModal"
    Sidebar, Header, UploadSection, HistoryList,
    CreditResult, ChatAssistant, ExportButton, DashboardCard,
    ClientModal,
  ],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard {

  showClientModal  = false;
  currentCin       = '';
  lastCreatedClient: ClientResponse | null = null;

  openClientModal():  void { this.showClientModal = true;  }
  closeClientModal(): void { this.showClientModal = false; }

  onClientCreated(client: ClientResponse): void {
    this.lastCreatedClient = client;
    this.currentCin        = client.cin;
    console.log('Client créé :', client);
  }
}
