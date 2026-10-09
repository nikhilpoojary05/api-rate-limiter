# Deploying a public demo

How to run the project on one free cloud VM, with HTTPS, as a demo you can link from a
resume.

**What has and has not been tested.** The pieces in this repo were run locally: the
production Compose override (gateway on loopback only, trusting the proxy's client address)
and the [`Caddyfile`](../deploy/Caddyfile) with Caddy 2.11, serving the production dashboard
build, proxying the API and passing the live event stream through. The cloud steps below
have **not** been run: they need an account in your name. Expect to adjust details.

## 1. A VM

Oracle Cloud's Always Free tier includes Arm (Ampere A1) capacity of up to 4 cores and
24 GB, enough for the whole stack (about 4 GB in use). Any VM with 4 GB+ RAM works.

- Create an instance: Ubuntu 24.04, shape **VM.Standard.A1.Flex**, 2 OCPUs, 12 GB.
  Free A1 capacity is sometimes exhausted in a region; retry later or pick another.
- In the instance's VCN security list, allow inbound TCP **80** and **443**. Nothing else
  needs to be public: the gateway, databases and monitoring stay on the VM.
- Oracle's Ubuntu images also firewall the host itself. Open the same two ports there:

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

## 2. A domain

HTTPS needs a hostname. A free subdomain from a dynamic DNS service such as DuckDNS works;
point it at the VM's public IP. The steps below call it `ratelimiter.example.com`.

## 3. Software

```bash
sudo apt update
sudo apt install -y docker.io docker-compose-v2 openjdk-21-jdk-headless maven git
sudo usermod -aG docker $USER     # then log out and back in
```

Node.js 18+ for the dashboard build, and Caddy from its official repository:
https://caddyserver.com/docs/install#debian-ubuntu-raspbian

## 4. Build and configure

```bash
git clone https://github.com/nikhilpoojary05/api-rate-limiter.git
cd api-rate-limiter
mvn -B clean package -DskipTests
cp .env.example .env
```

Edit `.env`. Every secret must be new, never the demo values in this repo:

```bash
openssl rand -base64 48    # JWT_SECRET
openssl rand -hex 24       # POSTGRES_PASSWORD, REDIS_PASSWORD, GF_SECURITY_ADMIN_PASSWORD
```

and set:

```
CORS_ALLOWED_ORIGINS=https://ratelimiter.example.com
AUTH_COOKIE_SECURE=true
SECURITY_HSTS_ENABLED=true
```

The Java base image (`eclipse-temurin:21-jre-alpine`) is published for Arm; if a build on
the VM ever reports no matching platform, switch the four Dockerfiles to
`eclipse-temurin:21-jre`.

## 5. Start the stack

```bash
docker compose -f docker-compose.yml -f deploy/docker-compose.prod.yml up -d --build --wait
```

The production override publishes the gateway on `127.0.0.1` only and makes it trust
`X-Forwarded-For`, so each visitor gets their own per-IP login limit instead of all of them
sharing the proxy's. That trust is safe only because nothing but Caddy can reach the
gateway; do not publish the gateway port.

## 6. Dashboard and HTTPS

```bash
cd dashboard && npm ci && npm run build && cd ..
sudo mkdir -p /srv/dashboard && sudo cp -r dashboard/dist/* /srv/dashboard/
sudo cp deploy/Caddyfile /etc/caddy/Caddyfile
```

Give Caddy the domain: `sudo systemctl edit caddy`, add

```
[Service]
Environment=DOMAIN=ratelimiter.example.com
```

then `sudo systemctl restart caddy`. Caddy obtains the certificate itself. Open
`https://ratelimiter.example.com`.

## 7. Before you share the link

Everything a visitor needs to break the demo is in this public repo, so change it:

- **Seeded passwords** (`admin` / `Admin@123!` and the two demo users) are in the README.
  Change them, or share only an account you are happy for anyone to use.
- **Demo API keys** (`api_key_*`) are in the seed migration. Rotate them from the Tenants
  page.
- **Registration codes** (`acme-join-...`, `beta-join-...`) are in a migration too. Change
  or clear them in the `tenants` table of `ratelimiter_auth`.
- **A nightly reset** keeps the demo clean: `docker compose down -v` and `up` from a cron
  job restores the seed data. Do it only after rotating the values above in the migrations,
  or the reset brings the public ones back.

## 8. Monitoring

Grafana, Prometheus and Zipkin listen on the VM's loopback only. Reach them over SSH:

```bash
ssh -L 3001:localhost:3001 -L 9090:localhost:9090 -L 9411:localhost:9411 ubuntu@<vm-ip>
```

then open http://localhost:3001 on your machine.

## Updating

```bash
git pull && mvn -B clean package -DskipTests
docker compose -f docker-compose.yml -f deploy/docker-compose.prod.yml up -d --build --wait
```

and rebuild and copy the dashboard if it changed.
