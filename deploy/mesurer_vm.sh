#!/usr/bin/env bash
# Mesure les indicateurs de déploiement sur la VM Azure, en LECTURE SEULE :
# rien n'est redémarré, aucune donnée n'est lue ni modifiée, aucun secret n'est affiché.
#
# Depuis Azure Cloud Shell :
#   az vm run-command invoke -g CREDISENSE-RG -n credisense-vm --command-id RunShellScript \
#       --scripts @mesurer_vm.sh
# (ou, connecté à la VM : sudo bash mesurer_vm.sh)
cd /opt/credisense || exit 1
COMPOSE="docker compose -f docker-compose.prod.yml"
DOMAIN=$(grep -E '^DOMAIN=' .env | head -1 | cut -d= -f2)

echo "=== MACHINE ==="
echo "coeurs: $(nproc)"
free -m | awk 'NR==2 {print "memoire_totale_Mo: "$2"\nmemoire_utilisee_Mo: "$3"\nmemoire_disponible_Mo: "$7}'
df -h / | awk 'NR==2 {print "disque: "$2" au total, "$3" utilises ("$5")"}'
echo "taille_vm_azure: $(curl -s -H Metadata:true 'http://169.254.169.254/metadata/instance/compute/vmSize?api-version=2021-02-01&format=text')"

echo; echo "=== IMAGES (taille) ==="
docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}' | grep -E 'credisense|postgres|caddy'

echo; echo "=== CONTENEURS (etat, sante, demarrage) ==="
$COMPOSE ps --format '{{.Service}} | {{.Status}}'

echo; echo "=== RESSOURCES AU REPOS (instantane) ==="
docker stats --no-stream --format '{{.Name}} | CPU {{.CPUPerc}} | RAM {{.MemUsage}} ({{.MemPerc}})'

echo; echo "=== DEMARRAGE DES SERVICES (lu dans les journaux) ==="
$COMPOSE logs backend 2>/dev/null | grep -m1 -E 'Started .* in [0-9.]+ seconds' | sed -E 's/.*(Started .* in [0-9.]+ seconds).*/backend: \1/'
$COMPOSE logs ai 2>/dev/null | grep -m1 -E 'ChatbotService RAG prêt|Application startup complete' | cut -c1-120

echo; echo "=== LATENCE HTTP (10 requetes chacune, secondes) ==="
mesurer() {
  local nom="$1" url="$2" ; local t=()
  for i in $(seq 1 10); do t+=("$(curl -s -o /dev/null -w '%{time_total}' "$url")"); done
  printf '%s\n' "${t[@]}" | sort -n | awk -v n="$nom" '{a[NR]=$1; s+=$1} END {printf "%s: min %.3f | mediane %.3f | moyenne %.3f | max %.3f\n", n, a[1], a[int((NR+1)/2)], s/NR, a[NR]}'
}
mesurer "page d'accueil (Caddy + Angular SSR)" "https://$DOMAIN/"
mesurer "API (Caddy + Spring, route protegee)" "https://$DOMAIN/api/dossiers"
$COMPOSE exec -T ai python - <<'PY'
import time, urllib.request
t = []
for _ in range(10):
    d = time.time(); urllib.request.urlopen("http://localhost:8002/health").read(); t.append((time.time() - d) * 1000)
t.sort()
print("service IA /health (interne): min %.1f ms | mediane %.1f ms | max %.1f ms" % (t[0], t[len(t)//2], t[-1]))
PY

echo; echo "=== SAUVEGARDE ET PERSISTANCE ==="
docker volume ls --format '{{.Name}}' | grep -E 'pgdata|uploads|caddy'
echo "redemarrage_automatique: $(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' $($COMPOSE ps -q backend) 2>/dev/null)"
echo; echo "=== FIN ==="
