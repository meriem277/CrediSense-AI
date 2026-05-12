import { Component } from '@angular/core';
import { Sidebar } from "../sidebar/sidebar";
import { Header } from "../header/header";
import { UploadSection } from "../upload-section/upload-section";
import { HistoryList } from "../history-list/history-list";
import { CreditResult } from "../credit-result/credit-result";
import { ChatAssistant } from "../chat-assistant/chat-assistant";
import { ExportButton } from "../export-button/export-button";
import { DashboardCard } from '../dashboard-card/dashboard-card';

@Component({
  selector: 'app-dashboard',
  imports: [Sidebar, Header, UploadSection, HistoryList, CreditResult, ChatAssistant, ExportButton,  DashboardCard ],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard {}
