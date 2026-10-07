package com.example.crediSense.controller;

import com.example.crediSense.Service.impl.StatistiquesExcelService;
import com.example.crediSense.Service.impl.StatistiquesService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Tableau de bord de l'administrateur : statistiques des décisions et export Excel.
 * Réservé au rôle ADMIN (SecurityConfig : /api/admin/**).
 */
@RestController
@RequestMapping("/api/admin/statistiques")
@RequiredArgsConstructor
public class AdminStatistiquesController {

    public static final String TYPE_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final int PERIODE_PAR_DEFAUT_JOURS = 30;

    private final StatistiquesService statistiquesService;
    private final StatistiquesExcelService excelService;

    /** `du` et `au` au format AAAA-MM-JJ, inclus. Par défaut : les 30 derniers jours. */
    @GetMapping
    public StatistiquesService.Statistiques statistiques(
            @RequestParam(name = "du", required = false) String du,
            @RequestParam(name = "au", required = false) String au) {
        LocalDate[] periode = periode(du, au);
        return statistiquesService.calculer(periode[0], periode[1]);
    }

    @GetMapping("/export.xlsx")
    public ResponseEntity<byte[]> exporter(
            @RequestParam(name = "du", required = false) String du,
            @RequestParam(name = "au", required = false) String au) {
        LocalDate[] periode = periode(du, au);
        byte[] fichier = excelService.generer(statistiquesService.rapport(periode[0], periode[1]));

        String nom = "Statistiques-credisense-" + periode[0] + "_" + periode[1] + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(TYPE_XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nom + "\"")
                .body(fichier);
    }

    /** Lit et valide la période demandée ; 400 avec un message clair si elle est incohérente. */
    static LocalDate[] periode(String du, String au) {
        try {
            LocalDate fin   = au == null || au.isBlank() ? LocalDate.now() : LocalDate.parse(au.trim());
            LocalDate debut = du == null || du.isBlank() ? fin.minusDays(PERIODE_PAR_DEFAUT_JOURS - 1L)
                                                          : LocalDate.parse(du.trim());
            if (debut.isAfter(fin)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La date de début (" + debut + ") est après la date de fin (" + fin + ").");
            }
            if (ChronoUnit.DAYS.between(debut, fin) + 1 > StatistiquesService.JOURS_MAX) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La période ne peut pas dépasser " + StatistiquesService.JOURS_MAX + " jours.");
            }
            return new LocalDate[]{debut, fin};
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Dates attendues au format AAAA-MM-JJ (par exemple 2026-10-31).");
        }
    }
}
