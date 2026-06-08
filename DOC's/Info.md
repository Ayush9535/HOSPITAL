# 🏥 Hospital Management System (HMS) — Project Info

---

## 📌 What Is This Project?

The **Hospital Management System (HMS)** is a cloud-based, enterprise-grade **SaaS (Software as a Service)** platform designed to digitize and streamline the complete day-to-day operations of a hospital.

It is built as a **multi-tenant** platform — meaning a single system can power multiple hospitals at the same time, each with their own completely isolated data, users, and settings.

---

## 🏗️ How It Works (Simple Explanation)

Think of it like a building with multiple floors:

- **We (the platform owner / System Admin)** own and manage the entire building.
- Each **hospital client** gets their own private floor (their own account, their own data).
- One hospital **cannot see** or access another hospital's data — full privacy is guaranteed.
- We can add new hospital clients, activate or deactivate them at any time.

---

## 👥 Who Uses This System?

There are **two levels** of access:

### 🔐 Level 1 — System Admin (That's Us)
> Internal access only. The hospital client never sees or touches this.

- We log in through a **separate, hidden admin portal**.
- We create and manage hospital accounts.
- We control which hospitals are active or suspended.
- We have **no access to any hospital's patient data** (privacy by design).

---

### 🏥 Level 2 — Hospital Client Access
> This is what the client (hospital) gets when they purchase a subscription.

Each hospital gets access to the following **user roles** inside their account:

| Role | Who Uses It |
|------|-------------|
| **Hospital Admin** | The owner or administrator of the hospital |
| **Doctor** | Practicing physicians and specialists |
| **Receptionist** | Front desk / OPD staff |
| **Pharmacist** | Pharmacy counter staff |

Each role has its own **dedicated dashboard** with only the features relevant to that role.

---

## 🛠️ Technology Used

> *(This section is for internal/technical reference — not needed for the client presentation)*

| Layer | Technology |
|-------|------------|
| Backend (Server) | Java 17, Spring Boot 3.2, Spring Security + JWT |
| Database | MySQL 8.0 |
| Frontend (UI) | React 18, Vite, Tailwind CSS |
| Authentication | JWT-based token system with role-based access |
| Architecture | Multi-tenant SaaS (hospital_id based data isolation) |

---

## 🔒 Security & Data Privacy

- Each hospital's data is **completely isolated** from others.
- Secure **login with JWT tokens** — sessions expire automatically.
- Role-based access — a Receptionist cannot access Doctor or Admin features.
- Passwords are **encrypted** (BCrypt hashing).
- Audit logs track all important actions inside the hospital.

---

## 📦 What Modules Are Available?

The system currently includes the following modules:

| Module | Description |
|--------|-------------|
| 🧑‍⚕️ **OPD Management** | Out-Patient Department — appointments, queue, consultations |
| 🛏️ **IPD Management** | In-Patient Department — admissions, ward/bed management |
| 💊 **Pharmacy Management** | Medicine inventory, billing, purchases, suppliers |
| 💰 **Billing & Payments** | Patient billing, invoice generation, payment tracking |
| 👨‍⚕️ **Doctor Management** | Doctor profiles, schedules, fees |
| 🧑 **Patient Management** | Patient records, history, prescriptions |
| 🏥 **Reception / Front Desk** | Appointments, patient registration, queue management |
| 📊 **Reports & Analytics** | Revenue, sales, stock, and operational reports |
| ⚙️ **Hospital Settings** | Hospital profile, fees configuration, system preferences |

---

## 🚀 Deployment & Access

- The system runs on a **cloud server** — no software installation needed by the client.
- The hospital admin and staff access everything through a **web browser** (Chrome, Edge, Firefox).
- Works on **desktops and laptops** — no app download required.

---

## 📞 Support

- Clients can raise **support tickets** directly from within the system.
- The platform admin (us) manages and resolves tickets from the central admin panel.

---

## 📄 License

Proprietary — All rights reserved. Unauthorized use or distribution is prohibited.

---

*Last Updated: June 2026 | HMS Development Team*
