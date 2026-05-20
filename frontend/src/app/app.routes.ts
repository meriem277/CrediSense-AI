import { Routes } from '@angular/router';
import { agentGuard, adminGuard, authGuard } from './guards/auth.guard';
import { Dashboard } from './components/dashboard/dashboard';
import { AdminDashboard } from './components/admin-dashboard/admin-dashboard';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./components/login/login').then(m => m.Login), },
  { path: 'dashboard', component: Dashboard, canActivate: [authGuard, agentGuard] },
  { path: 'admin-dashboard', component: AdminDashboard, canActivate: [authGuard, adminGuard] },
  { path: 'register', loadComponent: () => import('./components/register/register').then(m => m.Register),canActivate: [authGuard, adminGuard]},

  { path: '', redirectTo: 'login', pathMatch: 'full' }
];
