// src/app/models/auth.model.ts
export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  nom: string;
  email: string;
  password: string;
  role: 'AGENT' | 'ADMIN';
}

export interface AuthResponse {
  token: string;
  email: string;
  nom: string;
  role: string;
}

export interface DecodedToken {
  sub: string;
  role: string;
  nom: string;
  exp: number;
}
