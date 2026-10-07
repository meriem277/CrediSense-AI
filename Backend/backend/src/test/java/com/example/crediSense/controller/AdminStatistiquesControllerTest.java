package com.example.crediSense.controller;

import com.example.crediSense.Service.impl.StatistiquesExcelService;
import com.example.crediSense.Service.impl.StatistiquesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** La période demandée est validée, et l'export est servi comme un vrai fichier Excel à télécharger. */
class AdminStatistiquesControllerTest {

    private StatistiquesService statistiques;
    private StatistiquesExcelService excel;
    private AdminStatistiquesController controller;

    @BeforeEach
    void preparer() {
        statistiques = mock(StatistiquesService.class);
        excel        = mock(StatistiquesExcelService.class);
        controller   = new AdminStatistiquesController(statistiques, excel);
    }

    // ── Période ──────────────────────────────────────────────────────────────

    @Test
    void sans_dates_la_periode_est_les_trente_derniers_jours_aujourd_hui_compris() {
        LocalDate[] p = AdminStatistiquesController.periode(null, null);

        assertEquals(LocalDate.now(), p[1]);
        assertEquals(LocalDate.now().minusDays(29), p[0]);               // 30 jours au total
    }

    @Test
    void les_dates_demandees_sont_utilisees_telles_quelles() {
        LocalDate[] p = AdminStatistiquesController.periode("2026-10-01", "2026-10-31");

        assertEquals(LocalDate.of(2026, 10, 1), p[0]);
        assertEquals(LocalDate.of(2026, 10, 31), p[1]);
    }

    @Test
    void seule_la_date_de_debut_donnee_la_fin_est_aujourd_hui() {
        LocalDate[] p = AdminStatistiquesController.periode(LocalDate.now().minusDays(5).toString(), "");

        assertEquals(LocalDate.now(), p[1]);
        assertEquals(LocalDate.now().minusDays(5), p[0]);
    }

    @Test
    void un_seul_jour_est_une_periode_valide() {
        LocalDate[] p = AdminStatistiquesController.periode("2026-10-05", "2026-10-05");

        assertEquals(p[0], p[1]);
    }

    @Test
    void une_date_mal_ecrite_donne_400_avec_le_format_attendu() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> AdminStatistiquesController.periode("01/10/2026", "2026-10-31"));

        assertEquals(400, e.getStatusCode().value());
        assertTrue(e.getReason().contains("AAAA-MM-JJ"));
    }

    @Test
    void un_debut_apres_la_fin_donne_400() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> AdminStatistiquesController.periode("2026-11-01", "2026-10-01"));

        assertEquals(400, e.getStatusCode().value());
        assertTrue(e.getReason().contains("après"));
    }

    @Test
    void une_periode_trop_longue_donne_400() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> AdminStatistiquesController.periode("2020-01-01", "2026-10-31"));

        assertEquals(400, e.getStatusCode().value());
        assertTrue(e.getReason().contains(String.valueOf(StatistiquesService.JOURS_MAX)));
    }

    // ── Endpoints ────────────────────────────────────────────────────────────

    @Test
    void les_statistiques_sont_calculees_pour_la_periode_demandee() {
        controller.statistiques("2026-10-01", "2026-10-31");

        verify(statistiques).calculer(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
    }

    @Test
    void l_export_est_un_fichier_excel_a_telecharger_avec_un_nom_explicite() {
        StatistiquesService.Rapport rapport = new StatistiquesService.Rapport(null, java.util.List.of());
        when(statistiques.rapport(any(), any())).thenReturn(rapport);
        when(excel.generer(rapport)).thenReturn("PK-xlsx".getBytes());

        ResponseEntity<byte[]> reponse = controller.exporter("2026-10-01", "2026-10-31");

        assertEquals(200, reponse.getStatusCode().value());
        assertEquals(AdminStatistiquesController.TYPE_XLSX, reponse.getHeaders().getContentType().toString());
        assertEquals("attachment; filename=\"Statistiques-credisense-2026-10-01_2026-10-31.xlsx\"",
                reponse.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("PK-xlsx", new String(reponse.getBody()));
        verify(statistiques).rapport(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
    }

    @Test
    void l_export_valide_la_periode_comme_les_statistiques() {
        assertThrows(ResponseStatusException.class, () -> controller.exporter("n'importe quoi", null));
        verifyNoInteractions(excel);
    }

    // ── Accès réservé à l'administrateur ─────────────────────────────────────

    @Test
    void les_routes_d_administration_sont_reservees_au_role_admin() throws Exception {
        String config = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/example/crediSense/jwt/SecurityConfig.java"));

        assertTrue(config.contains(".requestMatchers(\"/api/admin/**\").hasRole(\"ADMIN\")"),
                "/api/admin/** doit exiger le rôle ADMIN");
        assertTrue(AdminStatistiquesController.class.getAnnotation(
                org.springframework.web.bind.annotation.RequestMapping.class).value()[0].startsWith("/api/admin/"),
                "le contrôleur doit vivre sous /api/admin/ pour être protégé");
    }
}
