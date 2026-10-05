import { RenderMode, ServerRoute } from '@angular/ssr';

// Client-side rendering: the app relies on localStorage/JWT auth, which cannot
// be prerendered. The Express server only serves the shell + static assets.
export const serverRoutes: ServerRoute[] = [
  {
    path: '**',
    renderMode: RenderMode.Client
  }
];
