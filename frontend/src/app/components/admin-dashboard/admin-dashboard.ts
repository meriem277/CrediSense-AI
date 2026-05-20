import { Component, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../../services/auth.service';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { Register } from '../register/register';

@Component({
  selector: 'app-admin-dashboard',
  imports: [CommonModule, ReactiveFormsModule,Register],
  templateUrl: './admin-dashboard.html',
  styleUrl: './admin-dashboard.scss',
})
export class AdminDashboard  implements OnInit {
   // Données admin
  nom = '';
  activeTab = 'overview';  // 'overview' | 'register' | 'agents'

  // Formulaire register
  registerForm: FormGroup;
  registerLoading = false;
  registerError = '';
  registerSuccess = '';

  // Liste des agents
  agents: any[] = [];

  constructor(
    private auth: AuthService,
    private fb: FormBuilder,
    private http: HttpClient,
    private router: Router
  )
  {
    this.registerForm = this.fb.group({
      nom:      ['', Validators.required],
      email:    ['', [Validators.required, Validators.email]],
      password: ['', [Validators.required, Validators.minLength(8)]],
      role:     ['AGENT', Validators.required]
    });
  }

  ngOnInit() {
    const user = this.auth.getUser();
    this.nom = user?.nom || 'Admin';
    this.loadAgents();
  }

  loadAgents() {
    this.http.get<any[]>('http://localhost:8081/api/agents')
      .subscribe({ next: (data) => this.agents = data, error: () => {} });
  }

  submitRegister() {
    if (this.registerForm.invalid) return;
    this.registerLoading = true;
    this.registerError = '';
    this.registerSuccess = '';

    this.http.post<any>(
      'http://localhost:8081/api/auth/register',
      this.registerForm.value
    ).subscribe({
      next: () => {
        this.registerSuccess = 'Compte créé avec succès.';
        this.registerForm.reset({ role: 'AGENT' });
        this.registerLoading = false;
        this.loadAgents();
      },
      error: (err) => {
        this.registerError = err.error?.message || 'Erreur lors de la création.';
        this.registerLoading = false;
      }
    });
  }

  logout() {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}

