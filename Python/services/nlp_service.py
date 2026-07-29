# services/nlp_service.py
"""
Classification NLP des documents bancaires
avec sentence-transformers (paraphrase-multilingual-MiniLM-L12-v2)
Supporte le français ET l'arabe
"""

from sentence_transformers import SentenceTransformer, util
import logging

logger = logging.getLogger(__name__)

MODEL_NAME = "paraphrase-multilingual-MiniLM-L12-v2"

# ── Types de documents avec phrases FR + AR ───────────────────────────────────
DOCUMENT_LABELS = {
    "CIN": [
        # Français
        "carte d'identité nationale",
        "identité nationale tunisie",
        "numéro CIN pièce d'identité",
        "date de naissance lieu de naissance",
        "carte nationale d'identité",
        # Arabe
        "بطاقة التعريف الوطنية",
        "رقم بطاقة الهوية الوطنية",
        "تاريخ الميلاد مكان الميلاد",
        "الهوية الوطنية تونس",
    ],
    "FICHE_PAIE": [
        # Français
        "bulletin de paie salaire net",
        "fiche de paie mensuelle",
        "salaire brut cotisations sociales",
        "net à payer employé employeur",
        "rémunération mensuelle",
        "CNSS retenue salariale",
        # Arabe
        "كشف الراتب الشهري",
        "الراتب الصافي الراتب الخام",
        "الاشتراكات الاجتماعية",
        "صاحب العمل الموظف الأجر",
    ],
    "RELEVE_BANCAIRE": [
        # Français
        "relevé de compte bancaire",
        "solde créditeur débiteur",
        "opérations bancaires extrait de compte",
        "virement dépôt retrait solde",
        "historique des transactions",
        "RIB numéro de compte",
        # Arabe
        "كشف الحساب البنكي",
        "الرصيد الدائن المدين",
        "العمليات البنكية",
        "التحويل الإيداع السحب",
        "رقم الحساب البنكي",
    ],
    "ATTESTATION_EMPLOI": [
        # Français
        "attestation de travail emploi",
        "certifie que monsieur madame est employé",
        "attestation employeur poste occupé",
        "confirmation d'emploi CDI",
        "attestation de service",
        # Arabe
        "شهادة عمل",
        "يشهد بأن السيد يعمل لدى",
        "شهادة في العمل الوظيفة",
        "صاحب العمل يؤكد التوظيف",
    ],
    "CONTRAT_TRAVAIL": [
        # Français
        "contrat de travail à durée indéterminée CDI",
        "contrat de travail CDD durée déterminée",
        "convention entre employeur et employé",
        "conditions d'emploi poste salaire",
        "article du contrat travail",
        # Arabe
        "عقد عمل لأجل غير محدد",
        "عقد عمل محدد المدة",
        "اتفاقية بين صاحب العمل والموظف",
        "شروط العمل الراتب الوظيفة",
    ],
    "ASSURANCE_VIE": [
        # Français
        "police d'assurance vie décès invalidité",
        "assurance décès toutes causes",
        "bénéficiaire assurance capital garanti",
        "prime d'assurance mensuelle annuelle",
        "contrat assurance vie",
        # Arabe
        "وثيقة التأمين على الحياة",
        "تأمين الوفاة والعجز",
        "قسط التأمين الشهري السنوي",
        "عقد التأمين على الحياة",
    ],
    "BILAN_COMPTABLE": [
        # Français
        "bilan comptable actif passif",
        "résultat net exercice comptable",
        "chiffre d'affaires bénéfice perte",
        "bilan annuel entreprise",
        "compte de résultat charges produits",
        # Arabe
        "الميزانية المحاسبية الأصول الخصوم",
        "النتيجة الصافية للسنة المحاسبية",
        "رقم المعاملات الربح الخسارة",
        "الميزانية السنوية للمؤسسة",
    ],
    "DECLARATION_FISCALE": [
        # Français
        "déclaration fiscale impôt revenus",
        "déclaration annuelle des revenus",
        "impôt sur le revenu déclaration",
        "ministère des finances déclaration",
        "avis d'imposition revenu imposable",
        # Arabe
        "التصريح الجبائي ضريبة الدخل",
        "التصريح السنوي بالدخل",
        "الضريبة على الدخل",
        "وزارة المالية التصريح الضريبي",
    ],
    "JUSTIFICATIF_DOMICILE": [
        # Français
        "justificatif de domicile adresse",
        "facture électricité eau téléphone",
        "quittance de loyer résidence",
        "adresse domicile attestation",
        "certificat de résidence",
        # Arabe
        "وثيقة إقامة العنوان",
        "فاتورة الكهرباء الماء الهاتف",
        "إيصال الإيجار",
        "شهادة الإقامة العنوان",
    ],
    "AUTRE": [
        "document autre non reconnu",
        "fichier non classifiable",
        "وثيقة غير معروفة",
    ],
}


class NLPClassifier:

    def __init__(self):
        logger.info(f"Chargement du modèle {MODEL_NAME}...")
        self.model = SentenceTransformer(MODEL_NAME)
        self._build_label_embeddings()
        logger.info("Modèle NLP chargé — FR + AR opérationnel.")

    def _build_label_embeddings(self):
        """Précalcule les embeddings moyens des phrases de référence."""
        self.label_embeddings = {}
        for label, phrases in DOCUMENT_LABELS.items():
            embeddings = self.model.encode(phrases, convert_to_tensor=True)
            self.label_embeddings[label] = embeddings.mean(dim=0)

    def classify(self, texte: str, seuil_confiance: float = 0.35) -> dict:
        """
        Classifie un texte et retourne le type de document + score de confiance.
        """
        if not texte or len(texte.strip()) < 20:
            return {
                "type_document": "AUTRE",
                "confiance": 0.0,
                "top3": [],
                "message": "Texte trop court pour classification"
            }

        # Limiter à 2000 chars pour la performance
        texte_tronque = texte[:2000]
        texte_embedding = self.model.encode(texte_tronque, convert_to_tensor=True)

        # Similarité cosinus avec chaque label
        scores = {}
        for label, ref_embedding in self.label_embeddings.items():
            score = util.cos_sim(texte_embedding, ref_embedding).item()
            scores[label] = round(score, 4)

        sorted_scores = sorted(scores.items(), key=lambda x: x[1], reverse=True)
        best_label, best_score = sorted_scores[0]

        if best_score < seuil_confiance:
            best_label = "AUTRE"

        top3 = [{"label": l, "score": s} for l, s in sorted_scores[:3]]

        logger.info(f"Classification → {best_label} (confiance={best_score})")

        return {
            "type_document": best_label,
            "confiance":     best_score,
            "top3":          top3
        }


# ── Règles métier — checklist documents ───────────────────────────────────────

def verifier_documents_requis(
    documents_fournis: list,
    age_client: int = None,
    type_contrat: str = None,
    nationalite: str = "TN"
) -> dict:
    """
    Vérifie la complétude du dossier selon les règles métier Attijariwafa.
    """
    # Documents toujours obligatoires
    requis = {
        "CIN",
        "FICHE_PAIE",
        "RELEVE_BANCAIRE",
        "ATTESTATION_EMPLOI",
        "JUSTIFICATIF_DOMICILE",
    }

    regles_appliquees = []

    # Règle 1 : âge > 60 → assurance vie
    if age_client and age_client > 60:
        requis.add("ASSURANCE_VIE")
        regles_appliquees.append(
            f"Âge {age_client} > 60 ans → assurance vie obligatoire"
        )

    # Règle 2 : indépendant/gérant → bilan + déclaration fiscale
    if type_contrat and type_contrat.upper() in ("INDEPENDANT", "GERANT"):
        requis.add("BILAN_COMPTABLE")
        requis.add("DECLARATION_FISCALE")
        regles_appliquees.append(
            f"Statut {type_contrat} → bilan comptable + déclaration fiscale"
        )

    # Règle 3 : CDD → contrat de travail
    if type_contrat and type_contrat.upper() == "CDD":
        requis.add("CONTRAT_TRAVAIL")
        regles_appliquees.append("Contrat CDD → contrat de travail requis")

    # Règle 4 : étranger → titre de séjour
    if nationalite and nationalite.upper() != "TN":
        requis.add("TITRE_SEJOUR")
        regles_appliquees.append("Demandeur étranger → titre de séjour requis")

    fournis_set = set(documents_fournis)
    manquants   = sorted(requis - fournis_set)
    complet     = len(manquants) == 0

    return {
        "complet":           complet,
        "manquants":         manquants,
        "requis":            sorted(requis),
        "fournis":           sorted(fournis_set),
        "regles_appliquees": regles_appliquees,
        "taux_completion":   round(len(fournis_set & requis) / len(requis) * 100, 1)
    }