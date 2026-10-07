package com.example.crediSense.Service.impl;

import com.example.crediSense.Service.impl.StatistiquesService.Delais;
import com.example.crediSense.Service.impl.StatistiquesService.LigneDecision;
import com.example.crediSense.Service.impl.StatistiquesService.Rapport;
import com.example.crediSense.Service.impl.StatistiquesService.Statistiques;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;

/**
 * Export Excel (.xlsx) du tableau de bord : une feuille de synthèse, le détail de chaque décision
 * (filtrable, avec de vraies dates et de vrais nombres, pas du texte), les motifs et l'évolution.
 *
 * Le fichier contient les noms des clients : il n'est servi qu'à l'administrateur. Il ne contient
 * ni CIN, ni adresse, ni document.
 */
@Service
public class StatistiquesExcelService {

    private static final String[] LIBELLE_DECISION = {"ELIGIBLE", "Éligible", "CONDITIONNEL", "Conditionnel",
            "REFUS", "Refus", "A_COMPLETER", "À compléter", "INDETERMINE", "Indéterminée"};

    public byte[] generer(Rapport rapport) {
        try (XSSFWorkbook classeur = new XSSFWorkbook(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Styles styles = new Styles(classeur);
            synthese(classeur, styles, rapport.statistiques());
            decisions(classeur, styles, rapport.lignes());
            motifs(classeur, styles, rapport.statistiques());
            evolution(classeur, styles, rapport.statistiques());
            classeur.write(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Génération du fichier Excel impossible : " + e.getMessage(), e);
        }
    }

    // ── Feuilles ─────────────────────────────────────────────────────────────

    private void synthese(XSSFWorkbook classeur, Styles s, Statistiques st) {
        Sheet feuille = classeur.createSheet("Synthèse");
        int l = 0;

        Row titre = feuille.createRow(l++);
        texte(titre, 0, "Attijari Bank — CrediSense : statistiques des décisions de crédit", s.titre);
        Row periode = feuille.createRow(l++);
        texte(periode, 0, "Période : du " + st.periode().du() + " au " + st.periode().au()
                + " (" + st.periode().jours() + " jours)", s.normal);
        l++;

        l = section(feuille, l, "Volumes", s);
        l = ligne(feuille, l, "Dossiers déposés", st.dossiersDeposes(), s.entier, s);
        l = ligne(feuille, l, "Décisions rendues", st.decisionsTotal(), s.entier, s);
        l = ligne(feuille, l, "dont décisions définitives (éligible, conditionnel, refus)", st.decisionsDefinitives(), s.entier, s);
        for (int i = 0; i < LIBELLE_DECISION.length; i += 2) {
            l = ligne(feuille, l, "   " + LIBELLE_DECISION[i + 1], st.parDecision().getOrDefault(LIBELLE_DECISION[i], 0), s.entier, s);
        }
        l++;

        l = section(feuille, l, "Taux (sur les décisions définitives)", s);
        l = ligneNombre(feuille, l, "Taux d'acceptation (éligibles)", st.tauxAcceptation(), s.pourcent, s);
        l = ligneNombre(feuille, l, "Taux d'acceptation avec conditions (éligibles + conditionnels)",
                st.tauxAcceptationAvecConditions(), s.pourcent, s);
        l = ligneNombre(feuille, l, "Taux de refus", st.tauxRefus(), s.pourcent, s);
        l++;

        l = section(feuille, l, "Délais (en heures)", s);
        l = delais(feuille, l, "Délai de traitement (dépôt → décision)", st.delaiTraitement(), s);
        l = delais(feuille, l, "Délai d'envoi (décision → réponse envoyée au client)", st.delaiEnvoi(), s);
        l++;

        l = section(feuille, l, "Réponses aux clients", s);
        l = ligne(feuille, l, "Envoyées", st.envois().envoyes(), s.entier, s);
        l = ligne(feuille, l, "En attente de validation (sur la période)", st.envois().enAttenteValidation(), s.entier, s);
        l = ligne(feuille, l, "Envoi programmé", st.envois().programmes(), s.entier, s);
        l = ligne(feuille, l, "Annulées par un agent", st.envois().annules(), s.entier, s);
        l = ligne(feuille, l, "Échecs d'envoi", st.envois().echecs(), s.entier, s);
        l = ligne(feuille, l, "Non envoyées (client sans adresse)", st.envois().nonEnvoyes(), s.entier, s);
        l = ligne(feuille, l, "Aucun envoi tenté", st.envois().sansEnvoi(), s.entier, s);
        l = ligne(feuille, l, "Réponses en attente de validation, toutes périodes", (int) st.envois().enAttenteValidationTotal(), s.entier, s);
        l++;

        l = section(feuille, l, "Montant", s);
        ligneNombre(feuille, l, "Montant moyen demandé (DT)", st.montantMoyenDemande(), s.montant, s);

        feuille.setColumnWidth(0, 62 * 256);
        feuille.setColumnWidth(1, 16 * 256);
        feuille.setColumnWidth(2, 16 * 256);
        feuille.setColumnWidth(3, 16 * 256);
        feuille.setColumnWidth(4, 12 * 256);
    }

    private void decisions(XSSFWorkbook classeur, Styles s, java.util.List<LigneDecision> lignes) {
        Sheet feuille = classeur.createSheet("Décisions");
        String[] entetes = {"Référence", "Client", "Déposé le", "Décision le", "Délai (h)", "Décision", "Score",
                "Montant demandé (DT)", "Durée (mois)", "Motif principal", "Statut de la réponse",
                "Réponse envoyée le", "Mode d'envoi", "Version des règles"};
        Row entete = feuille.createRow(0);
        for (int c = 0; c < entetes.length; c++) texte(entete, c, entetes[c], s.entete);

        int l = 1;
        for (LigneDecision d : lignes) {
            Row r = feuille.createRow(l++);
            texte(r, 0, d.reference(), s.normal);
            texte(r, 1, d.client(), s.normal);
            date(r, 2, d.depotLe(), s);
            date(r, 3, d.decisionLe(), s);
            nombre(r, 4, d.delaiHeures(), s.decimal);
            texte(r, 5, libelleDecision(d.decision()), s.normal);
            nombre(r, 6, d.score(), s.entier);
            nombre(r, 7, d.montant(), s.montant);
            nombre(r, 8, d.duree() != null ? d.duree().doubleValue() : null, s.entier);
            texte(r, 9, d.motifPrincipal(), s.normal);
            texte(r, 10, libelleStatut(d.statutEmail()), s.normal);
            date(r, 11, d.emailEnvoyeLe(), s);
            texte(r, 12, libelleMode(d.modeEnvoi()), s.normal);
            texte(r, 13, d.versionRegles(), s.normal);
        }
        int[] largeurs = {12, 26, 18, 18, 11, 14, 8, 20, 13, 46, 24, 18, 22, 18};
        for (int c = 0; c < largeurs.length; c++) feuille.setColumnWidth(c, largeurs[c] * 256);
        feuille.createFreezePane(0, 1);
        if (!lignes.isEmpty()) feuille.setAutoFilter(new CellRangeAddress(0, lignes.size(), 0, entetes.length - 1));
    }

    private void motifs(XSSFWorkbook classeur, Styles s, Statistiques st) {
        Sheet feuille = classeur.createSheet("Motifs");
        Row entete = feuille.createRow(0);
        texte(entete, 0, "Décision", s.entete);
        texte(entete, 1, "Motif", s.entete);
        texte(entete, 2, "Nombre de dossiers", s.entete);
        int l = 1;
        for (var m : st.motifs()) {
            Row r = feuille.createRow(l++);
            texte(r, 0, libelleDecision(m.decision()), s.normal);
            texte(r, 1, m.motif(), s.normal);
            nombre(r, 2, (double) m.nombre(), s.entier);
        }
        feuille.setColumnWidth(0, 16 * 256);
        feuille.setColumnWidth(1, 70 * 256);
        feuille.setColumnWidth(2, 20 * 256);
        feuille.createFreezePane(0, 1);
    }

    private void evolution(XSSFWorkbook classeur, Styles s, Statistiques st) {
        Sheet feuille = classeur.createSheet("Évolution");
        Row entete = feuille.createRow(0);
        String[] titres = {"SEMAINE".equals(st.granularite()) ? "Semaine du" : "Jour", "Éligible", "Conditionnel",
                "Refus", "À compléter", "Total"};
        for (int c = 0; c < titres.length; c++) texte(entete, c, titres[c], s.entete);
        int l = 1;
        for (var p : st.evolution()) {
            Row r = feuille.createRow(l++);
            Cell jour = r.createCell(0);
            jour.setCellValue(Date.from(LocalDate.parse(p.date()).atStartOfDay(ZoneId.systemDefault()).toInstant()));
            jour.setCellStyle(s.jour);
            nombre(r, 1, (double) p.eligible(), s.entier);
            nombre(r, 2, (double) p.conditionnel(), s.entier);
            nombre(r, 3, (double) p.refus(), s.entier);
            nombre(r, 4, (double) p.aCompleter(), s.entier);
            nombre(r, 5, (double) p.total(), s.entier);
        }
        for (int c = 0; c < titres.length; c++) feuille.setColumnWidth(c, 16 * 256);
        feuille.createFreezePane(0, 1);
    }

    // ── Cellules ─────────────────────────────────────────────────────────────

    private int section(Sheet f, int l, String titre, Styles s) {
        texte(f.createRow(l), 0, titre, s.section);
        return l + 1;
    }

    private int ligne(Sheet f, int l, String libelle, int valeur, CellStyle style, Styles s) {
        Row r = f.createRow(l);
        texte(r, 0, libelle, s.normal);
        nombre(r, 1, (double) valeur, style);
        return l + 1;
    }

    private int ligneNombre(Sheet f, int l, String libelle, Double valeur, CellStyle style, Styles s) {
        Row r = f.createRow(l);
        texte(r, 0, libelle, s.normal);
        if (valeur == null) texte(r, 1, "—", s.droite); else nombre(r, 1, valeur, style);
        return l + 1;
    }

    private int delais(Sheet f, int l, String libelle, Delais d, Styles s) {
        Row r = f.createRow(l);
        texte(r, 0, libelle, s.normal);
        if (d.echantillon() == 0) {
            texte(r, 1, "—", s.droite);
            return l + 1;
        }
        nombre(r, 1, d.moyenneHeures(), s.decimal);
        nombre(r, 2, d.medianeHeures(), s.decimal);
        nombre(r, 3, d.maxHeures(), s.decimal);
        nombre(r, 4, (double) d.echantillon(), s.entier);
        Row legende = f.createRow(l + 1);
        texte(legende, 1, "moyenne", s.legende);
        texte(legende, 2, "médiane", s.legende);
        texte(legende, 3, "maximum", s.legende);
        texte(legende, 4, "dossiers", s.legende);
        return l + 2;
    }

    private void texte(Row r, int colonne, String valeur, CellStyle style) {
        Cell c = r.createCell(colonne);
        c.setCellValue(valeur == null ? "" : valeur);
        c.setCellStyle(style);
    }

    private void nombre(Row r, int colonne, Double valeur, CellStyle style) {
        Cell c = r.createCell(colonne);
        if (valeur == null) {
            c.setBlank();
        } else {
            c.setCellValue(valeur);
        }
        c.setCellStyle(style);
    }

    private void date(Row r, int colonne, LocalDateTime valeur, Styles s) {
        Cell c = r.createCell(colonne);
        if (valeur == null) {
            c.setBlank();
        } else {
            c.setCellValue(Date.from(valeur.atZone(ZoneId.systemDefault()).toInstant()));
        }
        c.setCellStyle(s.dateHeure);
    }

    // ── Libellés ─────────────────────────────────────────────────────────────

    static String libelleDecision(String decision) {
        for (int i = 0; i < LIBELLE_DECISION.length; i += 2) {
            if (LIBELLE_DECISION[i].equals(decision)) return LIBELLE_DECISION[i + 1];
        }
        return decision == null ? "" : decision;
    }

    static String libelleStatut(String statut) {
        if (statut == null) return "Aucun envoi tenté";
        return switch (statut) {
            case "ENVOYE"                -> "Envoyée";
            case "ECHEC"                 -> "Échec d'envoi";
            case "NON_ENVOYE"            -> "Non envoyée (pas d'adresse)";
            case "EN_ATTENTE_VALIDATION" -> "En attente de validation";
            case "PROGRAMME"             -> "Envoi programmé";
            case "ANNULE"                -> "Annulée par un agent";
            default                      -> statut;
        };
    }

    static String libelleMode(String mode) {
        if (mode == null) return "";
        return switch (mode) {
            case "AUTO"       -> "Automatique";
            case "DELAI"      -> "Après délai";
            case "VALIDATION" -> "Après validation";
            case "MANUEL"     -> "À la demande de l'agent";
            default           -> mode;
        };
    }

    // ── Styles ───────────────────────────────────────────────────────────────

    private static final class Styles {
        final CellStyle titre, section, entete, normal, droite, legende, entier, decimal, montant, pourcent,
                dateHeure, jour;

        Styles(XSSFWorkbook classeur) {
            CreationHelper aide = classeur.getCreationHelper();
            short formatDate  = aide.createDataFormat().getFormat("dd/mm/yyyy hh:mm");
            short formatJour  = aide.createDataFormat().getFormat("dd/mm/yyyy");
            short formatPct   = aide.createDataFormat().getFormat("0.0%");
            short formatDec   = aide.createDataFormat().getFormat("0.0");
            short formatMont  = aide.createDataFormat().getFormat("#,##0.000");
            short formatEnt   = aide.createDataFormat().getFormat("0");

            Font gras = classeur.createFont();
            gras.setBold(true);
            Font grand = classeur.createFont();
            grand.setBold(true);
            grand.setFontHeightInPoints((short) 14);
            Font blanc = classeur.createFont();
            blanc.setBold(true);
            blanc.setColor(IndexedColors.WHITE.getIndex());
            Font gris = classeur.createFont();
            gris.setItalic(true);
            gris.setColor(IndexedColors.GREY_50_PERCENT.getIndex());

            titre   = style(classeur, grand, null, (short) 0, false);
            section = style(classeur, gras, IndexedColors.LIGHT_ORANGE, (short) 0, false);
            entete  = style(classeur, blanc, IndexedColors.GREY_50_PERCENT, (short) 0, true);
            normal  = style(classeur, null, null, (short) 0, false);
            droite  = style(classeur, null, null, (short) 0, false);
            droite.setAlignment(HorizontalAlignment.RIGHT);
            legende = style(classeur, gris, null, (short) 0, false);
            legende.setAlignment(HorizontalAlignment.RIGHT);
            entier  = style(classeur, null, null, formatEnt, false);
            decimal = style(classeur, null, null, formatDec, false);
            montant = style(classeur, null, null, formatMont, false);
            pourcent = style(classeur, gras, null, formatPct, false);
            dateHeure = style(classeur, null, null, formatDate, false);
            jour    = style(classeur, null, null, formatJour, false);
        }

        private static CellStyle style(XSSFWorkbook classeur, Font police, IndexedColors fond, short format,
                                       boolean bordure) {
            CellStyle s = classeur.createCellStyle();
            if (police != null) s.setFont(police);
            if (fond != null) {
                s.setFillForegroundColor(fond.getIndex());
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            if (format != 0) s.setDataFormat(format);
            if (bordure) s.setBorderBottom(BorderStyle.THIN);
            return s;
        }
    }
}
