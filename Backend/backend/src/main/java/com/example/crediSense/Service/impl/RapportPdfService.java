package com.example.crediSense.Service.impl;

import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.Dossier;
import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.FontFactory;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.ColumnText;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfPageEventHelper;
import com.itextpdf.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Génère le rapport PDF d'une décision de crédit, côté serveur (iText).
 *
 * Deux versions du même rapport :
 *  - CLIENT : ce qu'on peut envoyer au client (décision, explication, chiffres de sa demande,
 *             informations à fournir, conditions) ; jamais les contrôles internes, les risques
 *             ni les points de vigilance ;
 *  - AGENT  : le rapport complet pour le conseiller (contrôles réglementaires, capacité,
 *             simulations, points forts et de vigilance, plan recommandé).
 *
 * Le rapport est construit à partir du résultat enregistré (le JSON renvoyé par le service IA) :
 * il peut donc être régénéré à tout moment, sans relancer l'analyse et sans stocker de fichier.
 *
 * Limite : la police standard du PDF (Helvetica) ne contient pas l'alphabet arabe ; un nom écrit
 * en arabe apparaît avec des « ? ». Les caractères français sont tous gérés.
 */
@Slf4j
@Service
public class RapportPdfService {

    public enum Version { CLIENT, AGENT }

    private static final String BANQUE = "Attijari Bank";

    private static final BaseColor ORANGE = new BaseColor(0xE8, 0x61, 0x1A);
    private static final BaseColor GRIS   = new BaseColor(0x6B, 0x72, 0x80);
    private static final BaseColor FOND   = new BaseColor(0xF8, 0xF9, 0xFC);
    private static final BaseColor VERT   = new BaseColor(0x16, 0xA3, 0x4A);
    private static final BaseColor ROUGE  = new BaseColor(0xDC, 0x26, 0x26);
    private static final BaseColor AMBRE  = new BaseColor(0xD9, 0x77, 0x06);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm");

    // ── Point d'entrée ───────────────────────────────────────────────────────

    public byte[] generer(Dossier dossier, Map<String, Object> resultat, Version version) {
        try (ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 40, 40, 40, 55);
            PdfWriter writer = PdfWriter.getInstance(document, sortie);
            writer.setPageEvent(new PiedDePage());
            document.addTitle("Rapport de décision de crédit - " + BANQUE);
            document.addAuthor(BANQUE + " — CrediSense");
            document.open();

            String decision = texte(resultat, "eligibility");

            entete(document, version);
            identification(document, dossier, resultat, version);
            decision(document, decision, resultat, version);

            if (version == Version.AGENT) {
                texteSection(document, writer, "Résumé de l'analyse", texte(resultat, "summary"));
            } else {
                // Dossier conditionnel : le résumé. Le texte long rédigé par l'IA peut citer ses propres
                // calculs, qui diffèrent de ceux du moteur (chiffres de la section suivante).
                String explication = "CONDITIONNEL".equals(decision) && !texte(resultat, "summary").isBlank()
                        ? texte(resultat, "summary") : texte(resultat, "rawExplanation");
                texteSection(document, writer, "Explication", explication);
            }

            manquants(document, writer, resultat, version);
            chiffresCles(document, writer, dossier, resultat, version);

            if (version == Version.AGENT) {
                detailScore(document, writer, resultat);
                controles(document, writer, resultat);
                capaciteEtSimulations(document, writer, resultat);
                propositionsAjustement(document, writer, resultat, version);
                listeSection(document, writer, "Points forts", liste(resultat, "strengths"));
                listeSection(document, writer, "Points de vigilance", liste(resultat, "weaknesses"));
                listeSection(document, writer, "Risques", liste(resultat, "risks"));
                listeSection(document, writer, "Plan recommandé", liste(resultat, "recommendedPlan"));
                listeSection(document, writer, "Avertissements", liste(resultat, "avertissements"));
                texteSection(document, writer, "Note de calcul", texte(resultat, "calculationNote"));
            }
            // Le client reçoit les propositions du moteur (il peut les accepter depuis son espace client)
            if (version == Version.CLIENT && "CONDITIONNEL".equals(decision)) {
                propositionsAjustement(document, writer, resultat, version);
            }
            listeSection(document, writer, "Conditions avant décaissement", liste(resultat, "conditions"));

            mentionFinale(document, version);
            document.close();
            return sortie.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Génération du PDF impossible : " + e.getMessage(), e);
        }
    }

    // ── Blocs du document ────────────────────────────────────────────────────

    private void entete(Document document, Version version) throws DocumentException {
        PdfPTable bandeau = new PdfPTable(1);
        bandeau.setWidthPercentage(100);
        PdfPCell cellule = new PdfPCell();
        cellule.setBackgroundColor(ORANGE);
        cellule.setBorder(Rectangle.NO_BORDER);
        cellule.setPadding(16);
        cellule.addElement(new Paragraph(BANQUE, police(20, Font.BOLD, BaseColor.WHITE)));
        cellule.addElement(new Paragraph(
                version == Version.AGENT
                        ? "CrediSense — Rapport d'analyse de crédit (usage interne)"
                        : "CrediSense — Réponse à votre demande de crédit",
                police(10, Font.NORMAL, BaseColor.WHITE)));
        bandeau.addCell(cellule);
        document.add(bandeau);
        document.add(espace(10));
    }

    private void identification(Document document, Dossier dossier, Map<String, Object> resultat, Version version)
            throws DocumentException {
        Client client = dossier != null ? dossier.getClient() : null;
        String nom = client != null
                ? ((client.getPrenom() != null ? client.getPrenom() : "") + " "
                   + (client.getNom() != null ? client.getNom() : "")).trim()
                : "";

        PdfPTable table = new PdfPTable(new float[]{1.4f, 3f});
        table.setWidthPercentage(100);
        ligne(table, "Client", nom.isEmpty() ? "—" : nom);
        if (version == Version.AGENT && client != null && client.getCin() != null) {
            ligne(table, "CIN", client.getCin());
        }
        ligne(table, "Référence du dossier", dossier != null && dossier.getId() != null
                ? dossier.getId().toString().substring(0, 8).toUpperCase() : "—");
        ligne(table, "Type de crédit", orDefaut(texte(resultat, "creditType"), "CONSOMMATION"));
        ligne(table, "Date du rapport", LocalDateTime.now().format(DATE));
        document.add(table);
        document.add(espace(8));
    }

    private void decision(Document document, String decision, Map<String, Object> resultat, Version version)
            throws DocumentException {
        BaseColor couleur = couleurDecision(decision);

        PdfPTable cadre = new PdfPTable(1);
        cadre.setWidthPercentage(100);
        PdfPCell cellule = new PdfPCell();
        cellule.setBorderColor(couleur);
        cellule.setBorderWidth(1.5f);
        cellule.setPadding(14);
        Paragraph titre = new Paragraph(libelleDecision(decision), police(18, Font.BOLD, couleur));
        titre.setAlignment(Element.ALIGN_CENTER);
        cellule.addElement(titre);

        // Pas de score pour un dossier à compléter : il serait provisoire, et un chiffre provisoire
        // est pris pour un verdict (même règle que l'e-mail)
        if (!"A_COMPLETER".equals(decision)) {
            Double score = nombre(resultat.get("eligibilityScore"));
            if (score != null) {
                Paragraph p = new Paragraph("Score de crédit : " + Math.round(score) + " / 100",
                        police(11, Font.NORMAL, GRIS));
                p.setAlignment(Element.ALIGN_CENTER);
                cellule.addElement(p);
            }
        } else if (version == Version.AGENT) {
            Double score = nombre(resultat.get("eligibilityScore"));
            if (score != null) {
                Paragraph p = new Paragraph("Score provisoire : " + Math.round(score) + " / 100 (non définitif)",
                        police(10, Font.ITALIC, GRIS));
                p.setAlignment(Element.ALIGN_CENTER);
                cellule.addElement(p);
            }
        }
        cadre.addCell(cellule);
        document.add(cadre);
        document.add(espace(8));
    }

    private void manquants(Document document, PdfWriter writer, Map<String, Object> resultat, Version version) throws DocumentException {
        List<Object> manquants = new ArrayList<>(liste(resultat, "donneesManquantes"));
        if (version == Version.CLIENT) {
            // Le taux d'intérêt est un réglage de la banque : le client ne peut pas le fournir
            manquants.removeIf(m -> String.valueOf(m).startsWith("Taux d'intérêt"));
        }
        listeSection(document, writer, version == Version.AGENT ? "Informations à compléter"
                                                         : "Informations à nous fournir", manquants);
    }

    private void chiffresCles(Document document, PdfWriter writer, Dossier dossier, Map<String, Object> resultat, Version version)
            throws DocumentException {
        Map<String, Object> m = carte(resultat, "financialMetrics");

        PdfPTable table = new PdfPTable(new float[]{2f, 2f});
        table.setWidthPercentage(100);

        Object montant = m.get("requestedAmount") != null ? m.get("requestedAmount")
                : (dossier != null ? dossier.getMontantCredit() : null);
        Object duree = m.get("duration") != null ? m.get("duration")
                : (dossier != null ? dossier.getDureeCredit() : null);

        ligne(table, "Montant demandé", dt(montant));
        ligne(table, "Durée", nombre(duree) != null ? Math.round(nombre(duree)) + " mois" : "—");

        Double mensualite = nombre(m.get("monthlyPayment"));
        // Une mensualité nulle n'existe pas : c'est une mensualité qui n'a pas été calculée
        ligne(table, "Mensualité estimée", mensualite != null && mensualite > 0 ? dt(mensualite) : "Non calculée");

        Double taux = nombre(resultat.get("tauxAnnuelApplique"));
        if (taux != null) ligne(table, "Taux d'intérêt annuel appliqué", pourcent(taux * 100));

        if (version == Version.AGENT) {
            ligne(table, "Revenu mensuel net", dt(m.get("monthlyIncome")));
            Double dti = nombre(m.get("dti"));
            ligne(table, "Taux d'endettement", dti != null && dti > 0 ? pourcent(dti) : "Non calculé");
            Double dettes = nombre(m.get("existingDebts"));
            ligne(table, "Dettes existantes (mensuelles)", dettes != null ? dt(dettes) : "Inconnues");
        }

        ouvrirSection(document, writer, "Chiffres de la demande");
        document.add(table);
    }

    private void controles(Document document, PdfWriter writer, Map<String, Object> resultat) throws DocumentException {
        List<Object> controles = liste(resultat, "regulatoryChecks");
        if (controles.isEmpty()) return;

        ouvrirSection(document, writer, "Contrôles réglementaires");
        PdfPTable table = new PdfPTable(new float[]{2.2f, 1.4f, 1.4f, 1.3f, 3.2f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String titre : new String[]{"Critère", "Valeur", "Seuil", "Statut", "Explication"}) {
            enteteCellule(table, titre);
        }
        for (Object o : controles) {
            if (!(o instanceof Map<?, ?> c)) continue;
            String statut = String.valueOf(c.get("status"));
            cellule(table, String.valueOf(c.get("criterion")), Font.BOLD, null);
            cellule(table, String.valueOf(c.get("value")), Font.NORMAL, null);
            cellule(table, String.valueOf(c.get("threshold")), Font.NORMAL, null);
            cellule(table, libelleStatut(statut), Font.BOLD, couleurStatut(statut));
            cellule(table, String.valueOf(c.get("explanation")), Font.NORMAL, null);
        }
        document.add(table);
    }

    /** Détail du score : points obtenus par critère (grille calculée par le code, pas par le modèle de langage). */
    private void detailScore(Document document, PdfWriter writer, Map<String, Object> resultat) throws DocumentException {
        Map<String, Object> detail = carte(resultat, "scoreDetail");
        List<Object> criteres = liste(detail, "criteres");
        if (criteres.isEmpty()) return;

        ouvrirSection(document, writer, "Détail du score");
        PdfPTable table = new PdfPTable(new float[]{3f, 2.2f, 1.6f, 1.6f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String titre : new String[]{"Critère", "Valeur", "Points", "Maximum"}) {
            enteteCellule(table, titre);
        }
        for (Object o : criteres) {
            if (!(o instanceof Map<?, ?> c)) continue;
            boolean connu = !Boolean.FALSE.equals(c.get("connu"));
            Double points = nombre(c.get("points"));
            Double maximum = nombre(c.get("maximum"));
            cellule(table, String.valueOf(c.get("libelle")), Font.BOLD, null);
            cellule(table, String.valueOf(c.get("valeur")), Font.NORMAL, null);
            cellule(table, connu && points != null ? String.valueOf(Math.round(points)) : "écarté", Font.NORMAL, null);
            cellule(table, maximum != null ? String.valueOf(Math.round(maximum)) : "—", Font.NORMAL, null);
        }
        document.add(table);

        Double total = nombre(detail.get("total"));
        boolean provisoire = Boolean.TRUE.equals(detail.get("provisoire"));
        Paragraph resume = new Paragraph("Score : " + (total != null ? Math.round(total) : "—") + " / 100"
                + (provisoire ? " (provisoire : un ou plusieurs critères inconnus sont écartés du calcul)" : ""),
                police(10, Font.BOLD, BaseColor.DARK_GRAY));
        resume.setSpacingBefore(4);
        document.add(resume);
        if (Boolean.TRUE.equals(detail.get("prototype"))) {
            Paragraph note = new Paragraph("Prototype : les poids de la grille et les seuils sont des valeurs par défaut indicatives, "
                    + "à valider avec la banque avant tout usage réel.", police(9, Font.ITALIC, BaseColor.GRAY));
            note.setSpacingAfter(2);
            document.add(note);
        }
    }

    private void capaciteEtSimulations(Document document, PdfWriter writer, Map<String, Object> resultat) throws DocumentException {
        Map<String, Object> capacite = carte(resultat, "capacity");
        if (!capacite.isEmpty()) {
            ouvrirSection(document, writer, "Capacité d'emprunt");
            PdfPTable table = new PdfPTable(new float[]{2f, 2f});
            table.setWidthPercentage(100);
            ligne(table, "Mensualité maximale", dt(capacite.get("maxMonthlyPayment")));
            ligne(table, "Reste après la demande", dt(capacite.get("remainingMonthly")));
            Double ref = nombre(capacite.get("referenceDuration"));
            ligne(table, "Montant empruntable" + (ref != null ? " (" + Math.round(ref) + " mois)" : ""),
                    dt(capacite.get("maxAmountForDuration")));
            document.add(table);
        }

        List<Object> simulations = liste(resultat, "simulations");
        if (simulations.isEmpty()) return;

        ouvrirSection(document, writer, "Simulation par durée");
        PdfPTable table = new PdfPTable(new float[]{1.2f, 2f, 1.5f, 2f, 1.5f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String titre : new String[]{"Durée", "Mensualité", "Endettement", "Coût total", "Statut"}) {
            enteteCellule(table, titre);
        }
        for (Object o : simulations) {
            if (!(o instanceof Map<?, ?> s)) continue;
            String statut = String.valueOf(s.get("status"));
            boolean demandee = Boolean.TRUE.equals(s.get("isRequested"));
            Double duree = nombre(s.get("duration"));
            cellule(table, (duree != null ? Math.round(duree) + " mois" : "—") + (demandee ? " (demandée)" : ""),
                    demandee ? Font.BOLD : Font.NORMAL, null);
            cellule(table, dt(s.get("monthlyPayment")), Font.NORMAL, null);
            Double dti = nombre(s.get("dti"));
            cellule(table, dti != null ? pourcent(dti) : "—", Font.NORMAL, null);
            cellule(table, dt(s.get("totalCost")), Font.NORMAL, null);
            cellule(table, libelleStatut(statut), Font.BOLD, couleurStatut(statut));
        }
        document.add(table);
    }

    /**
     * Propositions de montant / durée d'un dossier conditionnel. L'agent les voit comme « indicatives » ; le
     * client les reçoit comme des propositions auxquelles il répond depuis son espace client.
     */
    private void propositionsAjustement(Document document, PdfWriter writer, Map<String, Object> resultat,
                                        Version version) throws DocumentException {
        Map<String, Object> propositions = carte(resultat, "adjustedOffers");
        if (propositions.isEmpty()) return;
        boolean client = version == Version.CLIENT;
        // Pour le client : seulement s'il y a réellement des offres (pas le message interne « rien à ajuster »)
        if (client && (!Boolean.TRUE.equals(propositions.get("applicable")) || liste(propositions, "offers").isEmpty())) return;

        ouvrirSection(document, writer, client ? "Propositions de votre conseiller" : "Propositions d'ajustement");
        String message = client
                ? "Pour adapter votre crédit à votre capacité de remboursement, voici nos propositions. Aucune n'est appliquée "
                  + "sans votre accord : vous pouvez en accepter une ou les refuser depuis votre espace client."
                : texte(propositions, "message");
        if (message != null && !message.isBlank()) {
            Paragraph p = new Paragraph(nettoyer(message), police(10, Font.ITALIC, BaseColor.DARK_GRAY));
            p.setSpacingAfter(4);
            document.add(p);
        }

        List<Object> offres = liste(propositions, "offers");
        if (offres.isEmpty()) return;

        PdfPTable table = new PdfPTable(new float[]{3f, 1.8f, 1.2f, 1.8f, 1.5f, 1.8f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String titre : new String[]{"Proposition", "Montant", "Durée", "Mensualité", "Endettement", "Coût total"}) {
            enteteCellule(table, titre);
        }
        for (Object o : offres) {
            if (!(o instanceof Map<?, ?> offre)) continue;
            cellule(table, String.valueOf(offre.get("label")), Font.BOLD, null);
            cellule(table, dt(offre.get("amount")), Font.NORMAL, null);
            Double duree = nombre(offre.get("duration"));
            cellule(table, duree != null ? Math.round(duree) + " mois" : "—", Font.NORMAL, null);
            cellule(table, dt(offre.get("monthlyPayment")), Font.NORMAL, null);
            Double dti = nombre(offre.get("dti"));
            cellule(table, dti != null ? pourcent(dti) : "—", Font.NORMAL, null);
            cellule(table, dt(offre.get("totalCost")), Font.NORMAL, null);
        }
        document.add(table);
    }

    private void listeSection(Document document, PdfWriter writer, String titre, List<Object> elements) throws DocumentException {
        if (elements.isEmpty()) return;
        ouvrirSection(document, writer, titre);
        for (Object element : elements) {
            String ligne = libelleElement(element);
            if (ligne.isBlank()) continue;
            Paragraph p = new Paragraph("•  " + nettoyer(ligne), police(10, Font.NORMAL, BaseColor.DARK_GRAY));
            p.setIndentationLeft(8);
            p.setSpacingAfter(2);
            document.add(p);
        }
    }

    private void texteSection(Document document, PdfWriter writer, String titre, String contenu) throws DocumentException {
        if (contenu == null || contenu.isBlank()) return;
        ouvrirSection(document, writer, titre);
        PdfPTable cadre = new PdfPTable(1);
        cadre.setWidthPercentage(100);
        PdfPCell cellule = new PdfPCell(new Phrase(nettoyer(contenu), police(10, Font.NORMAL, BaseColor.DARK_GRAY)));
        cellule.setBackgroundColor(FOND);
        cellule.setBorder(Rectangle.NO_BORDER);
        cellule.setPadding(10);
        cellule.setLeading(0, 1.35f);
        cadre.addCell(cellule);
        document.add(cadre);
    }

    private void mentionFinale(Document document, Version version) throws DocumentException {
        document.add(espace(14));
        String mention = version == Version.CLIENT
                ? "Cette réponse est issue d'une analyse automatisée de votre dossier. Pour toute question, "
                  + "contactez votre conseiller en agence ou le 71 141 400."
                : "Analyse automatisée : les chiffres sont calculés par le moteur de règles, le score et "
                  + "l'explication proviennent du modèle de langage. À valider par un conseiller.";
        Paragraph p = new Paragraph(mention, police(9, Font.ITALIC, GRIS));
        document.add(p);
    }

    // ── Aides de mise en page ────────────────────────────────────────────────

    /**
     * Ouvre une section : son titre ne reste jamais seul en bas de page. S'il reste moins de
     * 110 points avant la marge, on passe à la page suivante.
     */
    private void ouvrirSection(Document document, PdfWriter writer, String titre) throws DocumentException {
        if (writer.getVerticalPosition(true) - document.bottomMargin() < 110) {
            document.newPage();
        }
        document.add(titreSection(titre));
    }

    private Paragraph titreSection(String titre) {
        Paragraph p = new Paragraph(titre.toUpperCase(Locale.FRENCH), police(11, Font.BOLD, ORANGE));
        p.setSpacingBefore(12);
        p.setSpacingAfter(5);
        return p;
    }

    private Paragraph espace(float hauteur) {
        Paragraph p = new Paragraph(" ");
        p.setLeading(hauteur);
        return p;
    }

    private void ligne(PdfPTable table, String libelle, String valeur) {
        PdfPCell gauche = new PdfPCell(new Phrase(libelle, police(10, Font.NORMAL, GRIS)));
        PdfPCell droite = new PdfPCell(new Phrase(nettoyer(valeur), police(10, Font.BOLD, BaseColor.DARK_GRAY)));
        for (PdfPCell c : new PdfPCell[]{gauche, droite}) {
            c.setBorder(Rectangle.BOTTOM);
            c.setBorderColor(new BaseColor(0xE5, 0xE7, 0xEB));
            c.setPadding(5);
        }
        table.addCell(gauche);
        table.addCell(droite);
    }

    private void enteteCellule(PdfPTable table, String titre) {
        PdfPCell c = new PdfPCell(new Phrase(titre, police(9, Font.BOLD, BaseColor.WHITE)));
        c.setBackgroundColor(GRIS);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(5);
        table.addCell(c);
    }

    private void cellule(PdfPTable table, String texte, int style, BaseColor couleur) {
        PdfPCell c = new PdfPCell(new Phrase(nettoyer(texte),
                police(9, style, couleur != null ? couleur : BaseColor.DARK_GRAY)));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(new BaseColor(0xE5, 0xE7, 0xEB));
        c.setPadding(4);
        table.addCell(c);
    }

    private Font police(float taille, int style, BaseColor couleur) {
        return FontFactory.getFont(FontFactory.HELVETICA, taille, style, couleur);
    }

    /** Pied de page : nom de la banque, mention de confidentialité et numéro de page. */
    private class PiedDePage extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte canevas = writer.getDirectContent();
            Phrase gauche = new Phrase(BANQUE + " — CrediSense · Document confidentiel", police(8, Font.NORMAL, GRIS));
            Phrase droite = new Phrase("Page " + writer.getPageNumber(), police(8, Font.NORMAL, GRIS));
            ColumnText.showTextAligned(canevas, Element.ALIGN_LEFT, gauche, document.leftMargin(), 28, 0);
            ColumnText.showTextAligned(canevas, Element.ALIGN_RIGHT, droite,
                    document.getPageSize().getWidth() - document.rightMargin(), 28, 0);
        }
    }

    // ── Libellés et conversions ──────────────────────────────────────────────

    static String libelleDecision(String decision) {
        return switch (decision == null ? "" : decision) {
            case "ELIGIBLE"     -> "ÉLIGIBLE";
            case "REFUS"        -> "REFUS";
            case "CONDITIONNEL" -> "CONDITIONNEL";
            case "A_COMPLETER"  -> "DOSSIER À COMPLÉTER";
            default             -> "EN COURS D'ANALYSE";
        };
    }

    private static BaseColor couleurDecision(String decision) {
        return switch (decision == null ? "" : decision) {
            case "ELIGIBLE" -> VERT;
            case "REFUS"    -> ROUGE;
            case "CONDITIONNEL", "A_COMPLETER" -> AMBRE;
            default -> GRIS;
        };
    }

    private static String libelleStatut(String statut) {
        return switch (statut) {
            case "OK"         -> "Conforme";
            case "ATTENTION"  -> "Attention";
            case "KO"         -> "Non conforme";
            case "A_VERIFIER" -> "À vérifier";
            default           -> statut;
        };
    }

    private static BaseColor couleurStatut(String statut) {
        return switch (statut) {
            case "OK"        -> VERT;
            case "KO"        -> ROUGE;
            case "ATTENTION" -> AMBRE;
            default          -> GRIS;
        };
    }

    /** Montant en dinars, 3 décimales, séparateurs ASCII (l'espace fine insécable n'existe pas dans la police). */
    static String dt(Object valeur) {
        Double d = nombre(valeur);
        if (d == null) return "—";
        DecimalFormatSymbols symboles = new DecimalFormatSymbols(Locale.FRANCE);
        symboles.setGroupingSeparator(' ');
        symboles.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.000", symboles).format(d) + " DT";
    }

    private static String pourcent(double valeur) {
        DecimalFormatSymbols symboles = new DecimalFormatSymbols(Locale.FRANCE);
        symboles.setDecimalSeparator(',');
        return new DecimalFormat("0.##", symboles).format(valeur) + " %";
    }

    private static Double nombre(Object valeur) {
        if (valeur == null) return null;
        try {
            double d = valeur instanceof Number n ? n.doubleValue() : Double.parseDouble(valeur.toString().trim());
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String texte(Map<String, Object> m, String cle) {
        Object v = m == null ? null : m.get(cle);
        return v == null ? "" : v.toString();
    }

    private static String orDefaut(String valeur, String defaut) {
        return valeur == null || valeur.isBlank() ? defaut : valeur;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> liste(Map<String, Object> m, String cle) {
        Object v = m == null ? null : m.get(cle);
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> carte(Map<String, Object> m, String cle) {
        Object v = m == null ? null : m.get(cle);
        return v instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
    }

    /** Un point fort, un risque ou une action : texte simple ou objet {title, detail, description, action…}. */
    private static String libelleElement(Object element) {
        if (element == null) return "";
        if (!(element instanceof Map<?, ?> m)) return element.toString();
        String titre = premier(m, "title", "action", "description", "text", "criterion");
        String detail = premier(m, "detail", "explanation", "reason");
        if (titre.isEmpty()) return detail;
        return detail.isEmpty() || detail.equals(titre) ? titre : titre + " — " + detail;
    }

    private static String premier(Map<?, ?> m, String... cles) {
        for (String cle : cles) {
            Object v = m.get(cle);
            if (v != null && !v.toString().isBlank()) return v.toString().trim();
        }
        return "";
    }

    /**
     * La police du PDF couvre le français (Windows-1252) mais pas ≤ ≥ → ni l'arabe : on remplace
     * les symboles courants par leur équivalent ASCII, et le reste hors police par « ? ».
     */
    static String nettoyer(String texte) {
        if (texte == null) return "";
        String t = texte.replace("≤", "<=").replace("≥", ">=").replace("→", "->")
                .replace(' ', ' ').replace(' ', ' ');
        StringBuilder sb = new StringBuilder(t.length());
        for (char c : t.toCharArray()) {
            boolean supporte = c < 0x100 || "€‚ƒ„…†‡ˆ‰Š‹ŒŽ‘’“”•–—˜™š›œžŸ".indexOf(c) >= 0;
            sb.append(supporte || Character.isWhitespace(c) ? c : '?');
        }
        return sb.toString();
    }
}
