import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { FormBuilder, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { AuthResponse, LoginRequest } from '../../models/auth.model';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'app-login',
    standalone: true,
  imports: [ReactiveFormsModule, CommonModule, RouterModule],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
   form: FormGroup;
  loading = false;
  error = '';

  constructor(
    private fb: FormBuilder,
    private http: HttpClient,
    private auth: AuthService,
    private router: Router
  ) {
    this.form = this.fb.group({
      email:    ['', [Validators.required, Validators.email]],
      password: ['', Validators.required]
    });
  }

  submit() {
    if (this.form.invalid) return;
    this.loading = true;
    this.error = '';

    const body: LoginRequest = this.form.value;

    this.http.post<AuthResponse>('http://localhost:8080/api/auth/login', body)
      .subscribe({
        next: (res) => this.auth.login(res.token, { email: res.email, nom: res.nom, role: res.role }),
        error: (err) => {
          this.error = err.error?.message || 'Identifiants invalides';
          this.loading = false;
        }
      });
  }
}
