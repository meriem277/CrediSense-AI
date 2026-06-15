import { Component, EventEmitter, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';

export interface Client {
  id: string;
  cin: string;
  nom: string;
  prenom: string;
  createdAt: string;
}
@Component({
  selector: 'app-client-list',
  imports: [CommonModule],
  templateUrl: './client-list.html',
  styleUrl: './client-list.scss',
})
export class ClientList  implements OnInit {
  @Output() clientSelected = new EventEmitter<Client>();

  clients: Client[] = [];
  loading = false;
  error   = '';

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.loadClients();
  }

  loadClients(): void {
    this.loading = true;
    this.error   = '';

    this.http.get<Client[]>(`${environment.apiUrl}/api/clients`).subscribe({
      next: (data) => {
        this.clients = data;
        this.loading = false;
      },
      error: () => {
        this.error   = 'Impossible de charger les clients.';
        this.loading = false;
      }
    });
  }

  selectClient(client: Client): void {
    this.clientSelected.emit(client);
  }


}
