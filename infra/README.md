# Deploying CrediSense AI on Azure (Student pack)

One VM (Ubuntu 22.04, 8 GB) runs everything with Docker Compose behind Caddy (automatic HTTPS via
`<ip>.nip.io`, no domain needed):

```
Internet ─► Caddy :443 ─┬─ /api/*  ─► backend (Spring Boot :8081) ─► postgres
                        └─ /*      ─► frontend (Angular, Node :4000)
                                      backend ─► ai (FastAPI :8002, OCR/NLP/RAG)  [shared uploads volume]
```

Images are built by GitHub Actions (`.github/workflows/deploy.yml`) and stored in GHCR (free).
Terraform creates the VM; cloud-init installs Docker and writes the config. No Ansible.

## 1. Provision (Azure Cloud Shell, or any shell with `az login`)

```bash
git clone <repo> && cd CrediSense-AI && git checkout deploy/azure
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars   # set image_prefix, paste Python/.env into ai_env, SMTP + Google id
terraform init
terraform apply -parallelism=1
```

If you get `SkuNotAvailable`/quota errors, change `vm_size` or `location` in `terraform.tfvars`
(Student subscriptions are region-restricted: `az policy assignment list --query "[?name=='sys.regionrestriction'].parameters.listOfAllowedLocations.value" -o tsv`).

## 2. Give GitHub the VM details

```bash
terraform output -raw ssh_private_key | gh secret set SSH_PRIVATE_KEY
gh variable set AZURE_VM_IP --body "$(terraform output -raw public_ip)"
```

(or add them by hand under Settings > Secrets and variables > Actions).

## 3. Deploy

Push to `main` or `deploy/azure` (or run the **Deploy** workflow manually). It builds the three
images, copies the compose files to the VM and restarts the stack. The first build of the AI image
takes a while (PyTorch + OCR models). Then open `terraform output app_url`.

## Notes

- Create the first admin once (refused afterwards): `curl -X POST https://<domain>/api/auth/init-admin -H "Content-Type: application/json" -d "{...RegisterRequest fields...}"`.
- Secrets live in `/opt/credisense/.env` on the VM and in `terraform.tfstate` (git-ignored, keep it private).
- Google login: add `https://<domain>` to the OAuth client's authorized JavaScript origins.
- Tear down everything: `terraform destroy`.
- Cost control: `az vm deallocate -g credisense-rg -n credisense-vm` when not in use.
