import { Routes } from '@angular/router';
import { agentGuard, adminGuard, authGuard } from './guards/auth.guard';
import { Dashboard } from './components/dashboard/dashboard';
import { AdminDashboard } from './components/Interne/admin-dashboard/admin-dashboard';

export const routes: Routes = [

 // { path: '', redirectTo: 'login', pathMatch: 'full' },

  // ── INTERNE ──────────────────────────────────────────────────────────
  {
    path: 'login',
    loadComponent: () => import('./components/Interne/login/login').then(m => m.Login)
  },
 {
  path: 'register',
  loadComponent: () => import('./components/Interne/register/register').then(m => m.Register),
},
{
  path: 'reset-password',
  loadComponent: () => import('./components/Interne/reset-password/reset-password')
    .then(m => m.ResetPassword)
},
  {
    path: 'dashboard',
    component: Dashboard,
    canActivate: [authGuard, agentGuard]
  },
  {
    path: 'admin-dashboard',
    component: AdminDashboard,
    canActivate: [authGuard, adminGuard]
  },

  // ── EXTERNE — Portail Client ──────────────────────────────────────────
  {
    path: 'client',
    children: [
    //  { path: '', redirectTo: 'login', pathMatch: 'full' },
      {
        path: 'login',
        loadComponent: () => import('./components/Externe/clientloginetregister/clientloginetregister')
          .then(m => m.Clientloginetregister)
      },
      {
        path: 'demande',
        loadComponent: () => import('./components/Externe/client-portal/demande/demande')
          .then(m => m.Demande)
      },
      {
        path: 'confirmation',
        loadComponent: () => import('./components/Externe/client-portal/confirmation/confirmation')
          .then(m => m.Confirmation)
      },
      {
        path: 'reset-password',
        loadComponent: () => import('./components/Externe/reset-password/reset-password')
          .then(m => m.ResetPassword)
      },
      {
  path: 'historique',
  loadComponent: () => import('./components/Externe/client-portal/historique/historique')
    .then(m => m.Historique)
},
    ]
  },

 // { path: '**', redirectTo: 'login' }
];
