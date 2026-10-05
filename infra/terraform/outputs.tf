output "public_ip" {
  value = azurerm_public_ip.main.ip_address
}

output "app_url" {
  value = "https://${local.domain}"
}

output "ssh_private_key" {
  description = "Private key for the VM. Store it as the SSH_PRIVATE_KEY GitHub secret."
  value       = tls_private_key.ssh.private_key_openssh
  sensitive   = true
}
