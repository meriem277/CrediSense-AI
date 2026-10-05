variable "location" {
  description = "Azure region. Azure for Students restricts regions via policy (RequestDisallowedByAzure otherwise) and the allowed list differs per subscription: check it with `az policy assignment list --query \"[].parameters.listOfAllowedLocations.value\" -o json`."
  type        = string
  default     = "swedencentral"
}

variable "vm_size" {
  description = "VM size. The AI service (PaddleOCR + PyTorch + sentence-transformers) needs ~8 GB RAM in total with Spring Boot and Postgres, so 4 GB sizes (B2s) are too small. Pick a 2 vCPU / 8 GB size from a family where your subscription has quota (`az vm list-usage --location <region> --query \"[?limit!='0']\" -o table`) and that exists in the region (`az vm list-skus` or Get-AzComputeResourceSku): not every region has every family. Standard_B2as_v2 deployed on an Azure for Students subscription in swedencentral, where Standard_B2ms/B4ms hit SkuNotAvailable in norwayeast and D-series v5 quota was 0. If creation fails with SkuNotAvailable it is a temporary capacity shortage: try another size or region."
  type        = string
  default     = "Standard_B2as_v2"
}

variable "os_disk_size_gb" {
  description = "OS disk size. Docker images (PyTorch, PaddleOCR models) are several GB."
  type        = number
  default     = 64
}

variable "admin_username" {
  type    = string
  default = "credisense"
}

variable "allowed_ssh_source_ip" {
  description = "CIDR allowed to SSH (port 22). \"*\" is required for GitHub Actions deploys (runner IPs are unpredictable); password login is disabled, key only."
  type        = string
  default     = "*"
}

variable "image_prefix" {
  description = "GHCR image prefix, lowercase: ghcr.io/<github-owner>/credisense. deploy.yml re-derives this from the repo owner on every deploy."
  type        = string
  default     = "ghcr.io/meriem277/credisense"
}

variable "domain" {
  description = "Public hostname. Leave empty to use <ip-with-dashes>.nip.io (free, real Let's Encrypt HTTPS, no domain purchase needed)."
  type        = string
  default     = ""
}

# ── Application secrets (put them in terraform.tfvars, which is git-ignored) ──

variable "ai_env" {
  description = "Full content of Python/.env (pasted as a heredoc in terraform.tfvars). Written to /opt/credisense/ai.env and loaded by the AI container. GROQ_API_KEY from it is also reused by the backend."
  type        = string
  sensitive   = true
}

variable "google_client_id" {
  description = "Google OAuth client id (client portal login). Add https://<domain> to its authorized JavaScript origins."
  type        = string
  default     = ""
}

variable "smtp_username" {
  type    = string
  default = ""
}

variable "smtp_password" {
  description = "SMTP password (Gmail: an app password)."
  type        = string
  sensitive   = true
  default     = ""
}
