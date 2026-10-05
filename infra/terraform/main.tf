locals {
  name   = "credisense"
  domain = var.domain != "" ? var.domain : "${replace(azurerm_public_ip.main.ip_address, ".", "-")}.nip.io"

  env_file = <<-EOT
    DOMAIN=${local.domain}
    IMAGE_PREFIX=${var.image_prefix}
    IMAGE_TAG=latest
    POSTGRES_USER=credisense
    POSTGRES_PASSWORD=${random_password.postgres.result}
    JWT_SECRET=${random_password.jwt.result}
    GROQ_API_KEY=${var.groq_api_key}
    MISTRAL_API_KEY=${var.mistral_api_key}
    GEMINI_API_KEY=${var.gemini_api_key}
    GOOGLE_CLIENT_ID=${var.google_client_id}
    SMTP_USERNAME=${var.smtp_username}
    SMTP_PASSWORD=${var.smtp_password}
  EOT
}

resource "random_password" "postgres" {
  length  = 32
  special = false
}

resource "random_password" "jwt" {
  length  = 64
  special = false
}

# Terraform generates the SSH key so there is no ssh-keygen step.
resource "tls_private_key" "ssh" {
  algorithm = "RSA" # azurerm's admin_ssh_key rejects ed25519
  rsa_bits  = 4096
}

resource "azurerm_resource_group" "main" {
  name     = "${local.name}-rg"
  location = var.location
}

resource "azurerm_virtual_network" "main" {
  name                = "${local.name}-vnet"
  address_space       = ["10.0.0.0/16"]
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
}

resource "azurerm_subnet" "main" {
  name                 = "${local.name}-subnet"
  resource_group_name  = azurerm_resource_group.main.name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = ["10.0.1.0/24"]
}

resource "azurerm_public_ip" "main" {
  name                = "${local.name}-ip"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  allocation_method   = "Static"
  sku                 = "Standard"
}

resource "azurerm_network_security_group" "main" {
  name                = "${local.name}-nsg"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name

  security_rule {
    name                       = "SSH"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "22"
    source_address_prefix      = var.allowed_ssh_source_ip
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "HTTP"
    priority                   = 110
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "80"
    source_address_prefix      = "*"
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "HTTPS"
    priority                   = 120
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "443"
    source_address_prefix      = "*"
    destination_address_prefix = "*"
  }
}

resource "azurerm_network_interface" "main" {
  name                = "${local.name}-nic"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name

  ip_configuration {
    name                          = "internal"
    subnet_id                     = azurerm_subnet.main.id
    private_ip_address_allocation = "Dynamic"
    public_ip_address_id          = azurerm_public_ip.main.id
  }
}

resource "azurerm_network_interface_security_group_association" "main" {
  network_interface_id      = azurerm_network_interface.main.id
  network_security_group_id = azurerm_network_security_group.main.id
}

resource "azurerm_linux_virtual_machine" "main" {
  name                = "${local.name}-vm"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  size                = var.vm_size
  admin_username      = var.admin_username

  network_interface_ids           = [azurerm_network_interface.main.id]
  disable_password_authentication = true

  admin_ssh_key {
    username   = var.admin_username
    public_key = tls_private_key.ssh.public_key_openssh
  }

  os_disk {
    caching              = "ReadWrite"
    storage_account_type = "StandardSSD_LRS"
    disk_size_gb         = var.os_disk_size_gb
  }

  source_image_reference {
    publisher = "Canonical"
    offer     = "0001-com-ubuntu-server-jammy"
    sku       = "22_04-lts-gen2"
    version   = "latest"
  }

  # cloud-init installs Docker and starts the stack on first boot.
  custom_data = base64encode(templatefile("${path.module}/cloud-init.yaml.tftpl", {
    admin_username = var.admin_username
    env_b64        = base64encode(local.env_file)
    compose_b64    = filebase64("${path.module}/../../deploy/docker-compose.prod.yml")
    caddyfile_b64  = filebase64("${path.module}/../../deploy/Caddyfile")
  }))

  # custom_data only runs on first boot; changing it later must not recreate the VM.
  lifecycle {
    ignore_changes = [custom_data]
  }
}
