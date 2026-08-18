# services/nlp_service.py
"""
Service de classification NLP CrediSense — par embeddings sémantiques
(sentence-transformers, zero-shot).

Améliorations par rapport à la v1 :
1. Similarité MAX par phrase de référence (au lieu de la moyenne des
   embeddings d'une catégorie) — plus précis, et on sait QUELLE phrase
   de référence a matché (utile pour l'audit).
2. Seuils de confiance PAR CATÉGORIE (au lieu d'un seuil global unique).
3. Cache disque des embeddings de labels — évite de recalculer à chaque
   redémarrage du service.
4. Vérification hybride : un pattern structurel fort (regex CIN) qui
   contredit la classification sémantique lève une alerte.
5. Logging structuré (dossier_id, confiance, durée) pour l'audit.
"""

import time
import pickle
import logging
import re
from pathlib import Path

from sentence_transformers import SentenceTransformer, util
import torch

logger = logging.getLogger(__name__)

MODEL_NAME = "paraphrase-multilingual-MiniLM-L12-v2"
DEVICE = "cpu"
MAX_CHARS_TEXTE = 2000
CACHE_PATH = Path("cache/label_embeddings.pkl")

SEUIL_CONFIANCE_DEFAUT = 0.35

SEUILS_PAR_LABEL = {
    "CIN": 0.40,
    "FICHE_PAIE": 0.35,
    "RELEVE_BANCAIRE": 0.35,
    "ATTESTATION_EMPLOI": 0.32,
    "CONTRAT_TRAVAIL": 0.32,
    "ASSURANCE_VIE": 0.35,
    "BILAN_COMPTABLE": 0.35,
    "DECLARATION_FISCALE": 0.35,
    "JUSTIFICATIF_DOMICILE": 0.32,
    "TITRE_SEJOUR": 0.35,
    "AUTRE": 0.0,
}

DOCUMENT_LABELS: dict[str, list[str]] = {
    "CIN": [
        "carte d'identité nationale",
        "identité nationale tunisie",
        "numéro CIN pièce d'identité",
        "date de naissance lieu de naissance",
        "carte nationale d'identité",
        "بطاقة التعريف الوطنية",
        "رقم بطاقة الهوية الوطنية",
        "تاريخ الميلاد مكان الميلاد",
        "الهوية الوطنية تونس",
    ],
    "FICHE_PAIE": [
        "bulletin de paie salaire net",
        "fiche de paie mensuelle",
        "salaire brut cotisations sociales",
        "net à payer employé employeur",
        "rémunération mensuelle",
        "CNSS retenue salariale",
        "كشف الراتب الشهري",
        "الراتب الصافي الراتب الخام",
        "الاشتراكات الاجتماعية",
        "صاحب العمل الموظف الأجر",
    ],
    "RELEVE_BANCAIRE": [
        "relevé de compte bancaire",
        "solde créditeur débiteur",
        "opérations bancaires extrait de compte",
        "virement dépôt retrait solde",
        "historique des transactions",
        "RIB numéro de compte",
        "كشف الحساب البنكي",
        "الرصيد الدائن المدين",
        "العمليات البنكية",
        "التحويل الإيداع السحب",
        "رقم الحساب البنكي",
    ],
    "ATTESTATION_EMPLOI": [
        "attestation de travail emploi",
        "certifie que monsieur madame est employé",
        "attestation employeur poste occupé",
        "confirmation d'emploi CDI",
        "attestation de service",
        "شهادة عمل",
        "يشهد بأن السيد يعمل لدى",
        "شهادة في العمل الوظيفة",
        "صاحب العمل يؤكد التوظيف",
    ],
    "CONTRAT_TRAVAIL": [
        "contrat de travail à durée indéterminée CDI",
        "contrat de travail CDD durée déterminée",
        "convention entre employeur et employé",
        "conditions d'emploi poste salaire",
        "article du contrat travail",
        "عقد عمل لأجل غير محدد",
        "عقد عمل محدد المدة",
        "اتفاقية بين صاحب العمل والموظف",
        "شروط العمل الراتب الوظيفة",
    ],
    "ASSURANCE_VIE": [
        "police d'assurance vie décès invalidité",
        "assurance décès toutes causes",
        "bénéficiaire assurance capital garanti",
        "prime d'assurance mensuelle annuelle",
        "contrat assurance vie",
        "وثيقة التأمين على الحياة",
        "تأمين الوفاة والعجز",
        "قسط التأمين الشهري السنوي",
        "عقد التأمين على الحياة",
    ],
    "BILAN_COMPTABLE": [
        "bilan comptable actif passif",
        "résultat net exercice comptable",
        "chiffre d'affaires bénéfice perte",
        "bilan annuel entreprise",
        "compte de résultat charges produits",
        "الميزانية المحاسبية الأصول الخصوم",
        "النتيجة الصافية للسنة المحاسبية",
        "رقم المعاملات الربح الخسارة",
        "الميزانية السنوية للمؤسسة",
    ],
    "DECLARATION_FISCALE": [
        "déclaration fiscale impôt revenus",
        "déclaration annuelle des revenus",
        "impôt sur le revenu déclaration",
        "ministère des finances déclaration",
        "avis d'imposition revenu imposable",
        "التصريح الجبائي ضريبة الدخل",
        "التصريح السنوي بالدخل",
        "الضريبة على الدخل",
        "وزارة المالية التصريح الضريبي",
    ],
    "JUSTIFICATIF_DOMICILE": [
        "justificatif de domicile adresse",
        "facture électricité eau téléphone",
        "quittance de loyer résidence",
        "adresse domicile attestation",
        "certificat de résidence",
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

REGEX_CIN_NUMERO = re.compile(r"\b\d{8}\b")
MOTS_CLES_IDENTITE = ("identité", "identite", "هوية", "التعريف")


class NLPClassifier:

    def __init__(self):
        logger.info("Chargement du modèle %s (device=%s)...", MODEL_NAME, DEVICE)
        t0 = time.time()
        self.model = SentenceTransformer(MODEL_NAME, device=DEVICE)
        self.label_embeddings: dict[str, torch.Tensor] = {}
        self._build_label_embeddings()
        logger.info(
            "Modèle NLP prêt en %.2fs — FR + AR opérationnel (%d catégories).",
            time.time() - t0, len(DOCUMENT_LABELS)
        )

    def _build_label_embeddings(self) -> None:
        if CACHE_PATH.exists():
            try:
                with open(CACHE_PATH, "rb") as f:
                    cached = pickle.load(f)
                if cached.get("labels_hash") == self._hash_labels():
                    self.label_embeddings = cached["embeddings"]
                    logger.info("Embeddings de labels chargés depuis le cache.")
                    return
                logger.info("Cache obsolète (labels modifiés) — recalcul.")
            except Exception as e:
                logger.warning("Cache illisible (%s) — recalcul.", e)

        for label, phrases in DOCUMENT_LABELS.items():
            embeddings = self.model.encode(
                phrases, convert_to_tensor=True, normalize_embeddings=True
            )
            self.label_embeddings[label] = embeddings

        CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
        with open(CACHE_PATH, "wb") as f:
            pickle.dump({
                "embeddings": self.label_embeddings,
                "labels_hash": self._hash_labels(),
            }, f)
        logger.info("Embeddings de labels calculés et mis en cache.")

    @staticmethod
    def _hash_labels() -> int:
        return hash(tuple(
            (label, tuple(phrases)) for label, phrases in DOCUMENT_LABELS.items()
        ))

    def classify(self, texte: str, dossier_id: str | None = None) -> dict:
        t0 = time.time()

        if not texte or len(texte.strip()) < 20:
            return {
                "type_document": "AUTRE",
                "confiance": 0.0,
                "seuil_applique": SEUIL_CONFIANCE_DEFAUT,
                "top3": [],
                "alertes": ["Texte trop court pour classification (< 20 caractères)"],
                "duree_ms": round((time.time() - t0) * 1000, 1),
                "modele": MODEL_NAME,
            }

        texte_tronque = texte[:MAX_CHARS_TEXTE]
        texte_embedding = self.model.encode(
            texte_tronque, convert_to_tensor=True, normalize_embeddings=True
        )

        scores = []
        for label, ref_embeddings in self.label_embeddings.items():
            similarites = util.cos_sim(texte_embedding, ref_embeddings)[0]
            meilleur_idx = int(similarites.argmax())
            scores.append({
                "label": label,
                "score": round(similarites[meilleur_idx].item(), 4),
                "phrase_matchee": DOCUMENT_LABELS[label][meilleur_idx],
            })

        scores.sort(key=lambda s: s["score"], reverse=True)
        best = scores[0]

        seuil_applique = SEUILS_PAR_LABEL.get(best["label"], SEUIL_CONFIANCE_DEFAUT)
        type_final = best["label"] if best["score"] >= seuil_applique else "AUTRE"

        alertes = self._verifier_coherence_cin(texte_tronque, type_final)

        duree_ms = round((time.time() - t0) * 1000, 1)

        logger.info(
            "classification_effectuee",
            extra={
                "dossier_id": dossier_id,
                "type_document_predit": type_final,
                "confiance": best["score"],
                "seuil_applique": seuil_applique,
                "duree_ms": duree_ms,
            }
        )

        return {
            "type_document": type_final,
            "confiance": best["score"],
            "seuil_applique": seuil_applique,
            "top3": scores[:3],
            "alertes": alertes,
            "duree_ms": duree_ms,
            "modele": MODEL_NAME,
        }

    @staticmethod
    def _verifier_coherence_cin(texte: str, type_predit: str) -> list[str]:
        alertes = []
        texte_lower = texte.lower()

        pattern_cin_detecte = (
            REGEX_CIN_NUMERO.search(texte) is not None
            and any(mot in texte_lower or mot in texte for mot in MOTS_CLES_IDENTITE)
        )

        if pattern_cin_detecte and type_predit != "CIN":
            alertes.append(
                f"Pattern structurel de CIN détecté (numéro 8 chiffres + mot-clé "
                f"identité) mais classification = '{type_predit}'. Vérification "
                f"manuelle recommandée."
            )

        return alertes

    def stats(self) -> dict:
        return {
            "modele": MODEL_NAME,
            "device": DEVICE,
            "nb_categories": len(DOCUMENT_LABELS),
            "cache_utilise": CACHE_PATH.exists(),
        }


def verifier_documents_requis(
    documents_fournis: list[str],
    age_client: int | None = None,
    type_contrat: str | None = None,
    nationalite: str = "TN",
) -> dict:
    requis = {
        "CIN",
        "FICHE_PAIE",
        "RELEVE_BANCAIRE",
        "ATTESTATION_EMPLOI",
        "JUSTIFICATIF_DOMICILE",
    }

    regles_appliquees = []

    if age_client and age_client > 60:
        requis.add("ASSURANCE_VIE")
        regles_appliquees.append(f"Âge {age_client} > 60 ans → assurance vie obligatoire")

    if type_contrat and type_contrat.upper() in ("INDEPENDANT", "GERANT"):
        requis.add("BILAN_COMPTABLE")
        requis.add("DECLARATION_FISCALE")
        regles_appliquees.append(
            f"Statut {type_contrat} → bilan comptable + déclaration fiscale"
        )

    if type_contrat and type_contrat.upper() == "CDD":
        requis.add("CONTRAT_TRAVAIL")
        regles_appliquees.append("Contrat CDD → contrat de travail requis")

    if nationalite and nationalite.upper() != "TN":
        requis.add("TITRE_SEJOUR")
        regles_appliquees.append("Demandeur étranger → titre de séjour requis")

    fournis_set = set(documents_fournis)
    manquants = sorted(requis - fournis_set)
    complet = len(manquants) == 0

    return {
        "complet": complet,
        "manquants": manquants,
        "requis": sorted(requis),
        "fournis": sorted(fournis_set),
        "regles_appliquees": regles_appliquees,
        "taux_completion": round(len(fournis_set & requis) / len(requis) * 100, 1),
    }