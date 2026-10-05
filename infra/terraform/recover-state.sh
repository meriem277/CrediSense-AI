#!/usr/bin/env bash
# Re-attach resources that exist in Azure but are missing from the Terraform state.
#
# Azure sometimes returns a resource a few seconds after creating it. The azurerm
# provider then fails with "Provider produced inconsistent result after apply"
# even though the resource was created, and the next apply fails with
# "already exists ... needs to be imported". This imports whatever is missing.
#
# Usage (Azure Cloud Shell or any shell with az + terraform logged in):
#   ./recover-state.sh && terraform apply -parallelism=1
# Pass the same -var values you use for apply through TF_VAR_location / TF_VAR_vm_size,
# or set them in terraform.tfvars.
set -euo pipefail
cd "$(dirname "$0")"

RG="${RG:-credisense-rg}"
SUB="$(az account show --query id -o tsv)"
NET="/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.Network"

# address -> Azure resource id (import order matters: parents first)
ADDRS=(
  azurerm_resource_group.main
  azurerm_virtual_network.main
  azurerm_subnet.main
  azurerm_public_ip.main
  azurerm_network_security_group.main
  azurerm_network_interface.main
)
IDS=(
  "/subscriptions/$SUB/resourceGroups/$RG"
  "$NET/virtualNetworks/credisense-vnet"
  "$NET/virtualNetworks/credisense-vnet/subnets/credisense-subnet"
  "$NET/publicIPAddresses/credisense-ip"
  "$NET/networkSecurityGroups/credisense-nsg"
  "$NET/networkInterfaces/credisense-nic"
)

exists_in_azure() {
  case "$1" in
    azurerm_resource_group.main) az group show --name "$RG" >/dev/null 2>&1 ;;
    azurerm_subnet.main)         az network vnet subnet show --ids "$2" >/dev/null 2>&1 ;;
    *)                           az resource show --ids "$2" >/dev/null 2>&1 ;;
  esac
}

for i in "${!ADDRS[@]}"; do
  addr="${ADDRS[$i]}"
  id="${IDS[$i]}"
  if terraform state show "$addr" >/dev/null 2>&1; then
    echo "ok       $addr (already in state)"
  elif exists_in_azure "$addr" "$id"; then
    echo "import   $addr"
    terraform import "$addr" "$id"
  else
    echo "absent   $addr (apply will create it)"
  fi
done
