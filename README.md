# Hospital Management System (HMS)

A multi-tenant Hospital Management System built with Spring Boot, React, and MySQL.

## 🏥 Overview

This Hospital Management System is a multi-tenant SaaS platform that streamlines hospital operations across clinical, administrative, and inventory departments. The system supports standard workflows for OPD (Out-Patient Department), IPD (In-Patient Department), Pharmacy inventory, Hospital inventory tracking, configurable fees, support ticketing, and audit logging.

- **Super Admin**: Platform owner who manages hospitals
- **Hospital Admin**: Manages daily OPD operations for their hospital
- **Doctor**: Views appointments and patients

## 🛠️ Tech Stack

### Backend
- Java 17
- Spring Boot 3.2.0
- Spring Security with JWT
- Spring Data JPA
- MySQL 8.0
- Lombok / Maven

### Frontend
- React 18
- React Router DOM
- Axios
- Tailwind CSS 3

## 👥 Roles & Features

### Super Admin (Platform level)
- Separate login at `/platform/login`
- Create hospitals with admin credentials
- View all hospitals
- Activate/Deactivate hospitals
- Cannot see hospital data (patients, doctors, etc.)
- Manage hospital tenant registry (registration, activation, deactivation).
- Technical support ticketing dashboard.
- Platform FAQ management.
- Platform-level audit trails.

### Hospital Admin
- Login at `/login`
- Manage staff accounts (Doctors, Pharmacists, Receptionists, and Wards Admins).
- Manage hospital-wide audit logging and system events.
- Configure default Hospital Fees (consultations, case papers, and custom procedures).
- Setup Wards and Bed availability for IPD admissions.
- Manage clinical supplies catalog and configure inventory stock linkages.

### Doctor
- Login at `/login`
- Conduct patient consultations, document symptoms/diagnoses, and write prescriptions.
- Administer in-clinic medicines and hospital inventory items (which automatically degrades inventory stock and applies scaled linked fees: `quantity * fee`).
- Order lab tests and diagnostics.
- Manage IPD Admissions, daily follow-ups, and patient discharge summaries.

### Pharmacist
- Login at `/login`
- Manage medicine database, unit pricing, and active status.
- File medicine purchases for stock replenishment.
- View and dispense medications for doctor prescriptions.

### Receptionist
- Login at `/login`
- Register patients, track demographics, and assign custom public IDs.
- Schedule and queue patient appointments.
- Generate and print patient invoices, collect payments, and track outstanding bills.

## 🚀 Setup Instructions

### Prerequisites
- Java 17 or higher
- Maven 3.6+
- MySQL 8.0+
- Node.js 18+ and npm

### Database Setup

1. Create a MySQL database:
```sql
CREATE DATABASE hospital_management;
```

2. Configure database credentials in `backend/src/main/resources/application.properties`:
```properties
spring.datasource.username=root
spring.datasource.password=your_password
```

3. Create the initial Super Admin user (run after first backend startup):
```sql
USE hospital_management;
INSERT INTO users (email, password, name, role, hospital_id, created_at)
VALUES ('admin@hms.com', '$2a$10$qMUbT7gyNjvCsRS//Gf7g.1vFwZAq9RVSn3qpLuPjUzoB8fz0AxWy', 'Super Admin', 'SUPER_ADMIN', NULL, NOW());
```

### Backend Setup

1. Navigate to backend directory:
```bash
cd backend
```

2. Build the project:
```bash
mvn clean install
```

3. Run the application:
```bash
mvn spring-boot:run
```

Backend will start on `http://localhost:8080`

### Frontend Setup

1. Navigate to frontend directory:
```bash
cd frontend
```

2. Install dependencies:
```bash
npm install
```

3. Start development server:
```bash
npm run dev
```


Frontend will start on `http://localhost:5173`.

## 📁 Project Structure

```
Hospital Management/
├── backend/
│   ├── src/main/java/com/hms/
│   │   ├── entity/          # Database entities
│   │   ├── repository/      # JPA repositories
│   │   ├── service/         # Business logic (Platform & Hospital namespaces)
│   │   ├── controller/      # REST endpoints (Platform & Hospital namespaces)
│   │   ├── security/        # JWT parsing and security configuration
│   │   ├── config/          # Spring beans and WebConfig
│   │   └── dto/             # Data Transfer Objects
│   └── pom.xml
└── frontend/
    ├── src/
    │   ├── pages/           # Pages (Platform & Hospital namespaces)
    │   ├── components/      # Reusable UI components
    │   ├── services/        # Axios API handlers
    │   └── App.jsx          # Router and main layout context
    └── package.json
```

## ⚠️ Limitations & Future Scope
- ❌ External payment gateway integration (Stripe/Razorpay)
- ❌ Automatic SMS and Email patient notifications
- ❌ Dedicated native mobile applications (iOS/Android)
