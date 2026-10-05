variable "location" {
  description = "Azure region. Azure for Students restricts regions via policy (RequestDisallowedByAzure otherwise) and the allowed list differs per subscription: check it with `az policy assignment list --query \"[].parameters.listOfAllowedLocations.value\" -o json`."
  type        = string
  default     = "francecentral"
}

variable "vm_size" {
  description = "VM size. The AI service (PaddleOCR + PyTorch + sentence-transformers) needs ~8 GB RAM in total with Spring Boot and Postgres, so 4 GB sizes (B2s) are too small. Standard_B2ms and Standard_D2s_v7 are both 2 vCPU / 8 GB; B2ms is usually the one available on student subscriptions. Check with `az vm list-skus --location <region> --size Standard_B2ms -o table`."
  type        = string
  default     = "Standard_B2ms"
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

variable "groq_api_key" {
  type      = string
  sensitive = true
}

variable "mistral_api_key" {
  type      = string
  sensitive = true
  default   = ""
}

variable "gemini_api_key" {
  type      = string
  sensitive = true
  default   = ""
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
