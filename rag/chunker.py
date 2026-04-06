# rag/chunker.py
"""
Chunking sémantique adapté aux dossiers bancaires tunisiens.

Principe : découpage par SECTIONS MÉTIER détectées dans le texte,
plutôt que par nombre fixe de caractères.

Pourquoi c'est important pour le RAG bancaire :
  - Un chunk "revenus" contient exclusivement des infos sur les revenus
  - Le retriever retourne des chunks cohérents à la question posée
  - Exemple : "Quel est le salaire ?" → chunk "revenus" récupéré,
    pas un chunk qui mélange revenus + charges + adresse

Sections détectées automatiquement :
  identite · revenus · charges · patrimoine ·
  historique · professionnel · garanties · general
"""

import re
from dataclasses import dataclass, field
from typing import Optional


@dataclass
class Chunk:
    id: str                    # identifiant unique
    dossier_id: str            # identifiant du dossier parent
    section: str               # catégorie sémantique détectée
    texte: str                 # contenu textuel du chunk
    index: int                 # position dans le document
    nb_chars: int = 0          # longueur du contenu
    metadata: dict = field(default_factory=dict)

    def __post_init__(self):
        self.nb_chars = len(self.texte)


# ── Patterns de détection de sections ────────────────────────────────────────
# Chaque section est définie par des mots-clés en français ET en arabe

SECTION_PATTERNS: dict[str, str] = {

    "identite": (
        r"(informations?\s*(personnelles?|client|du\s*client)"
        r"|identit[eé]"
        r"|cin\s*n[o°]?"
        r"|nom\s*(complet|du\s*client)?\s*:"
        r"|pr[eé]nom\s*:"
        r"|date\s*de\s*naissance"
        r"|adresse\s*(postale|du\s*client)?\s*:"
        r"|situation\s*familiale"
        r"|الهوية|المعلومات الشخصية|الاسم)"
    ),

    "revenus": (
        r"(revenus?\s*(mensuels?|annuels?|nets?|bruts?)?"
        r"|salaire\s*(net|brut|de\s*base)?"
        r"|traitement\s*(mensuel|net)?"
        r"|net\s*(imposable|[àa]\s*payer|mensuel)"
        r"|fiche\s*de\s*paie"
        r"|bulletin\s*de\s*salaire"
        r"|r[eé]mun[eé]ration"
        r"|revenus?\s*locatifs?"
        r"|الراتب|الدخل|الأجر)"
    ),

    "charges": (
        r"(charges?\s*(fixes?|mensuelles?|courantes?)?"
        r"|cr[eé]dits?\s*(en\s*cours|immobilier|auto|consommation)"
        r"|mensualit[eé]s?\s*(cr[eé]dit)?"
        r"|loyer\s*(mensuel)?"
        r"|remboursement"
        r"|d[eé]penses?\s*(mensuelles?|fixes?)"
        r"|taux\s*d.endettement"
        r"|الأعباء|القسط الشهري|الإيجار)"
    ),

    "patrimoine": (
        r"(patrimoine\s*(net|immobilier|financier)?"
        r"|[eé]pargne\s*(disponible|totale)?"
        r"|compte\s*[eé]pargne"
        r"|livret\s*(A|[eé]pargne)?"
        r"|assurance.vie"
        r"|bien\s*(immobilier|foncier)"
        r"|valeur\s*du\s*bien"
        r"|apport\s*(personnel|initial)"
        r"|الأصول|المدخرات|الممتلكات)"
    ),

    "historique": (
        r"(historique\s*(bancaire|des\s*op[eé]rations|du\s*compte)?"
        r"|relev[eé]\s*de\s*compte"
        r"|mouvements?\s*(du\s*compte|mensuels?)?"
        r"|op[eé]rations?\s*(bancaires?|du\s*mois)?"
        r"|solde\s*(moyen|minimum|maximum|fin\s*de\s*mois)"
        r"|d[eé]bit\s*(total|du\s*mois)?"
        r"|cr[eé]dit\s*(total|du\s*mois)?"
        r"|كشف الحساب|العمليات المصرفية)"
    ),

    "professionnel": (
        r"(employeur\s*:"
        r"|nom\s*de\s*l.employeur"
        r"|profession\s*:"
        r"|cat[eé]gorie\s*(professionnelle|socio-professionnelle)"
        r"|contrat\s*(CDI|CDD|de\s*travail)"
        r"|anciennet[eé]\s*(dans\s*l.entreprise|professionnelle)?"
        r"|poste\s*(occup[eé]|actuel)?"
        r"|secteur\s*d.activit[eé]"
        r"|جهة العمل|المهنة|عقد العمل)"
    ),

    "garanties": (
        r"(garantie\s*(propos[eé]e|réelle|personnelle)?"
        r"|caution\s*(bancaire|personnelle)?"
        r"|hypoth[eè]que"
        r"|nantissement"
        r"|gage"
        r"|s[uû]ret[eé]"
        r"|apport\s*en\s*garantie"
        r"|الضمانات|الكفالة|الرهن)"
    ),
}

# Taille maximale d'un chunk en caractères (~400 tokens)
MAX_CHUNK_CHARS = 1600

# Taille minimale pour qu'un chunk soit conservé
MIN_CHUNK_CHARS = 40


def chunk_document(texte_propre: str, dossier_id: str) -> list[Chunk]:
    """
    Point d'entrée principal.
    Découpe le texte nettoyé en chunks sémantiques.

    Args:
        texte_propre : texte après passage dans cleaner.py
        dossier_id   : identifiant unique du dossier (ex: "dossier_ali_ben_salem")

    Returns:
        Liste de Chunk triés par ordre d'apparition dans le document.
    """
    lines = texte_propre.split('\n')
    chunks: list[Chunk] = []

    current_section = "general"
    current_lines: list[str] = []
    chunk_index = 0

    for line in lines:
        stripped = line.strip()
        if not stripped:
            # Ligne vide : séparateur potentiel mais on ne change pas de section
            if current_lines:
                current_lines.append('')
            continue

        # Détection de changement de section
        detected = _detect_section(stripped)
        if detected and detected != current_section:
            # Sauvegarder le chunk courant s'il est assez long
            if _text_from_lines(current_lines):
                chunk = _make_chunk(
                    dossier_id, current_section,
                    current_lines, chunk_index
                )
                if chunk:
                    chunks.append(chunk)
                    chunk_index += 1
            current_section = detected
            current_lines = [stripped]
        else:
            current_lines.append(stripped)

        # Découpe si le chunk courant dépasse MAX_CHUNK_CHARS
        if len(_text_from_lines(current_lines)) > MAX_CHUNK_CHARS:
            chunk = _make_chunk(
                dossier_id, current_section,
                current_lines, chunk_index
            )
            if chunk:
                chunks.append(chunk)
                chunk_index += 1
            current_lines = []

    # Dernier chunk résiduel
    if _text_from_lines(current_lines):
        chunk = _make_chunk(
            dossier_id, current_section,
            current_lines, chunk_index
        )
        if chunk:
            chunks.append(chunk)

    print(f"[Chunker] {len(chunks)} chunks créés pour '{dossier_id}'")
    for c in chunks:
        print(f"  - [{c.section:15s}] {c.nb_chars:4d} chars  id={c.id}")

    return chunks


def _detect_section(line: str) -> Optional[str]:
    """Retourne le nom de la section si la ligne en signale le début."""
    line_lower = line.lower()
    for section, pattern in SECTION_PATTERNS.items():
        if re.search(pattern, line_lower, re.IGNORECASE):
            return section
    return None


def _text_from_lines(lines: list[str]) -> str:
    return '\n'.join(lines).strip()


def _make_chunk(
    dossier_id: str,
    section: str,
    lines: list[str],
    index: int
) -> Optional[Chunk]:
    """Crée un Chunk à partir des lignes accumulées."""
    texte = _text_from_lines(lines)
    if len(texte) < MIN_CHUNK_CHARS:
        return None
    return Chunk(
        id=f"{dossier_id}__{section}__{index:03d}",
        dossier_id=dossier_id,
        section=section,
        texte=texte,
        index=index,
        metadata={"source_section": section, "chunk_index": index}
    )