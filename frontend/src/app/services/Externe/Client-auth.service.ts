import { Inject, Injectable, PLATFORM_ID } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface ClientUser {
  id:       string;
  email:    string;
  nom:      string;
  cin:      string;
  prenom:   string;
  photoUrl: string;
  provider: string;
}

export interface ClientAuthResponse {
  token:    string;
  id:       string;
  email:    string;
  nom:      string;
  cin:      string;
  prenom:   string;
  photoUrl: string;
  provider: string;
  role:     string;
}

export interface RegisterResponse {
  message: string;
  email:   string;
}

@Injectable({ providedIn: 'root' })
export class ClientAuthService {

  private readonly apiUrl    = `${environment.apiUrl}/api/client-auth`;
  private readonly TOKEN_KEY = 'client_token';
  private readonly USER_KEY  = 'client_user';
  private isBrowser: boolean;  // ✅ déclaré

  constructor(
    private http: HttpClient,
    private router: Router,
    @Inject(PLATFORM_ID) platformId: Object
  ) {
    this.isBrowser = isPlatformBrowser(platformId);
  }

  // ── Inscription ───────────────────────────────────────────────────────
  // ⚠️ Ne retourne plus de token : le compte doit être vérifié par email
  // avant toute connexion, donc pas de saveSession() ici.
  register(email: string, password: string,
           nom: string, prenom: string): Observable<RegisterResponse> {
    return this.http.post<RegisterResponse>(
      `${this.apiUrl}/register`, { email, password, nom, prenom }
    );
  }

  // ── Connexion email/password ──────────────────────────────────────────
  login(email: string, password: string): Observable<ClientAuthResponse> {
    return this.http.post<ClientAuthResponse>(
      `${this.apiUrl}/login`, { email, password }
    ).pipe(tap(res => this.saveSession(res)));
  }

  // ── Connexion Google ──────────────────────────────────────────────────
  loginWithGoogle(googleToken: string): Observable<ClientAuthResponse> {
    return this.http.post<ClientAuthResponse>(
      `${this.apiUrl}/google`, { googleToken }
    ).pipe(tap(res => this.saveSession(res)));
  }

  // ── Vérification email ────────────────────────────────────────────────
  verifyEmail(token: string): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/verify-email`, { token });
  }

  resendVerification(email: string): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/resend-verification`, { email });
  }

  // ── Session ───────────────────────────────────────────────────────────
  saveSession(res: ClientAuthResponse): void {
    if (!this.isBrowser) return;  // ✅
    localStorage.setItem(this.TOKEN_KEY, res.token);
    localStorage.setItem(this.USER_KEY, JSON.stringify({
      id:       res.id,
      email:    res.email,
      nom:      res.nom,
       cin: res.cin,

      prenom:   res.prenom,
      photoUrl: res.photoUrl,
      provider: res.provider
    }));
  }

  getToken(): string | null {
    return this.isBrowser ? localStorage.getItem(this.TOKEN_KEY) : null;  // ✅
  }

  getUser(): ClientUser | null {
    if (!this.isBrowser) return null;  // ✅
    const stored = localStorage.getItem(this.USER_KEY);
    return stored ? JSON.parse(stored) : null;
  }

  isLoggedIn(): boolean {
    return !!this.getToken();
  }


  logout(): void {
    if (this.isBrowser) {  // ✅
      localStorage.removeItem(this.TOKEN_KEY);
      localStorage.removeItem(this.USER_KEY);
    }
    this.router.navigate(['/client/login']);
  }
  forgotPassword(email: string): Observable<any> {
  return this.http.post(`${this.apiUrl}/forgot-password`, { email });
}

resetPassword(token: string, password: string): Observable<any> {
  return this.http.post(`${this.apiUrl}/reset-password`, { token, password });
}
}
