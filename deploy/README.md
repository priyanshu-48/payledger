# Deploying PayLedger on one VM (Oracle Cloud Always Free)

What runs: the whole stack from `docker-compose.yml` plus Caddy, on one Ubuntu 24.04 VM. Only ports 80 and 443 are open to the world; see [ADR 0023](../docs/decisions/0023-deployment.md).

## 1. In the Oracle console (you do this once)

1. Sign up for **Oracle Cloud Free Tier** and pick a home region (it cannot be changed later). A card is needed to verify identity; Always Free resources are not charged. Do not upgrade the account to "Pay As You Go" unless you mean to.
2. **Compute > Instances > Create instance**
   - Image: **Canonical Ubuntu 24.04** (the Arm / aarch64 build).
   - Shape: **Ampere > VM.Standard.A1.Flex**, **4 OCPU and 24 GB** (the Always Free maximum; 2 OCPU / 12 GB also works).
   - Networking: create a new virtual cloud network with the wizard, and assign a public IPv4 address.
   - SSH keys: let Oracle generate a key pair and **download the private key**; keep it somewhere private (it is the only way in).
   - Boot volume: 100 GB is within the free allowance.
   - If creation fails with "out of capacity", try another availability domain, or retry later; Arm capacity is shared.
3. **Reserve the public IP** so it survives a stop and start: Networking > Reserved public IPs > create one, then on the instance open Attached VNICs > the VNIC > IPv4 addresses > edit, and choose the reserved address.
4. **Open the web ports**: Networking > your virtual cloud network > Security Lists > the default list > Add ingress rules: source `0.0.0.0/0`, TCP, destination ports **80** and **443**. While you are there, change the existing SSH rule (port 22) so its source is **your own IP** (for example `203.0.113.5/32`) instead of everywhere.
5. Tell me the **public IP**, the login user (`ubuntu`) and **where the private key file is on your machine**. The key is only used by `ssh`; it is never printed or copied.

## 2. On the VM (I run these over SSH)

```bash
# once: copy the script over, then Docker, swap, the code and a random .env that stays on the VM
scp -i <private key> deploy/provision.sh ubuntu@203.0.113.7:
ssh -i <private key> ubuntu@203.0.113.7 'bash provision.sh 203.0.113.7'
# then, in a new SSH session (so the docker group applies):
bash ~/payledger/deploy/deploy.sh          # builds on the VM (15-20 minutes the first time), starts, waits for HTTPS
bash ~/payledger/deploy/seed-demo.sh       # the public demo merchant and a few payments
```

The site is then at `https://203-0-113-7.sslip.io/` (the dashed IP under sslip.io; Caddy gets the certificate itself). From any machine:

```bash
ops/deploy/smoke-remote.sh https://203-0-113-7.sslip.io
```

## 3. Day to day

- **Update:** `bash ~/payledger/deploy/deploy.sh` (pulls `main`, rebuilds what changed, restarts).
- **Logs:** `cd ~/payledger && docker compose logs -f ledger-service` (any service).
- **Admin access:** the admin password and every other secret are in `~/payledger/.env` on the VM only. Read it over SSH when you need it; it is not in the repository and not in Jenkins.
- **Stop / start:** `docker compose down` keeps the data volumes; the VM can also be stopped from the console. The reserved IP keeps the address.
- **Idle instances:** Oracle may reclaim Always Free instances that sit nearly idle for a week; opening the site or Grafana now and then is enough, and the services themselves (Prometheus scraping, the status-check job) keep some activity going.
- **Data:** this is a demo. There are no backups; `docker compose down -v` deletes all data.

## 4. Moving to another provider

Nothing here is Oracle-specific except the console steps: any Ubuntu 22.04 or 24.04 VM (x86 or Arm) with 8 GB or more RAM, ports 80/443 open and a public IP works with the same three scripts.
