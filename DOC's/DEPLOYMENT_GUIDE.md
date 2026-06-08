# 🚀 Complete Hostinger VPS Deployment Guide — AxonX MedTech

> **For**: Hospital Management System (HMS) + Company Website  
> **Server OS**: Ubuntu (Hostinger VPS)  
> **Domain**: `axonxmedtech.com`

---

## 📋 What We Are Deploying (Architecture Overview)

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          HOSTINGER VPS (Ubuntu)                                │
│                                                                                │
│  ┌─────────────────────────────────────────────────────────────────────────┐    │
│  │                        NGINX (Reverse Proxy)                           │    │
│  │   Port 80 (HTTP) → redirects to 443                                   │    │
│  │   Port 443 (HTTPS) → routes to correct app based on domain            │    │
│  └───────┬──────────────┬──────────────┬──────────────┬────────────┬──────┘    │
│          │              │              │              │            │            │
│    ┌─────┴────┐  ┌──────┴─────┐  ┌────┴─────┐  ┌────┴────┐  ┌───┴──────┐    │
│    │ Website  │  │ Website    │  │ App      │  │ App     │  │ Backend  │    │
│    │ PROD     │  │ STAGING    │  │ Frontend │  │ Frontend│  │ Services │    │
│    │ :3001    │  │ :3002      │  │ (static) │  │(static) │  │          │    │
│    └──────────┘  └────────────┘  └──────────┘  └─────────┘  └──┬───┬───┘    │
│                                                                 │   │        │
│                                                          ┌──────┴┐ ┌┴──────┐  │
│                                                          │Backend│ │Backend│  │
│                                                          │PROD   │ │STAGE  │  │
│                                                          │:8080  │ │:8081  │  │
│                                                          └───┬───┘ └───┬───┘  │
│                                                              │         │      │
│    ┌─────────────────────────────────────────────────────────┴────┬────┘      │
│    │                        MySQL Server                          │           │
│    │   ┌──────────────────────┐  ┌──────────────────────────┐    │           │
│    │   │ hms_production       │  │ hms_staging              │    │           │
│    │   └──────────────────────┘  └──────────────────────────┘    │           │
│    └──────────────────────────────────────────────────────────────┘           │
│                                                                               │
│    ┌──────────────────────────────────────────────────────────────┐           │
│    │                     Redis Server (Port 6379)                 │           │
│    └──────────────────────────────────────────────────────────────┘           │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Domain Mapping

| Purpose | Domain | Points To |
|---|---|---|
| **PRODUCTION Website** | `axonxmedtech.com` | Website repo → `main` branch |
| **PRODUCTION Frontend (HMS App)** | `app.axonxmedtech.com` | HMS frontend → `main` branch |
| **PRODUCTION Backend (HMS API)** | `api.axonxmedtech.com` | HMS backend → `main` branch → Port 8080 |
| **PRODUCTION Database** | — | MySQL DB: `hms_production` |
| **STAGING Website** | `staging-axonxmedtech.com` | Website repo → staging/dev branch |
| **STAGING Frontend (HMS App)** | `staging-app.axonxmedtech.com` | HMS frontend → `staging` branch |
| **STAGING Backend (HMS API)** | `staging-api.axonxmedtech.com` | HMS backend → `staging` branch → Port 8081 |
| **STAGING Database** | — | MySQL DB: `hms_staging` |

### Your Tech Stack Summary

| Component | Technology | Version |
|---|---|---|
| Backend | Java (Spring Boot) | Java 17 / Spring Boot 3.2.0 |
| Frontend | React (Vite + TypeScript) | React 19 / Vite 7 |
| Database | MySQL | 8.0+ |
| Cache | Redis | Latest |
| Auth | JWT (stateless) | — |
| Real-time | WebSocket | Spring WebSocket |
| Reverse Proxy | Nginx | — |
| SSL | Let's Encrypt (Certbot) | — |

---

## 📌 Prerequisites Before Starting

Before you begin, make sure you have:

- [x] Hostinger VPS purchased with Ubuntu OS selected
- [x] Domain `axonxmedtech.com` purchased
- [ ] VPS IP address (find it in Hostinger dashboard → VPS → Overview)
- [ ] Root password for VPS (set during VPS setup in Hostinger)
- [ ] SSH client (macOS Terminal has it built-in — no extra install needed)
- [ ] Your GitHub repo URL: `https://github.com/Ayush9535/HOSPITAL.git`
- [ ] Your website repo URL (you'll need this later)

---

## PHASE 1: Initial VPS Access & Security Setup

### Step 1.1 — Find Your VPS IP Address

1. Log in to [Hostinger](https://hpanel.hostinger.com/)
2. Go to **VPS** section
3. Click on your VPS
4. Copy the **IP Address** shown on the dashboard (e.g., `185.xxx.xxx.xxx`)

### Step 1.2 — Connect to Your VPS via SSH

Open **Terminal** on your Mac (press `Cmd + Space`, type "Terminal", hit Enter) and run:

```bash
ssh root@YOUR_VPS_IP
```

> Replace `YOUR_VPS_IP` with your actual VPS IP address.  
> It will ask: "Are you sure you want to continue connecting?" → Type **yes** and press Enter.  
> Then enter your **root password** (the one you set during VPS setup).

> [!TIP]
> If typing the password shows nothing on screen, that's normal — it's hidden for security. Just type it and press Enter.

### Step 1.3 — Update the System

Once logged in, update all packages:

```bash
apt update && apt upgrade -y
```

> **What this does**: Downloads the latest package lists and upgrades all installed software to their latest versions. `-y` means automatically say "yes" to prompts.

### Step 1.4 — Create a Non-Root User (For Security)

Running everything as `root` is dangerous. Let's create a regular user:

```bash
# Create a new user (replace 'deploy' with any username you want)
adduser deploy
```

> It will ask you to set a password and fill in some info. Set a strong password. You can skip the other fields by pressing Enter.

Now give this user admin (sudo) privileges:

```bash
usermod -aG sudo deploy
```

> **What this does**: Adds the `deploy` user to the `sudo` group, allowing them to run commands as root when needed by prefixing with `sudo`.

### Step 1.5 — Set Up SSH Key Authentication (More Secure than Passwords)

On **your Mac** (not the server), open a NEW Terminal window:

```bash
# Generate an SSH key pair (press Enter for all prompts to accept defaults)
ssh-keygen -t ed25519 -C "your-email@example.com"
```

Now copy the public key to your server:

```bash
# Display the key content
cat ~/.ssh/id_ed25519.pub
```

Copy the entire output. Then go back to **your VPS SSH session** and run:

```bash
# Switch to the deploy user
su - deploy

# Create SSH directory
mkdir -p ~/.ssh
chmod 700 ~/.ssh

# Paste your public key (replace PASTE_YOUR_KEY_HERE with the actual key)
echo "PASTE_YOUR_KEY_HERE" >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys

# Go back to root
exit
```

### Step 1.6 — Configure SSH Security

```bash
# Edit the SSH configuration
nano /etc/ssh/sshd_config
```

Find and change these lines (use `Ctrl+W` to search in nano):

```
# Change these values:
PermitRootLogin no
PasswordAuthentication no
PubkeyAuthentication yes
```

> [!WARNING]
> **BEFORE** disabling password authentication, test that SSH key login works from another Terminal window on your Mac:
> ```bash
> ssh deploy@YOUR_VPS_IP
> ```
> If this works WITHOUT asking for a password, you're safe to proceed.

Save the file (`Ctrl+X`, then `Y`, then `Enter`) and restart SSH:

```bash
systemctl restart sshd
```

> [!CAUTION]
> If you disable password login BEFORE confirming key login works, you'll be locked out of your server! Always test first.

### Step 1.7 — Set Up the Firewall (UFW)

```bash
# Allow SSH (CRITICAL — do this FIRST or you'll be locked out!)
ufw allow OpenSSH

# Allow HTTP and HTTPS traffic
ufw allow 80/tcp
ufw allow 443/tcp

# Enable the firewall
ufw enable
```

> It will ask "Command may disrupt existing SSH connections. Proceed with operation?" → Type **y**

Verify the firewall status:

```bash
ufw status
```

Expected output:
```
Status: active

To                         Action      From
--                         ------      ----
OpenSSH                    ALLOW       Anywhere
80/tcp                     ALLOW       Anywhere
443/tcp                    ALLOW       Anywhere
```

### Step 1.8 — Set Server Timezone

```bash
timedatectl set-timezone Asia/Kolkata
```

> **Why**: Ensures your logs, timestamps, and cron jobs use Indian Standard Time.

---

## PHASE 2: Install Required Software

> [!NOTE]
> From now on, log in as the `deploy` user: `ssh deploy@YOUR_VPS_IP`  
> Use `sudo` before commands that require root access.

### Step 2.1 — Install Java 17 (For Backend)

```bash
sudo apt install -y openjdk-17-jdk
```

Verify installation:

```bash
java -version
```

Expected output: `openjdk version "17.x.x"`

### Step 2.2 — Install Maven (For Building Backend)

```bash
sudo apt install -y maven
```

Verify:

```bash
mvn -version
```

### Step 2.3 — Install Node.js 20 LTS (For Frontend Build)

```bash
# Install Node.js via NodeSource repository
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs
```

Verify:

```bash
node -v    # Should show v20.x.x
npm -v     # Should show 10.x.x
```

### Step 2.4 — Install MySQL 8.0 (Database)

```bash
sudo apt install -y mysql-server
```

Secure the MySQL installation:

```bash
sudo mysql_secure_installation
```

> It will ask you several questions:
> 1. **VALIDATE PASSWORD component?** → Press `y`, then choose `1` (MEDIUM) for password strength
> 2. **Set root password** → Set a STRONG password (write it down somewhere safe!)
> 3. **Remove anonymous users?** → `y`
> 4. **Disallow root login remotely?** → `y`
> 5. **Remove test database?** → `y`
> 6. **Reload privilege tables?** → `y`

### Step 2.5 — Create Databases and Users

Log into MySQL:

```bash
sudo mysql -u root -p
```

> Enter the MySQL root password you just set.

Now create the production and staging databases:

```sql
-- ===================================================
-- CREATE DATABASES
-- ===================================================

-- Production database
CREATE DATABASE hms_production CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Staging/Development database
CREATE DATABASE hms_staging CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ===================================================
-- CREATE DEDICATED DATABASE USERS (one per environment)
-- ===================================================

-- Production user (replace 'STRONG_PROD_PASSWORD_HERE' with an actual strong password)
CREATE USER 'hms_prod_user'@'localhost' IDENTIFIED BY 'STRONG_PROD_PASSWORD_HERE';
GRANT ALL PRIVILEGES ON hms_production.* TO 'hms_prod_user'@'localhost';

-- Staging user (replace 'STRONG_STAGING_PASSWORD_HERE' with an actual strong password)
CREATE USER 'hms_staging_user'@'localhost' IDENTIFIED BY 'STRONG_STAGING_PASSWORD_HERE';
GRANT ALL PRIVILEGES ON hms_staging.* TO 'hms_staging_user'@'localhost';

-- Apply the privilege changes
FLUSH PRIVILEGES;

-- Verify databases were created
SHOW DATABASES;

-- Exit MySQL
EXIT;
```

> [!IMPORTANT]
> **Save these passwords!** You will need them for the environment files later.
> - Production DB password: `STRONG_PROD_PASSWORD_HERE` (replace with your actual password)
> - Staging DB password: `STRONG_STAGING_PASSWORD_HERE` (replace with your actual password)

### Step 2.6 — Install Redis (For Caching)

```bash
sudo apt install -y redis-server
```

Configure Redis to run as a service:

```bash
sudo nano /etc/redis/redis.conf
```

Find the line `supervised no` and change it to:

```
supervised systemd
```

Also, find `bind 127.0.0.1 ::1` and make sure it's uncommented (not starting with `#`). This ensures Redis only accepts local connections.

Optionally, set a Redis password. Find `# requirepass foobared` and change to:

```
requirepass YOUR_REDIS_PASSWORD
```

Save and restart Redis:

```bash
sudo systemctl restart redis-server
sudo systemctl enable redis-server
```

Verify Redis is running:

```bash
redis-cli ping
```

Expected output: `PONG` (or if you set a password, use `redis-cli -a YOUR_REDIS_PASSWORD ping`)

### Step 2.7 — Install Nginx (Reverse Proxy & Web Server)

```bash
sudo apt install -y nginx
```

Start and enable Nginx:

```bash
sudo systemctl start nginx
sudo systemctl enable nginx
```

Verify: Open your browser and go to `http://YOUR_VPS_IP` — you should see the Nginx welcome page.

### Step 2.8 — Install Git

```bash
sudo apt install -y git
```

### Step 2.9 — Install Certbot (For Free SSL Certificates)

```bash
sudo apt install -y certbot python3-certbot-nginx
```

---

## PHASE 3: Domain & DNS Configuration

> [!IMPORTANT]
> This is done in the **Hostinger control panel** (in your browser), NOT on the VPS.

### Step 3.1 — Understanding DNS (Simple Explanation)

**DNS (Domain Name System)** is like a phone book for the internet. When someone types `axonxmedtech.com`, DNS translates that to your VPS IP address so the browser knows where to go.

**A Record**: Maps a domain name directly to an IP address.  
**Subdomain**: A prefix added to your main domain (like `app.axonxmedtech.com`).

### Step 3.2 — Configure DNS Records in Hostinger

1. Log in to [Hostinger hPanel](https://hpanel.hostinger.com/)
2. Go to **Domains** → click `axonxmedtech.com` → **DNS / Nameservers** → **DNS Records**

Add the following **A records** (all pointing to your VPS IP):

| Type | Name | Value (IP Address) | TTL |
|------|------|-----|-----|
| A | `@` | `YOUR_VPS_IP` | 3600 |
| A | `www` | `YOUR_VPS_IP` | 3600 |
| A | `app` | `YOUR_VPS_IP` | 3600 |
| A | `api` | `YOUR_VPS_IP` | 3600 |
| A | `staging-app` | `YOUR_VPS_IP` | 3600 |
| A | `staging-api` | `YOUR_VPS_IP` | 3600 |

> [!NOTE]
> **How to add each record:**
> 1. Click "Add Record"
> 2. Type: Select **A**
> 3. Name: Enter the value from the "Name" column above (e.g., `@` for the root domain, `app` for `app.axonxmedtech.com`)
> 4. Points to: Enter your VPS IP address
> 5. TTL: Leave as default or set to 3600
> 6. Click **Add Record**

For the staging website domain (`staging-axonxmedtech.com`):

> [!IMPORTANT]
> Since `staging-axonxmedtech.com` is a completely different domain (not a subdomain), you have two options:
>
> **Option A (Recommended)**: If you also own `staging-axonxmedtech.com` as a separate domain, configure its DNS the same way — add an A record with `@` pointing to your VPS IP.
>
> **Option B (Free alternative)**: Use `staging.axonxmedtech.com` as a subdomain instead. This requires NO extra domain purchase. Just add one more A record:
> | Type | Name | Value | TTL |
> |---|---|---|---|
> | A | `staging` | `YOUR_VPS_IP` | 3600 |
>
> **This guide will use the subdomain approach (`staging.axonxmedtech.com`) since it's free. Adjust if you have a separate domain.**

Updated domain mapping (using subdomain approach):

| Purpose | Domain |
|---|---|
| PRODUCTION Website | `axonxmedtech.com` and `www.axonxmedtech.com` |
| PRODUCTION Frontend | `app.axonxmedtech.com` |
| PRODUCTION Backend | `api.axonxmedtech.com` |
| STAGING Website | `staging.axonxmedtech.com` |
| STAGING Frontend | `staging-app.axonxmedtech.com` |
| STAGING Backend | `staging-api.axonxmedtech.com` |

### Step 3.3 — Wait for DNS Propagation

DNS changes take **5 minutes to 48 hours** to propagate worldwide (usually 5–30 minutes with Hostinger).

To check if your DNS is working, run this from your VPS:

```bash
# Check each domain
dig +short axonxmedtech.com
dig +short app.axonxmedtech.com
dig +short api.axonxmedtech.com
dig +short staging.axonxmedtech.com
dig +short staging-app.axonxmedtech.com
dig +short staging-api.axonxmedtech.com
```

Each should return your VPS IP address. If not, wait a bit and try again.

---

## PHASE 4: Clone Your Projects & Set Up Directory Structure

### Step 4.1 — Create the Project Directories

```bash
# Create organized directory structure
sudo mkdir -p /var/www/hms/production/frontend
sudo mkdir -p /var/www/hms/production/backend
sudo mkdir -p /var/www/hms/staging/frontend
sudo mkdir -p /var/www/hms/staging/backend
sudo mkdir -p /var/www/website/production
sudo mkdir -p /var/www/website/staging

# Give ownership to the deploy user
sudo chown -R deploy:deploy /var/www/hms
sudo chown -R deploy:deploy /var/www/website
```

> **Directory layout explained**:
> ```
> /var/www/
> ├── hms/
> │   ├── production/
> │   │   ├── frontend/    ← HMS React app (main branch)
> │   │   └── backend/     ← HMS Spring Boot (main branch)
> │   └── staging/
> │       ├── frontend/    ← HMS React app (staging branch)
> │       └── backend/     ← HMS Spring Boot (staging branch)
> └── website/
>     ├── production/      ← Company website (main branch)
>     └── staging/         ← Company website (dev branch)
> ```

### Step 4.2 — Clone the HMS Repository (Production — main branch)

```bash
cd /var/www/hms/production

# Clone the main branch of your HMS repo
git clone -b main https://github.com/Ayush9535/HOSPITAL.git .
```

> **What `-b main` does**: Clones only the `main` branch.  
> **What `.` does**: Clones into the current directory instead of creating a subdirectory.

Wait — the repo has both `frontend/` and `backend/` inside. So the structure will be:
```
/var/www/hms/production/
├── frontend/
├── backend/
├── setup/
└── ...
```

Actually, let's restructure since your repo has both:

```bash
# Remove the pre-created subdirectories
rm -rf /var/www/hms/production/frontend /var/www/hms/production/backend

# Clone the PRODUCTION code (main branch)
cd /var/www/hms/production
git clone -b main https://github.com/Ayush9535/HOSPITAL.git .
```

### Step 4.3 — Clone the HMS Repository (Staging — staging branch)

```bash
# Clone the STAGING code (staging branch)
cd /var/www/hms/staging
git clone -b staging https://github.com/Ayush9535/HOSPITAL.git .
```

### Step 4.4 — Clone the Website Repository

```bash
# Clone the PRODUCTION website (main branch)
cd /var/www/website/production
git clone -b main https://github.com/YOUR_USERNAME/YOUR_WEBSITE_REPO.git .

# Clone the STAGING website (replace 'dev' with your branch name)
cd /var/www/website/staging
git clone -b dev https://github.com/YOUR_USERNAME/YOUR_WEBSITE_REPO.git .
```

> [!IMPORTANT]
> Replace `YOUR_USERNAME/YOUR_WEBSITE_REPO` with your actual website GitHub repo URL.

---

## PHASE 5: Configure Environment Variables

### Step 5.1 — Production Backend Environment File

```bash
nano /var/www/hms/production/backend/.env
```

Paste the following (replace the placeholder values):

```properties
# === PRODUCTION ENVIRONMENT (.env) ===

# Database
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/hms_production?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=Asia/Kolkata&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=hms_prod_user
SPRING_DATASOURCE_PASSWORD=STRONG_PROD_PASSWORD_HERE

# JWT Secret (Generate a random 64-character string — see tip below)
JWT_SECRET=REPLACE_WITH_A_VERY_LONG_RANDOM_SECRET_KEY_AT_LEAST_64_CHARACTERS

# CORS — Allow only production frontend
FRONTEND_URL=https://app.axonxmedtech.com

# Server port
PORT=8080

# Redis
SPRING_REDIS_HOST=localhost
SPRING_REDIS_PORT=6379
SPRING_REDIS_PASSWORD=YOUR_REDIS_PASSWORD
```

> [!TIP]
> Generate a strong JWT secret with this command:
> ```bash
> openssl rand -base64 64
> ```
> Copy the output and use it as your `JWT_SECRET`.

### Step 5.2 — Staging Backend Environment File

```bash
nano /var/www/hms/staging/backend/.env
```

Paste:

```properties
# === STAGING ENVIRONMENT (.env) ===

# Database (DIFFERENT database than production!)
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/hms_staging?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=Asia/Kolkata&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=hms_staging_user
SPRING_DATASOURCE_PASSWORD=STRONG_STAGING_PASSWORD_HERE

# JWT Secret (can be different from production for extra safety)
JWT_SECRET=REPLACE_WITH_ANOTHER_DIFFERENT_RANDOM_SECRET_KEY

# CORS — Allow only staging frontend
FRONTEND_URL=https://staging-app.axonxmedtech.com

# Server port (DIFFERENT from production!)
PORT=8081

# Redis
SPRING_REDIS_HOST=localhost
SPRING_REDIS_PORT=6379
SPRING_REDIS_PASSWORD=YOUR_REDIS_PASSWORD
```

### Step 5.3 — Production Frontend Environment File

```bash
nano /var/www/hms/production/frontend/.env
```

Paste:

```properties
# === PRODUCTION FRONTEND (.env) ===
VITE_API_BASE_URL=https://api.axonxmedtech.com
VITE_CLOUDINARY_CLOUD_NAME=dbcauttek
VITE_CLOUDINARY_UPLOAD_PRESET=hospital_logos
```

### Step 5.4 — Staging Frontend Environment File

```bash
nano /var/www/hms/staging/frontend/.env
```

Paste:

```properties
# === STAGING FRONTEND (.env) ===
VITE_API_BASE_URL=https://staging-api.axonxmedtech.com
VITE_CLOUDINARY_CLOUD_NAME=dbcauttek
VITE_CLOUDINARY_UPLOAD_PRESET=hospital_logos
```

---

## PHASE 6: Build & Deploy the Backend

### Step 6.1 — Build Production Backend

```bash
cd /var/www/hms/production/backend

# Load environment variables and build
set -a; source .env; set +a
mvn clean package -DskipTests
```

> **What this does**:
> - `set -a; source .env; set +a` → Loads the .env file as environment variables  
> - `mvn clean package` → Compiles the Java code and creates a JAR file  
> - `-DskipTests` → Skips running tests (faster build)  
> - The JAR file will be created at: `target/hospital-management-system-1.0.0.jar`

Verify the JAR was created:

```bash
ls -la target/*.jar
```

### Step 6.2 — Build Staging Backend

```bash
cd /var/www/hms/staging/backend
set -a; source .env; set +a
mvn clean package -DskipTests
```

### Step 6.3 — Create Systemd Service for Production Backend

Systemd keeps your backend running in the background and auto-restarts it if it crashes or the server reboots.

```bash
sudo nano /etc/systemd/system/hms-production.service
```

Paste:

```ini
[Unit]
Description=HMS Production Backend (Spring Boot)
After=network.target mysql.service redis-server.service
Wants=mysql.service redis-server.service

[Service]
User=deploy
Group=deploy
WorkingDirectory=/var/www/hms/production/backend
EnvironmentFile=/var/www/hms/production/backend/.env
ExecStart=/usr/bin/java -jar -Dspring.profiles.active=production -Xms256m -Xmx512m /var/www/hms/production/backend/target/hospital-management-system-1.0.0.jar
SuccessExitStatus=143
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

> **What each part means**:
> - `After=` → Start only after network, MySQL, and Redis are running
> - `User=deploy` → Run as the deploy user (not root, for security)
> - `EnvironmentFile=` → Load the .env file automatically
> - `ExecStart=` → The command to start the Java application
> - `-Xms256m -Xmx512m` → Allocate 256MB–512MB RAM (adjust based on your VPS RAM)
> - `Restart=always` → Auto-restart if it crashes
> - `RestartSec=10` → Wait 10 seconds before restarting

### Step 6.4 — Create Systemd Service for Staging Backend

```bash
sudo nano /etc/systemd/system/hms-staging.service
```

Paste:

```ini
[Unit]
Description=HMS Staging Backend (Spring Boot)
After=network.target mysql.service redis-server.service
Wants=mysql.service redis-server.service

[Service]
User=deploy
Group=deploy
WorkingDirectory=/var/www/hms/staging/backend
EnvironmentFile=/var/www/hms/staging/backend/.env
ExecStart=/usr/bin/java -jar -Dspring.profiles.active=staging -Xms256m -Xmx512m /var/www/hms/staging/backend/target/hospital-management-system-1.0.0.jar
SuccessExitStatus=143
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

### Step 6.5 — Start the Backend Services

```bash
# Reload systemd to recognize new services
sudo systemctl daemon-reload

# Start production backend
sudo systemctl start hms-production
sudo systemctl enable hms-production

# Start staging backend
sudo systemctl start hms-staging
sudo systemctl enable hms-staging
```

> `enable` means it will auto-start when the server reboots.

### Step 6.6 — Verify Backend Services Are Running

```bash
# Check production backend status
sudo systemctl status hms-production

# Check staging backend status
sudo systemctl status hms-staging
```

You should see `Active: active (running)` in green.

To check the application logs:

```bash
# View production logs (live streaming)
sudo journalctl -u hms-production -f

# View staging logs (live streaming)
sudo journalctl -u hms-staging -f
```

> Press `Ctrl+C` to stop viewing logs.

Test if the APIs respond:

```bash
curl http://localhost:8080/api/public/health
curl http://localhost:8081/api/public/health
```

---

## PHASE 7: Build & Deploy the Frontend

### Step 7.1 — Build Production Frontend

```bash
cd /var/www/hms/production/frontend

# Install dependencies
npm install

# Build for production (creates the 'dist' folder)
npm run build
```

> **What `npm run build` does**: Compiles and bundles the React app into static HTML/CSS/JS files in the `dist/` folder. These are the files Nginx will serve.

> [!NOTE]
> If you get a TypeScript error during `tsc && vite build`, you may need to fix type errors or temporarily bypass TypeScript checking by editing the build script in `package.json`:
> ```bash
> nano package.json
> ```
> Change `"build": "tsc && vite build"` to `"build": "vite build"` (removes TypeScript checking from the build process).

### Step 7.2 — Build Staging Frontend

```bash
cd /var/www/hms/staging/frontend
npm install
npm run build
```

---

## PHASE 8: Configure Nginx (Reverse Proxy)

> [!NOTE]
> **What is Nginx doing here?**
> - Nginx sits in front of everything and receives ALL incoming web traffic (ports 80 and 443)
> - Based on the domain name in the request, it routes traffic to the correct application
> - For frontends: serves the static `dist/` files directly (very fast)
> - For backends: forwards (proxies) requests to the Java app running on localhost ports
> - Handles SSL/HTTPS certificates

### Step 8.1 — Remove Default Nginx Config

```bash
sudo rm /etc/nginx/sites-enabled/default
```

### Step 8.2 — Production Website Config

```bash
sudo nano /etc/nginx/sites-available/website-production
```

Paste:

```nginx
server {
    listen 80;
    server_name axonxmedtech.com www.axonxmedtech.com;

    # Root directory for the website files
    root /var/www/website/production;
    index index.html;

    # Serve static files
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Cache static assets
    location ~* \.(jpg|jpeg|png|gif|ico|css|js|svg|woff|woff2|ttf|eot)$ {
        expires 30d;
        add_header Cache-Control "public, immutable";
    }
}
```

> [!NOTE]
> If your website uses a framework (Next.js, etc.) that runs a server, replace the static file config with a reverse proxy config similar to the backend configs below. Let me know your website's tech stack and I'll adjust this.

### Step 8.3 — Staging Website Config

```bash
sudo nano /etc/nginx/sites-available/website-staging
```

Paste:

```nginx
server {
    listen 80;
    server_name staging.axonxmedtech.com;

    root /var/www/website/staging;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location ~* \.(jpg|jpeg|png|gif|ico|css|js|svg|woff|woff2|ttf|eot)$ {
        expires 7d;
        add_header Cache-Control "public";
    }
}
```

### Step 8.4 — Production Frontend (HMS App) Config

```bash
sudo nano /etc/nginx/sites-available/hms-frontend-production
```

Paste:

```nginx
server {
    listen 80;
    server_name app.axonxmedtech.com;

    # Serve the built React app
    root /var/www/hms/production/frontend/dist;
    index index.html;

    # Handle client-side routing (React Router)
    # All routes should fall back to index.html so React handles routing
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Cache static assets (JS, CSS, images) for 30 days
    location ~* \.(jpg|jpeg|png|gif|ico|css|js|svg|woff|woff2|ttf|eot)$ {
        expires 30d;
        add_header Cache-Control "public, immutable";
    }

    # Security headers
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;
}
```

> **Key thing here**: `try_files $uri $uri/ /index.html;`  
> This is critical for React Router. When someone visits `app.axonxmedtech.com/login`, Nginx doesn't have a file called `/login`. This rule tells Nginx to serve `index.html` for all routes, and React Router handles the rest.

### Step 8.5 — Staging Frontend (HMS App) Config

```bash
sudo nano /etc/nginx/sites-available/hms-frontend-staging
```

Paste:

```nginx
server {
    listen 80;
    server_name staging-app.axonxmedtech.com;

    root /var/www/hms/staging/frontend/dist;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location ~* \.(jpg|jpeg|png|gif|ico|css|js|svg|woff|woff2|ttf|eot)$ {
        expires 7d;
        add_header Cache-Control "public";
    }

    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
}
```

### Step 8.6 — Production Backend (HMS API) Config

```bash
sudo nano /etc/nginx/sites-available/hms-backend-production
```

Paste:

```nginx
server {
    listen 80;
    server_name api.axonxmedtech.com;

    # Maximum upload size (for file uploads, PDF generation, etc.)
    client_max_body_size 50M;

    # Proxy all requests to the Spring Boot application running on port 8080
    location / {
        proxy_pass http://localhost:8080;
        proxy_http_version 1.1;

        # Pass original client information to the backend
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Timeout settings
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }

    # WebSocket support — CRITICAL for your real-time features
    location /ws/ {
        proxy_pass http://localhost:8080;
        proxy_http_version 1.1;

        # These headers upgrade HTTP connection to WebSocket
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Keep WebSocket connections alive for longer
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
```

> **WebSocket note**: Your HMS app uses WebSocket at `/ws/hospital/{hospitalId}`. The `/ws/` location block handles the HTTP→WebSocket protocol upgrade that is required. Without this, real-time features would break.

### Step 8.7 — Staging Backend (HMS API) Config

```bash
sudo nano /etc/nginx/sites-available/hms-backend-staging
```

Paste:

```nginx
server {
    listen 80;
    server_name staging-api.axonxmedtech.com;

    client_max_body_size 50M;

    location / {
        proxy_pass http://localhost:8081;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }

    location /ws/ {
        proxy_pass http://localhost:8081;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
```

### Step 8.8 — Enable All Nginx Configs

```bash
# Create symbolic links to enable each site
sudo ln -s /etc/nginx/sites-available/website-production /etc/nginx/sites-enabled/
sudo ln -s /etc/nginx/sites-available/website-staging /etc/nginx/sites-enabled/
sudo ln -s /etc/nginx/sites-available/hms-frontend-production /etc/nginx/sites-enabled/
sudo ln -s /etc/nginx/sites-available/hms-frontend-staging /etc/nginx/sites-enabled/
sudo ln -s /etc/nginx/sites-available/hms-backend-production /etc/nginx/sites-enabled/
sudo ln -s /etc/nginx/sites-available/hms-backend-staging /etc/nginx/sites-enabled/
```

### Step 8.9 — Test and Reload Nginx

```bash
# Test the configuration for syntax errors
sudo nginx -t
```

Expected output:
```
nginx: the configuration file /etc/nginx/nginx.conf syntax is ok
nginx: configuration file /etc/nginx/nginx.conf test is successful
```

If it shows errors, fix them before proceeding. Then reload:

```bash
sudo systemctl reload nginx
```

---

## PHASE 9: SSL Certificates (HTTPS)

> [!IMPORTANT]
> Your domains MUST be pointing to your VPS IP before this step works. Verify with `dig +short axonxmedtech.com` first.

### Step 9.1 — Get SSL Certificates for ALL Domains

Run this single command to get certificates for all domains:

```bash
sudo certbot --nginx \
  -d axonxmedtech.com \
  -d www.axonxmedtech.com \
  -d app.axonxmedtech.com \
  -d api.axonxmedtech.com \
  -d staging.axonxmedtech.com \
  -d staging-app.axonxmedtech.com \
  -d staging-api.axonxmedtech.com
```

> Certbot will ask you:
> 1. **Enter email address** → Enter your email (for renewal notifications)
> 2. **Agree to terms** → `A` (Agree)
> 3. **Share email with EFF?** → `N` (No) or `Y` (Yes), your choice
> 4. It will automatically configure Nginx to use HTTPS and redirect HTTP→HTTPS

> [!NOTE]
> If some domains haven't propagated yet, you can do them individually later:
> ```bash
> sudo certbot --nginx -d specific-domain.axonxmedtech.com
> ```

### Step 9.2 — Verify HTTPS is Working

Open your browser and visit:
- `https://axonxmedtech.com` → Should show your website (or Nginx default until website is deployed)
- `https://app.axonxmedtech.com` → Should show the HMS frontend
- `https://api.axonxmedtech.com/api/public/health` → Should return a response from the backend

### Step 9.3 — Set Up Auto-Renewal for SSL

Let's Encrypt certificates expire every 90 days. Certbot creates an auto-renewal cron job, but let's verify:

```bash
# Test the renewal process (dry run — doesn't actually renew)
sudo certbot renew --dry-run
```

If successful, you're all set. Certificates will auto-renew.

---

## PHASE 10: Initialize the Database (Super Admin Setup)

### Step 10.1 — Create the Super Admin for Production

Since your backend uses `spring.jpa.hibernate.ddl-auto=update`, the tables will be automatically created when the backend starts for the first time. After the backend is running:

```bash
sudo mysql -u hms_prod_user -p hms_production
```

Enter the production database password, then run:

```sql
-- Create Super Admin user
-- Email: admin@hms.com
-- Password: admin123 (BCrypt encoded)
INSERT INTO users (email, password, name, role, hospital_id, is_active, public_id, created_at)
VALUES (
    'admin@hms.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhCy',
    'Super Admin',
    'SUPER_ADMIN',
    NULL,
    TRUE,
    'SUPER_ADMIN_001',
    NOW()
);

-- Verify
SELECT id, email, name, role FROM users WHERE role = 'SUPER_ADMIN';

EXIT;
```

### Step 10.2 — Create the Super Admin for Staging

```bash
sudo mysql -u hms_staging_user -p hms_staging
```

Run the same SQL as above.

> [!TIP]
> **Change the default password immediately** after first login via the application UI. `admin123` is not secure for production.

---

## PHASE 11: Deployment Scripts (For Easy Updates)

### Step 11.1 — Create Production Deployment Script

```bash
nano /var/www/hms/deploy-production.sh
```

Paste:

```bash
#!/bin/bash
# =====================================================
# HMS Production Deployment Script
# Pulls latest code from 'main' branch and redeploys
# =====================================================

set -e  # Exit on any error

echo "=========================================="
echo "  HMS PRODUCTION DEPLOYMENT"
echo "  $(date)"
echo "=========================================="

# --- Backend ---
echo ""
echo ">>> [1/4] Pulling latest backend code (main branch)..."
cd /var/www/hms/production
git fetch origin main
git reset --hard origin/main

echo ">>> [2/4] Building backend JAR..."
cd /var/www/hms/production/backend
set -a; source .env; set +a
mvn clean package -DskipTests

echo ">>> [3/4] Restarting backend service..."
sudo systemctl restart hms-production

# --- Frontend ---
echo ">>> [4/4] Building frontend..."
cd /var/www/hms/production/frontend
npm install
npm run build

echo ""
echo "=========================================="
echo "  ✅ PRODUCTION DEPLOYMENT COMPLETE!"
echo "=========================================="
echo ""
echo "  Frontend: https://app.axonxmedtech.com"
echo "  Backend:  https://api.axonxmedtech.com"
echo ""

# Verify backend started
sleep 5
sudo systemctl status hms-production --no-pager
```

Make it executable:

```bash
chmod +x /var/www/hms/deploy-production.sh
```

### Step 11.2 — Create Staging Deployment Script

```bash
nano /var/www/hms/deploy-staging.sh
```

Paste:

```bash
#!/bin/bash
# =====================================================
# HMS Staging Deployment Script
# Pulls latest code from 'staging' branch and redeploys
# =====================================================

set -e

echo "=========================================="
echo "  HMS STAGING DEPLOYMENT"
echo "  $(date)"
echo "=========================================="

# --- Backend ---
echo ""
echo ">>> [1/4] Pulling latest backend code (staging branch)..."
cd /var/www/hms/staging
git fetch origin staging
git reset --hard origin/staging

echo ">>> [2/4] Building backend JAR..."
cd /var/www/hms/staging/backend
set -a; source .env; set +a
mvn clean package -DskipTests

echo ">>> [3/4] Restarting backend service..."
sudo systemctl restart hms-staging

# --- Frontend ---
echo ">>> [4/4] Building frontend..."
cd /var/www/hms/staging/frontend
npm install
npm run build

echo ""
echo "=========================================="
echo "  ✅ STAGING DEPLOYMENT COMPLETE!"
echo "=========================================="
echo ""
echo "  Frontend: https://staging-app.axonxmedtech.com"
echo "  Backend:  https://staging-api.axonxmedtech.com"
echo ""

sleep 5
sudo systemctl status hms-staging --no-pager
```

Make it executable:

```bash
chmod +x /var/www/hms/deploy-staging.sh
```

### Step 11.3 — Create Website Deployment Script

```bash
nano /var/www/website/deploy-website.sh
```

Paste:

```bash
#!/bin/bash
# =====================================================
# Website Deployment Script
# =====================================================

set -e

ENVIRONMENT=${1:-production}  # Default to production

echo "=========================================="
echo "  WEBSITE DEPLOYMENT ($ENVIRONMENT)"
echo "  $(date)"
echo "=========================================="

if [ "$ENVIRONMENT" = "production" ]; then
    cd /var/www/website/production
    BRANCH="main"
elif [ "$ENVIRONMENT" = "staging" ]; then
    cd /var/www/website/staging
    BRANCH="dev"
else
    echo "Usage: ./deploy-website.sh [production|staging]"
    exit 1
fi

echo ">>> Pulling latest code ($BRANCH branch)..."
git fetch origin $BRANCH
git reset --hard origin/$BRANCH

# If your website has a build step (e.g., npm build), uncomment these:
# echo ">>> Installing dependencies..."
# npm install
# echo ">>> Building..."
# npm run build

echo ""
echo "=========================================="
echo "  ✅ WEBSITE ($ENVIRONMENT) DEPLOYED!"
echo "=========================================="
```

Make it executable:

```bash
chmod +x /var/www/website/deploy-website.sh
```

### Usage of Deployment Scripts

```bash
# Deploy production HMS
/var/www/hms/deploy-production.sh

# Deploy staging HMS
/var/www/hms/deploy-staging.sh

# Deploy production website
/var/www/website/deploy-website.sh production

# Deploy staging website
/var/www/website/deploy-website.sh staging
```

---

## PHASE 12: Useful Commands Reference

### Service Management

```bash
# --- HMS Backend ---
sudo systemctl start hms-production        # Start production backend
sudo systemctl stop hms-production         # Stop production backend
sudo systemctl restart hms-production      # Restart production backend
sudo systemctl status hms-production       # Check status

sudo systemctl start hms-staging           # Start staging backend
sudo systemctl stop hms-staging            # Stop staging backend
sudo systemctl restart hms-staging         # Restart staging backend
sudo systemctl status hms-staging          # Check status

# --- Nginx ---
sudo systemctl restart nginx               # Restart Nginx
sudo nginx -t                              # Test Nginx config
sudo systemctl reload nginx                # Reload without downtime

# --- MySQL ---
sudo systemctl status mysql                # Check MySQL status
sudo mysql -u root -p                      # Login to MySQL as root

# --- Redis ---
sudo systemctl status redis-server         # Check Redis status
redis-cli ping                             # Test Redis connection
```

### Viewing Logs

```bash
# Backend application logs (live)
sudo journalctl -u hms-production -f       # Production logs
sudo journalctl -u hms-staging -f          # Staging logs

# Last 100 lines of logs
sudo journalctl -u hms-production -n 100

# Nginx access logs
sudo tail -f /var/log/nginx/access.log

# Nginx error logs
sudo tail -f /var/log/nginx/error.log
```

### Database Operations

```bash
# Login to production database
sudo mysql -u hms_prod_user -p hms_production

# Login to staging database
sudo mysql -u hms_staging_user -p hms_staging

# Backup production database
mysqldump -u hms_prod_user -p hms_production > ~/backup_production_$(date +%Y%m%d).sql

# Backup staging database
mysqldump -u hms_staging_user -p hms_staging > ~/backup_staging_$(date +%Y%m%d).sql

# Restore from backup
mysql -u hms_prod_user -p hms_production < ~/backup_production_20260608.sql
```

### Server Monitoring

```bash
# Check disk space
df -h

# Check memory usage
free -h

# Check running processes (CPU/memory)
htop    # (install with: sudo apt install htop)

# Check which ports are in use
sudo ss -tlnp
```

---

## PHASE 13: Automated Database Backups (Recommended)

### Step 13.1 — Create Backup Script

```bash
sudo mkdir -p /var/backups/hms
sudo chown deploy:deploy /var/backups/hms

nano /var/www/hms/backup-databases.sh
```

Paste:

```bash
#!/bin/bash
# =====================================================
# HMS Database Backup Script
# Runs daily via cron, keeps last 7 days of backups
# =====================================================

BACKUP_DIR="/var/backups/hms"
DATE=$(date +%Y%m%d_%H%M%S)

echo "[$(date)] Starting database backup..."

# Backup Production
mysqldump -u hms_prod_user -pSTRONG_PROD_PASSWORD_HERE hms_production > "$BACKUP_DIR/production_$DATE.sql"
echo "[$(date)] Production backup complete."

# Backup Staging
mysqldump -u hms_staging_user -pSTRONG_STAGING_PASSWORD_HERE hms_staging > "$BACKUP_DIR/staging_$DATE.sql"
echo "[$(date)] Staging backup complete."

# Delete backups older than 7 days
find $BACKUP_DIR -name "*.sql" -type f -mtime +7 -delete
echo "[$(date)] Old backups cleaned up."

echo "[$(date)] All backups complete!"
```

> [!CAUTION]
> Replace `STRONG_PROD_PASSWORD_HERE` and `STRONG_STAGING_PASSWORD_HERE` with your actual MySQL passwords.

Make it executable:

```bash
chmod +x /var/www/hms/backup-databases.sh
```

### Step 13.2 — Schedule Daily Backups with Cron

```bash
crontab -e
```

> If asked to choose an editor, select **nano** (option 1).

Add this line at the bottom:

```cron
# Daily database backup at 2 AM
0 2 * * * /var/www/hms/backup-databases.sh >> /var/backups/hms/backup.log 2>&1
```

Save and exit. Verify:

```bash
crontab -l
```

---

## PHASE 14: Security Hardening Checklist

### Step 14.1 — Install Fail2Ban (Blocks Repeated Login Attempts)

```bash
sudo apt install -y fail2ban
sudo systemctl enable fail2ban
sudo systemctl start fail2ban
```

### Step 14.2 — Secure Nginx Headers (Global)

```bash
sudo nano /etc/nginx/conf.d/security-headers.conf
```

Paste:

```nginx
# Security headers applied to all sites
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
add_header X-Frame-Options "SAMEORIGIN" always;
add_header X-Content-Type-Options "nosniff" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
```

Reload Nginx:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

### Step 14.3 — Enable Automatic Security Updates

```bash
sudo apt install -y unattended-upgrades
sudo dpkg-reconfigure --priority=low unattended-upgrades
```

> Select **Yes** when asked about automatic updates.

---

## 🧪 Final Verification Checklist

After completing all steps, verify everything works:

| # | Check | Command / URL | Expected |
|---|-------|-------------|----------|
| 1 | Production website loads | `https://axonxmedtech.com` | Website displays |
| 2 | Staging website loads | `https://staging.axonxmedtech.com` | Website displays |
| 3 | Production HMS frontend | `https://app.axonxmedtech.com` | Login page displays |
| 4 | Staging HMS frontend | `https://staging-app.axonxmedtech.com` | Login page displays |
| 5 | Production API health | `https://api.axonxmedtech.com/api/public/health` | JSON response |
| 6 | Staging API health | `https://staging-api.axonxmedtech.com/api/public/health` | JSON response |
| 7 | Production login | Login at `app.axonxmedtech.com/platform/login` | Super Admin dashboard |
| 8 | Staging login | Login at `staging-app.axonxmedtech.com/platform/login` | Super Admin dashboard |
| 9 | SSL padlock shown | All URLs above | 🔒 padlock in browser |
| 10 | Backend services running | `sudo systemctl status hms-production hms-staging` | Both `active (running)` |

---

## 🆘 Common Troubleshooting

### Backend won't start
```bash
# Check the error in logs
sudo journalctl -u hms-production -n 50 --no-pager

# Common causes:
# 1. Wrong database credentials in .env
# 2. MySQL not running: sudo systemctl status mysql
# 3. Redis not running: sudo systemctl status redis-server
# 4. Port already in use: sudo ss -tlnp | grep 8080
# 5. JAR file not found: ls /var/www/hms/production/backend/target/*.jar
```

### Frontend shows blank page
```bash
# Check if dist folder exists and has files
ls -la /var/www/hms/production/frontend/dist/

# Check Nginx error logs
sudo tail -20 /var/log/nginx/error.log

# Make sure Nginx can read the files
sudo chmod -R 755 /var/www/hms/production/frontend/dist/
```

### CORS errors in browser console
```bash
# Check that FRONTEND_URL in backend .env matches exactly
# For production: FRONTEND_URL=https://app.axonxmedtech.com
# For staging: FRONTEND_URL=https://staging-app.axonxmedtech.com
# Then restart the backend:
sudo systemctl restart hms-production
```

### WebSocket not connecting
```bash
# Ensure the Nginx backend config has the /ws/ location block
# Check Nginx config:
sudo cat /etc/nginx/sites-available/hms-backend-production | grep -A 10 "ws"
```

### SSL certificate not working
```bash
# Re-run certbot for a specific domain
sudo certbot --nginx -d domain-that-failed.axonxmedtech.com

# Check certificate status
sudo certbot certificates
```

### Out of memory (VPS running slow)
```bash
# Check memory
free -h

# Reduce Java heap in systemd service files
# Change -Xmx512m to -Xmx256m
sudo nano /etc/systemd/system/hms-production.service
sudo systemctl daemon-reload
sudo systemctl restart hms-production
```

### How to deploy new changes
```bash
# 1. Push changes to GitHub from your PC (main or staging branch)
# 2. SSH into VPS: ssh deploy@YOUR_VPS_IP
# 3. Run the appropriate deployment script:
/var/www/hms/deploy-production.sh   # For production
/var/www/hms/deploy-staging.sh      # For staging
```

---

## 📊 Recommended VPS Resources

| Component | Minimum RAM | Recommended RAM |
|---|---|---|
| Production Backend (Java) | 256 MB | 512 MB |
| Staging Backend (Java) | 256 MB | 512 MB |
| MySQL | 256 MB | 512 MB |
| Redis | 64 MB | 128 MB |
| Nginx | 32 MB | 64 MB |
| OS + Overhead | 256 MB | 512 MB |
| **Total** | **~1.1 GB** | **~2.2 GB** |

> [!IMPORTANT]
> Your VPS should have at least **2 GB RAM** to run both production and staging environments comfortably. If your VPS has only 1 GB, consider running only the production environment on the VPS and using your local machine for staging/development.

---

> **You're all set!** 🎉 Follow the phases in order, and at the end you'll have a fully deployed, production-ready HMS with separate production and staging environments, SSL certificates, automated backups, and easy deployment scripts.
