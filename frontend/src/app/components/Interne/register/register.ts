import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AbstractControl, FormBuilder, FormGroup, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { AuthResponse, RegisterRequest } from '../../../models/auth.model';
import { AuthService } from '../../../services/Interne/auth.service';

// ✅ Validateur mot de passe fort
function passwordStrengthValidator(control: AbstractControl): ValidationErrors | null {
  const val: string = control.value || '';
  if (!val) return null;
  const errors: ValidationErrors = {};
  if (val.length < 8)                                              errors['minLength']  = 'Au moins 8 caractères';
  if (!/[A-Z]/.test(val))                                         errors['uppercase']  = 'Au moins une majuscule';
  if (!/[0-9]/.test(val))                                         errors['digit']      = 'Au moins un chiffre';
  if (!/[!@#$%^&*()_+\-=\[\]{};\':"\\|,.<>\/?]/.test(val))      errors['special']    = 'Au moins un caractère spécial';
  return Object.keys(errors).length ? errors : null;
}

@Component({
  selector: 'app-register',
  standalone: true,
  imports: [ReactiveFormsModule, CommonModule, RouterModule],
  templateUrl: './register.html',
  styleUrl: './register.scss'
})
export class Register {

  form: FormGroup;
  loading = false;
  error   = '';
  success = '';

  passwordStrength = { percent: 0, color: '#e2e8f0', label: '' };

  constructor(
    private fb:     FormBuilder,
    private http:   HttpClient,
    private auth:   AuthService,
    private router: Router
  ) {
    this.form = this.fb.group({
      nom:      ['', Validators.required],
      email:    ['', [Validators.required, Validators.email]],
      password: ['', [Validators.required, passwordStrengthValidator]],
      confirmPassword: ['', Validators.required],

      role:     ['AGENT', Validators.required]
    });

    // ✅ Met à jour la barre de force à chaque frappe
    this.form.get('password')?.valueChanges.subscribe(val => this.updateStrength(val));
  }

  updateStrength(value: string) {
    let score = 0;
    if (value?.length >= 8)                                             score++;
    if (/[A-Z]/.test(value))                                           score++;
    if (/[0-9]/.test(value))                                           score++;
    if (/[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]/.test(value))         score++;
    if (value?.length >= 12)                                           score++;

    switch (score) {
      case 0:
      case 1: this.passwordStrength = { percent: 20,  color: '#dc2626', label: 'Très faible' }; break;
      case 2: this.passwordStrength = { percent: 40,  color: '#f97316', label: 'Faible'      }; break;
      case 3: this.passwordStrength = { percent: 60,  color: '#eab308', label: 'Moyen'       }; break;
      case 4: this.passwordStrength = { percent: 80,  color: '#22c55e', label: 'Fort'        }; break;
      case 5: this.passwordStrength = { percent: 100, color: '#16a34a', label: 'Très fort'   }; break;
    }
  }

  get pwdErrors() {
    return this.form.get('password')?.errors || {};
  }

  get pwdTouched() {
    return this.form.get('password')?.touched;
  }

  submit() {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }


  if (this.form.value.password !== this.form.value.confirmPassword) {
    this.error = 'Les mots de passe ne correspondent pas.';
    return;
  }

    this.loading = true;
    this.error   = '';
    this.success = '';

    const body: RegisterRequest = {
      nom:      this.form.value.nom,
      email:    this.form.value.email,
      password: this.form.value.password,
      role:     this.form.value.role.toUpperCase()
    };

    this.http.post<AuthResponse>('http://localhost:8081/api/auth/register', body)
      .subscribe({
        next: (res) => {
          this.loading = false;
          this.success = `✅ Compte ${res.role} créé avec succès pour ${res.email}`;
          this.form.reset({ role: 'AGENT' });
          this.passwordStrength = { percent: 0, color: '#e2e8f0', label: '' };
        },
        error: (err) => {
          this.error = err.status === 0
            ? 'Serveur injoignable.'
            : err.error?.message || "Erreur lors de l'inscription";
          this.loading = false;
        }
      });
  }
}
