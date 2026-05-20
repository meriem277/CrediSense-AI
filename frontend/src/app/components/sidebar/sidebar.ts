import { Component, OnInit } from '@angular/core';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-sidebar',
  imports: [],
  templateUrl: './sidebar.html',
  styleUrl: './sidebar.scss',
})
export class Sidebar   implements OnInit{
    nom = '';
  role = '';
  initiales = '';

  constructor(private auth: AuthService) {}

  ngOnInit() {
    const user = this.auth.getUser();
    if (user) {
      this.nom = user.nom;
      this.role = user.role;
      // ✅ Génère les initiales depuis le nom (ex: "Amira Khalil" → "AK")
      this.initiales = user.nom
        .split(' ')
        .map((n: string) => n[0])
        .join('')
        .toUpperCase()
        .slice(0, 2);
    }
  }
  toggleDarkMode(event: any) {
  if (event.target.checked) {
    document.body.classList.add('dark');
  } else {
    document.body.classList.remove('dark');
  }
}
}
