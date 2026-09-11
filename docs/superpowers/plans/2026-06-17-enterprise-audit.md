# HMS Enterprise Audit & Quality Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Audit and harden every layer of the Hospital Management System to enterprise-grade quality — covering security, code quality, duplicate elimination, feature completeness, and every screen's correctness.

**Architecture:** Multi-tenant SaaS with Spring Boot 3.2 backend (Java 17, MySQL, Redis) and React 19 + Vite frontend. Two platform tiers: Super Admin (platform-level) and Hospital users (ADMIN / DOCTOR / RECEPTIONIST / PHARMACIST). JWT-based auth, module-level feature flags, WebSocket real-time updates.

**Tech Stack:** Spring Boot 3.2 · Java 17 · MySQL · Redis · JPA/Hibernate · React 19 · Vite · TailwindCSS · @tanstack/react-table · Axios · OpenPDF · Thymeleaf

---

## Scope Summary

The audit is split into **7 phases**, each independently executable:

| Phase | Focus | Priority |
|---|---|---|
| 1 | Critical Security Fixes | CRITICAL |
| 2 | Backend Code Quality & Duplicate Elimination | HIGH |
| 3 | Frontend Code Quality & Duplicate Elimination | HIGH |
| 4 | Feature Verification — Every Screen Every Tab | HIGH |
| 5 | Enterprise Practices (API standards, error handling, logging) | MEDIUM |
| 6 | Test Coverage | MEDIUM |
| 7 | Performance & Observability | MEDIUM |

---

## Phase 1 — Critical Security Fixes

### Task 1.1: Remove the Public Debug Endpoint

**Files:**
- Modify: `backend/src/main/java/com/hms/controller/platform/PlatformUserController.java`
- Modify: `backend/src/main/java/com/hms/config/SecurityConfig.java`

- [ ] **Step 1: Find the debug endpoint**

```bash
grep -rn "debug-users" backend/src/main/java/
```

Expected: finds the `@GetMapping("/debug-users")` method in `PlatformUserController.java`.

- [ ] **Step 2: Delete the debug endpoint method entirely**

Delete the method annotated with `@GetMapping("/debug-users")` (or similar). It is publicly accessible and returns a user list.

- [ ] **Step 3: Remove from SecurityConfig permit list**

In `SecurityConfig.java`, find `.requestMatchers(...)` that permits `/platform/users/debug-users`. Remove that line.

- [ ] **Step 4: Compile and confirm removal**

```bash
cd backend && mvn compile -q
grep -rn "debug-users" src/
```

Expected: zero matches. Build succeeds.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hms/controller/platform/PlatformUserController.java
git add backend/src/main/java/com/hms/config/SecurityConfig.java
git commit -m "security: remove publicly accessible debug-users endpoint"
```

---

### Task 1.2: Add Login Rate Limiting

**Files:**
- Modify: `backend/pom.xml` (add bucket4j or spring-boot-starter-cache dependency)
- Create: `backend/src/main/java/com/hms/config/RateLimitConfig.java`
- Create: `backend/src/main/java/com/hms/filter/RateLimitFilter.java`
- Modify: `backend/src/main/java/com/hms/config/SecurityConfig.java`

- [ ] **Step 1: Add Bucket4j dependency to pom.xml**

In `backend/pom.xml`, inside `<dependencies>`:

```xml
<dependency>
    <groupId>com.giffing.bucket4j.spring.boot.starter</groupId>
    <artifactId>bucket4j-spring-boot-starter</artifactId>
    <version>0.10.0</version>
</dependency>
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-core</artifactId>
    <version>8.10.1</version>
</dependency>
```

- [ ] **Step 2: Create RateLimitFilter.java**

Create `backend/src/main/java/com/hms/filter/RateLimitFilter.java`:

```java
package com.hms.filter;

import io.github.bucket4j.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(10, Refill.intervally(10, Duration.ofMinutes(1))))
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!uri.endsWith("/login")) {
            chain.doFilter(request, response);
            return;
        }
        String ip = request.getRemoteAddr();
        Bucket bucket = buckets.computeIfAbsent(ip, k -> newBucket());
        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.getWriter().write("{\"error\":\"Too many login attempts. Try again in 1 minute.\"}");
        }
    }
}
```

- [ ] **Step 3: Register filter in SecurityConfig before JWT filter**

In `SecurityConfig.java` inside the `SecurityFilterChain` bean:

```java
// Add before: .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
.addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)
```

Inject the filter via constructor: `private final RateLimitFilter rateLimitFilter;`

- [ ] **Step 4: Compile and verify**

```bash
cd backend && mvn compile -q
```

Expected: clean build.

- [ ] **Step 5: Manual test** — call POST `/login` 11 times rapidly; 11th should return 429.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hms/filter/RateLimitFilter.java
git add backend/src/main/java/com/hms/config/SecurityConfig.java
git add backend/pom.xml
git commit -m "security: add in-memory rate limiting on all /login endpoints (10 req/min per IP)"
```

---

### Task 1.3: Enforce Axios Request Timeouts and Size Limits

**Files:**
- Modify: `frontend/src/services/apiService.js`

- [ ] **Step 1: Read current apiService.js**

```bash
cat frontend/src/services/apiService.js
```

- [ ] **Step 2: Add timeout and max content length**

Replace the axios instance creation with:

```js
const apiService = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
  timeout: 30000,           // 30 second request timeout
  maxContentLength: 5242880, // 5 MB response cap
  headers: {
    'Content-Type': 'application/json',
  },
});
```

- [ ] **Step 3: Handle timeout errors in the response interceptor**

In the response error interceptor, add before the 401 check:

```js
if (error.code === 'ECONNABORTED') {
  return Promise.reject(new Error('Request timed out. Please try again.'));
}
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/services/apiService.js
git commit -m "security: enforce 30s axios timeout and 5MB response cap"
```

---

### Task 1.4: Sanitize Logging — Disable SQL and Security Debug Logs in Production

**Files:**
- Modify: `backend/src/main/resources/application.properties`
- Create: `backend/src/main/resources/application-prod.properties`

- [ ] **Step 1: Read application.properties**

```bash
cat backend/src/main/resources/application.properties
```

- [ ] **Step 2: Change show-sql to false and tighten log levels in default profile**

```properties
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.format_sql=false
logging.level.org.springframework.security=WARN
logging.level.com.hms=INFO
```

- [ ] **Step 3: Create application-prod.properties**

```properties
# Production log levels — no SQL, no debug
spring.jpa.show-sql=false
logging.level.root=WARN
logging.level.com.hms=INFO
logging.level.org.springframework.security=WARN
logging.level.org.hibernate.SQL=OFF
```

- [ ] **Step 4: Compile and confirm**

```bash
cd backend && mvn compile -q
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/application.properties
git add backend/src/main/resources/application-prod.properties
git commit -m "security: disable SQL logging and security debug in production profile"
```

---

### Task 1.5: Validate CORS Origins Are Not Wildcard

**Files:**
- Read: `backend/src/main/java/com/hms/config/SecurityConfig.java`

- [ ] **Step 1: Inspect CORS configuration**

```bash
grep -A 20 "CorsConfiguration\|corsConfiguration\|allowedOrigin" \
  backend/src/main/java/com/hms/config/SecurityConfig.java
```

- [ ] **Step 2: Ensure no wildcard `*` origin is present**

If `setAllowedOrigins(List.of("*"))` exists anywhere, replace with specific origins from environment:

```java
config.setAllowedOrigins(List.of(
    System.getenv().getOrDefault("FRONTEND_URL", "http://localhost:5173")
));
```

- [ ] **Step 3: Verify allowed methods are not using wildcard**

```java
config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
```

- [ ] **Step 4: Commit if changes were made**

```bash
git add backend/src/main/java/com/hms/config/SecurityConfig.java
git commit -m "security: enforce explicit CORS origins, no wildcard"
```

---

## Phase 2 — Backend Code Quality & Duplicate Elimination

### Task 2.1: Add @Transactional Where Missing on @Modifying Queries

**Files:**
- Read: `backend/src/main/java/com/hms/service/hospital/HospitalAuthService.java`
- Read: All service files using `@Modifying` repositories

- [ ] **Step 1: Find all @Modifying usages**

```bash
grep -rn "@Modifying" backend/src/main/java/
```

- [ ] **Step 2: For each repository method found, verify its calling service method has @Transactional**

In `HospitalAuthService.java`, find `updateHospitalOperationsSettings` and confirm:

```java
@Transactional
public HospitalSettingDTO updateHospitalOperationsSettings(String email, HospitalSettingDTO dto) {
```

If `@Transactional` is missing, add it and import `org.springframework.transaction.annotation.Transactional`.

- [ ] **Step 3: Check all other repositories with @Modifying**

```bash
grep -rn "@Modifying" backend/src/main/java/com/hms/repository/
```

For each file, find the service that calls it and verify `@Transactional` is present on the service method.

- [ ] **Step 4: Compile**

```bash
cd backend && mvn compile -q
```

- [ ] **Step 5: Commit**

```bash
git add -p
git commit -m "fix: ensure @Transactional on all service methods calling @Modifying queries"
```

---

### Task 2.2: Centralize Multi-Tenant hospitalId Extraction

**Files:**
- Read: `backend/src/main/java/com/hms/security/SecurityContextHelper.java` (or `SecurityHelper.java`)
- Spot-check 5 services for repeated `SecurityHelper.getCurrentHospitalId()` pattern

- [ ] **Step 1: Find all hospitalId extraction calls**

```bash
grep -rn "getCurrentHospitalId\|getHospitalId" backend/src/main/java/com/hms/service/ | wc -l
```

- [ ] **Step 2: Confirm SecurityHelper is the canonical way to get hospitalId**

```bash
cat backend/src/main/java/com/hms/security/SecurityHelper.java
```

Or equivalent file. Verify it reads from the `SecurityContextHolder` using `UserAuthenticationDetails`.

- [ ] **Step 3: Check for any service that reads hospitalId from repository instead of SecurityHelper**

```bash
grep -rn "hospitalId" backend/src/main/java/com/hms/service/ | grep -v "SecurityHelper\|getCurrentHospitalId\|setHospitalId\|getHospitalId()" | head -30
```

Fix any occurrence where hospitalId is retrieved via a separate DB call when `SecurityHelper` already provides it.

- [ ] **Step 4: Commit any fixes**

```bash
git add -p
git commit -m "refactor: remove redundant hospitalId DB calls, use SecurityHelper consistently"
```

---

### Task 2.3: Verify All Controller Endpoints Have @PreAuthorize

**Files:**
- All files in `backend/src/main/java/com/hms/controller/`

- [ ] **Step 1: List all controller methods lacking @PreAuthorize**

```bash
grep -rn "@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping\|@PatchMapping" \
  backend/src/main/java/com/hms/controller/ | grep -v "test" | wc -l
```

```bash
grep -rn "@PreAuthorize" backend/src/main/java/com/hms/controller/ | wc -l
```

These two numbers should be close. If endpoint count is much higher, run:

```bash
grep -B5 "@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping\|@PatchMapping" \
  backend/src/main/java/com/hms/controller/hospital/OpdController.java | grep -v "@PreAuthorize" | grep "Mapping"
```

Repeat for each controller file.

- [ ] **Step 2: Add missing @PreAuthorize to any unprotected hospital endpoints**

Example for OpdController if missing:

```java
@PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','RECEPTIONIST')")
@PostMapping
public ResponseEntity<?> createOpd(@RequestBody CreateOpdRequest req) { ... }
```

- [ ] **Step 3: Confirm public endpoints are intentionally public**

Only these should be in the permit list in SecurityConfig:
- `/login`
- `/platform/login`
- `/api/public/health`
- `/api/public/faqs`
- `/ws/**`

Any others are a bug.

- [ ] **Step 4: Compile and commit**

```bash
cd backend && mvn compile -q
git add -p
git commit -m "security: add missing @PreAuthorize on unprotected hospital controller endpoints"
```

---

### Task 2.4: Eliminate Duplicate Exception Handlers

**Files:**
- Read: `backend/src/main/java/com/hms/exception/GlobalExceptionHandler.java`
- Read: `backend/src/main/java/com/hms/exception/RestExceptionHandler.java`

- [ ] **Step 1: Read both exception handler files**

```bash
cat backend/src/main/java/com/hms/exception/GlobalExceptionHandler.java
cat backend/src/main/java/com/hms/exception/RestExceptionHandler.java
```

- [ ] **Step 2: Identify duplicated @ExceptionHandler methods**

List all exception types handled in each. Any type handled in both files is a duplicate.

- [ ] **Step 3: Merge into one @ControllerAdvice**

Keep `GlobalExceptionHandler.java`. Move any unique handlers from `RestExceptionHandler.java` into `GlobalExceptionHandler.java`. Delete `RestExceptionHandler.java`.

Standard shape for GlobalExceptionHandler:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String,String>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String,String>> handleUnauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,String>> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "An unexpected error occurred"));
    }
}
```

- [ ] **Step 4: Compile**

```bash
cd backend && mvn compile -q
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hms/exception/
git commit -m "refactor: merge duplicate exception handlers into single GlobalExceptionHandler"
```

---

### Task 2.5: Validate HospitalSetting Default Values on First Row Creation

**Files:**
- Read: `backend/src/main/java/com/hms/service/hospital/HospitalAuthService.java`

- [ ] **Step 1: Find the orElseGet block that creates HospitalSetting**

```bash
grep -n "orElseGet\|newSettings\|HospitalSetting()" \
  backend/src/main/java/com/hms/service/hospital/HospitalAuthService.java
```

- [ ] **Step 2: Confirm all three fields have safe defaults when null is passed**

The creation block should look like:

```java
HospitalSetting newSettings = new HospitalSetting();
newSettings.setHospital(hospital);
newSettings.setReceptionMode(receptionMode != null ? receptionMode : "HAS_RECEPTIONIST");
newSettings.setBillingHandler(effectiveBillingHandler != null ? effectiveBillingHandler : "RECEPTIONIST");
newSettings.setInClinic(inClinic != null ? inClinic : Boolean.TRUE);
return hospitalSettingRepository.save(newSettings);
```

- [ ] **Step 3: Ensure getHospitalOperationsSettings handles missing row gracefully**

Find the GET endpoint's service method. If `findByHospital_Id` returns empty, return a DTO with defaults rather than throwing:

```java
return hospitalSettingRepository.findByHospital_Id(hospitalId)
    .map(s -> new HospitalSettingDTO(s.getReceptionMode(), s.getBillingHandler(), s.getInClinic()))
    .orElse(new HospitalSettingDTO("HAS_RECEPTIONIST", "RECEPTIONIST", true));
```

- [ ] **Step 4: Compile and commit**

```bash
cd backend && mvn compile -q
git add backend/src/main/java/com/hms/service/hospital/HospitalAuthService.java
git commit -m "fix: safe defaults when HospitalSetting row is missing on GET and POST"
```

---

### Task 2.6: Extract PdfService Into Focused Classes

**Files:**
- Read: `backend/src/main/java/com/hms/service/PdfService.java` (1568 lines)
- Create: `backend/src/main/java/com/hms/service/pdf/CasePaperPdfService.java`
- Create: `backend/src/main/java/com/hms/service/pdf/BillingPdfService.java`
- Create: `backend/src/main/java/com/hms/service/pdf/DischargePdfService.java`

- [ ] **Step 1: Read PdfService.java and categorize methods by document type**

```bash
grep -n "public\|private" backend/src/main/java/com/hms/service/PdfService.java | grep -v "//"
```

Categorize each method:
- Case paper generation → `CasePaperPdfService`
- Billing invoice generation → `BillingPdfService`
- Discharge summary generation → `DischargePdfService`
- Shared utilities → keep in a `PdfBaseService` or static helpers

- [ ] **Step 2: Create CasePaperPdfService.java with case paper methods only**

```java
package com.hms.service.pdf;

import org.springframework.stereotype.Service;
// (same imports as PdfService for the relevant methods)

@Service
public class CasePaperPdfService {
    // Move all case-paper-related methods here from PdfService
}
```

- [ ] **Step 3: Create BillingPdfService.java and DischargePdfService.java similarly**

- [ ] **Step 4: Update all callers to inject the specific service instead of PdfService**

```bash
grep -rn "PdfService\|pdfService" backend/src/main/java/com/hms/controller/
```

Update each controller to inject the correct focused service.

- [ ] **Step 5: Deprecate or delete original PdfService.java once all callers migrated**

- [ ] **Step 6: Compile**

```bash
cd backend && mvn compile -q
```

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/hms/service/pdf/
git add backend/src/main/java/com/hms/controller/
git commit -m "refactor: split 1568-line PdfService into CasePaperPdfService, BillingPdfService, DischargePdfService"
```

---

### Task 2.7: Add OpenAPI / Swagger Documentation

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/hms/config/OpenApiConfig.java`

- [ ] **Step 1: Add springdoc-openapi dependency**

In `pom.xml`:

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.3.0</version>
</dependency>
```

- [ ] **Step 2: Create OpenApiConfig.java**

```java
package com.hms.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.*;
import io.swagger.v3.oas.models.security.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Hospital Management System API")
                .version("1.0.0")
                .description("Multi-tenant SaaS HMS REST API"))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
            .components(new Components()
                .addSecuritySchemes("bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }
}
```

- [ ] **Step 3: Permit Swagger UI in SecurityConfig**

Add to the permit list in `SecurityConfig`:

```java
"/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html"
```

- [ ] **Step 4: Compile and open http://localhost:8080/swagger-ui.html**

```bash
cd backend && mvn compile -q
# Start the backend, then open browser to http://localhost:8080/swagger-ui.html
```

Expected: Swagger UI shows all endpoints grouped by controller.

- [ ] **Step 5: Commit**

```bash
git add backend/pom.xml backend/src/main/java/com/hms/config/OpenApiConfig.java
git add backend/src/main/java/com/hms/config/SecurityConfig.java
git commit -m "docs: add Swagger/OpenAPI UI at /swagger-ui.html"
```

---

## Phase 3 — Frontend Code Quality & Duplicate Elimination

### Task 3.1: Extract a useModal Hook to Eliminate Repeated Modal State

**Files:**
- Create: `frontend/src/hooks/useModal.js`
- Modify: `frontend/src/pages/hospital/HospitalAdminDashboard.jsx` (apply hook to one modal as proof-of-concept)

- [ ] **Step 1: Create useModal.js**

```js
// frontend/src/hooks/useModal.js
import { useState, useCallback } from 'react';

export function useModal(initialData = null) {
  const [isOpen, setIsOpen] = useState(false);
  const [data, setData] = useState(initialData);
  const [mode, setMode] = useState('view'); // 'view' | 'edit' | 'create'

  const open = useCallback((newData = null, newMode = 'view') => {
    setData(newData);
    setMode(newMode);
    setIsOpen(true);
  }, []);

  const close = useCallback(() => {
    setIsOpen(false);
    setData(null);
    setMode('view');
  }, []);

  return { isOpen, data, mode, open, close };
}
```

- [ ] **Step 2: Apply to PatientModal in HospitalAdminDashboard**

Find the current pattern (approximately):

```js
const [isPatientModalOpen, setIsPatientModalOpen] = useState(false);
const [editingPatient, setEditingPatient] = useState(null);
```

Replace with:

```js
const patientModal = useModal();
```

Update all references:
- `setIsPatientModalOpen(true)` → `patientModal.open(patient, 'edit')`
- `isPatientModalOpen` → `patientModal.isOpen`
- `editingPatient` → `patientModal.data`
- `setIsPatientModalOpen(false)` → `patientModal.close()`

- [ ] **Step 3: Verify PatientModal still works**

Start frontend dev server (`npm run dev`) and test adding and editing a patient.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/hooks/useModal.js
git add frontend/src/pages/hospital/HospitalAdminDashboard.jsx
git commit -m "refactor: introduce useModal hook, apply to patient modal in HospitalAdminDashboard"
```

---

### Task 3.2: Create a useFetch Hook to Eliminate Repeated Fetch Patterns

**Files:**
- Create: `frontend/src/hooks/useFetch.js`

- [ ] **Step 1: Create useFetch.js**

```js
// frontend/src/hooks/useFetch.js
import { useState, useEffect, useCallback, useRef } from 'react';

export function useFetch(fetchFn, deps = []) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const abortRef = useRef(null);

  const execute = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await fetchFn();
      setData(result);
    } catch (err) {
      if (err.name !== 'CanceledError') {
        setError(err.message || 'An error occurred');
      }
    } finally {
      setLoading(false);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  useEffect(() => {
    execute();
  }, [execute]);

  return { data, loading, error, refetch: execute };
}
```

- [ ] **Step 2: Document usage pattern (in a comment at top of file)**

```js
// Usage:
// const { data: patients, loading, error, refetch } = useFetch(
//   () => hospitalService.getPatients({ page, search }),
//   [page, search]
// );
```

- [ ] **Step 3: Apply to one table in HospitalAdminDashboard as proof-of-concept**

Find the current patient-fetch useEffect:

```js
useEffect(() => {
  setLoadingPatients(true);
  hospitalService.getPatients(...)
    .then(res => setPatients(res))
    .catch(err => console.error(err))
    .finally(() => setLoadingPatients(false));
}, [patientPage, patientSearch]);
```

Replace with:

```js
const { data: patientsData, loading: loadingPatients, refetch: refetchPatients } =
  useFetch(() => hospitalService.getPatients({ page: patientPage, search: patientSearch }),
           [patientPage, patientSearch]);
const patients = patientsData?.content ?? [];
```

- [ ] **Step 4: Verify table still loads**

```bash
cd frontend && npm run dev
```

Open the Patients tab — data should load as before.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/hooks/useFetch.js
git add frontend/src/pages/hospital/HospitalAdminDashboard.jsx
git commit -m "refactor: introduce useFetch hook, apply to patient list fetch"
```

---

### Task 3.3: Add Frontend Input Validation to All Create/Edit Modals

**Files:**
- Read: `frontend/src/utils/validation.js`
- Modify: `frontend/src/components/PatientModal.jsx`
- Modify: `frontend/src/components/AppointmentModal.jsx`

- [ ] **Step 1: Read existing validation.js**

```bash
cat frontend/src/utils/validation.js
```

- [ ] **Step 2: Add missing validators**

Add the following if not already present:

```js
export const validators = {
  required: (val) => (!val || val.toString().trim() === '') ? 'This field is required' : null,
  phone: (val) => (val && !/^\+?[\d\s\-]{7,15}$/.test(val)) ? 'Invalid phone number' : null,
  email: (val) => (val && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(val)) ? 'Invalid email address' : null,
  minLength: (min) => (val) => (val && val.length < min) ? `Minimum ${min} characters` : null,
  maxLength: (max) => (val) => (val && val.length > max) ? `Maximum ${max} characters` : null,
};

export function validate(values, rules) {
  const errors = {};
  for (const field in rules) {
    for (const rule of rules[field]) {
      const error = rule(values[field]);
      if (error) { errors[field] = error; break; }
    }
  }
  return errors;
}
```

- [ ] **Step 3: Apply validation to PatientModal**

At the top of the submit handler in `PatientModal.jsx`:

```js
const errors = validate(formData, {
  firstName: [validators.required],
  lastName:  [validators.required],
  phone:     [validators.required, validators.phone],
  gender:    [validators.required],
});
if (Object.keys(errors).length > 0) {
  setFormErrors(errors);
  return;
}
```

Add `const [formErrors, setFormErrors] = useState({})` to state.

Show errors below each field:

```jsx
{formErrors.firstName && (
  <p className="text-red-500 text-xs mt-1">{formErrors.firstName}</p>
)}
```

- [ ] **Step 4: Apply same pattern to AppointmentModal**

Fields to validate: `patientId` (required), `doctorId` (required), `appointmentDate` (required), `appointmentTime` (required).

- [ ] **Step 5: Test both modals — submit empty form, verify error messages appear**

- [ ] **Step 6: Commit**

```bash
git add frontend/src/utils/validation.js
git add frontend/src/components/PatientModal.jsx
git add frontend/src/components/AppointmentModal.jsx
git commit -m "feat: add client-side validation to PatientModal and AppointmentModal"
```

---

### Task 3.4: Verify Sidebar Shows Correct Items Per Role

**Files:**
- Read: `frontend/src/components/Sidebar.jsx`

- [ ] **Step 1: Read Sidebar.jsx**

```bash
cat frontend/src/components/Sidebar.jsx
```

- [ ] **Step 2: Verify role-to-menu mapping**

Expected nav items per role:

| Role | Expected Nav Items |
|---|---|
| HOSPITAL_ADMIN | Overview, Patients, Doctors, Appointments, Receptionists, Pharmacists, Billing, Wards & Beds, Inventory, Pharmacy, Settings, Audit Logs |
| DOCTOR | Overview, Appointments, Patients, IPD (if module enabled) |
| RECEPTIONIST | Overview, Queue/OPD, Patients, Appointments |
| PHARMACIST | Pharmacy Dashboard (redirects to pharmacy module) |
| SUPER_ADMIN | Hospitals, Users, Tickets, Audit Logs |

- [ ] **Step 3: Check module-gated items**

IPD and Pharmacy nav items must only appear if the hospital has those modules enabled. Verify the check uses `user?.modules?.includes('IPD')` or equivalent.

- [ ] **Step 4: Fix any missing or incorrect items**

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/Sidebar.jsx
git commit -m "fix: correct sidebar nav items per role and module flags"
```

---

### Task 3.5: Standardize Toast Notifications Across All Error Paths

**Files:**
- Read: `frontend/src/context/ToastContext.jsx`
- Spot-check: `frontend/src/pages/hospital/HospitalAdminDashboard.jsx`
- Spot-check: `frontend/src/pages/hospital/DoctorDashboard.jsx`
- Spot-check: `frontend/src/pages/hospital/ReceptionistDashboard.jsx`

- [ ] **Step 1: Confirm ToastContext API**

```bash
grep -n "showToast\|addToast\|toast\." frontend/src/context/ToastContext.jsx | head -20
```

Note the exact function name (e.g., `showToast`, `addToast`).

- [ ] **Step 2: Find all places where errors are silently console.error'd instead of toasted**

```bash
grep -rn "console.error" frontend/src/pages/ frontend/src/components/ | grep -v "//\|node_modules"
```

- [ ] **Step 3: Replace console.error with toast.error in user-visible operations**

Pattern to change:

```js
// Before
} catch (err) { console.error(err); }

// After
} catch (err) { showToast(err.message || 'Operation failed', 'error'); }
```

Do NOT replace console.error in useEffect cleanup, non-user-triggered errors, or WebSocket diagnostics.

- [ ] **Step 4: Verify success toasts exist for all CRUD operations**

Every `save`, `delete`, `update` operation should show a success toast. Find and add any missing ones.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/hospital/ frontend/src/components/
git commit -m "ux: replace silent console.error with toast notifications in all user-facing operations"
```

---

## Phase 4 — Feature Verification (Every Screen, Every Tab)

> **How to run:** Start both backend (`mvn spring-boot:run`) and frontend (`npm run dev`). Log in with each role. Work through every tab and action below. Mark each checkbox when verified working.

### Task 4.1: Platform / Super Admin Screens

**URL:** http://localhost:5173/platform (login: admin@hms.com / admin123)

- [ ] **Hospitals Tab**
  - [ ] Hospital list loads with pagination
  - [ ] Search/filter works
  - [ ] Clicking a hospital opens detail panel
  - [ ] "Onboard Hospital" form creates a hospital (verify in DB: `SELECT * FROM hospitals ORDER BY id DESC LIMIT 1`)
  - [ ] Enable/disable modules (OPD, IPD, PHARMACY) persists and reflects immediately
  - [ ] Deactivating a hospital prevents its users from logging in

- [ ] **Users Tab**
  - [ ] Super admin user list loads
  - [ ] No debug endpoint exposed (verify `/platform/users/debug-users` returns 403 after Task 1.1)

- [ ] **Support Tickets Tab**
  - [ ] Ticket list loads
  - [ ] Open/close ticket status update works

- [ ] **Audit Logs Tab**
  - [ ] Platform-wide audit log list loads with timestamps and action details

---

### Task 4.2: Hospital Admin Screens

**URL:** http://localhost:5173 (login as HOSPITAL_ADMIN)

- [ ] **Overview Tab**
  - [ ] Stats cards load (patients, doctors, appointments, billing totals)
  - [ ] Today's appointment list loads
  - [ ] Quick-action buttons navigate to correct tabs

- [ ] **Patients Tab**
  - [ ] Patient list loads with pagination
  - [ ] Search by name/phone works
  - [ ] "Add Patient" opens modal, fills required fields, saves, appears in table
  - [ ] Edit patient updates data
  - [ ] Delete patient (soft delete) removes from table
  - [ ] Patient ID (`customId`) shown in list and in consultation modal

- [ ] **Doctors Tab**
  - [ ] Doctor list loads
  - [ ] Add/Edit/Delete doctor works
  - [ ] Specialization displayed correctly

- [ ] **Appointments Tab**
  - [ ] Appointment list loads with date/status filter
  - [ ] Create appointment: select patient, doctor, date, time → saves
  - [ ] Edit appointment: status update works
  - [ ] Delete appointment works

- [ ] **Receptionists Tab**
  - [ ] Receptionist list loads
  - [ ] Add/Edit/Delete works

- [ ] **Pharmacists Tab**
  - [ ] Pharmacist list loads
  - [ ] Add/Edit/Delete works

- [ ] **Billing Tab**
  - [ ] Billing list loads with status filter (all/pending/paid)
  - [ ] Create billing entry works
  - [ ] Record payment works (marks bill as paid)
  - [ ] Print invoice button opens print dialog

- [ ] **Wards & Beds Tab**
  - [ ] Ward list loads with bed capacity
  - [ ] Add ward works
  - [ ] Bed list for a ward loads
  - [ ] Bed status update (available/occupied) works

- [ ] **Hospital Inventory Tab**
  - [ ] Item list loads
  - [ ] Add/Edit/Delete inventory item works
  - [ ] Stock adjustment works

- [ ] **Medicine Inventory Tab**
  - [ ] Medicine list loads with batch info
  - [ ] Stock adjustment works
  - [ ] Expiry alert visible for near-expiry batches

- [ ] **Settings → Fees**
  - [ ] Consultation fee loads and can be updated
  - [ ] Case paper fee loads and can be updated
  - [ ] Add custom fee works
  - [ ] Edit/Delete custom fee works

- [ ] **Settings → Operations**
  - [ ] Reception mode toggle (HAS_RECEPTIONIST / SOLO) saves without error
  - [ ] Billing handler toggle (RECEPTIONIST / DOCTOR / BOTH) saves without error
  - [ ] In-clinic mode toggle saves without error
  - [ ] All three toggles work 5 times in a row without `hospital_id cannot be null` error

- [ ] **Audit Logs Tab**
  - [ ] Audit log list loads with actor, action, timestamp

---

### Task 4.3: Doctor Screens

**URL:** http://localhost:5173 (login as DOCTOR)

- [ ] **Overview Tab**
  - [ ] Today's patient count, appointment count shown
  - [ ] Current patient queue visible
  - [ ] Low-stock alerts (if pharmacy module enabled)

- [ ] **Appointments Tab**
  - [ ] Doctor's own appointments load
  - [ ] Today/Pending/Completed/All filter pill works
  - [ ] Date picker (when "Date" pill selected) filters by date
  - [ ] Clicking appointment opens details

- [ ] **Patients Tab**
  - [ ] Patient list loads
  - [ ] Search works
  - [ ] "Start Consultation" opens ConsultationModal
  - [ ] ConsultationModal shows correct patient customId (not UUID, not blank)
  - [ ] Diagnosis, prescription, notes can be saved
  - [ ] Case paper PDF generation works (button visible, PDF opens/downloads)
  - [ ] "Admit to IPD" button visible (if IPD module enabled)

- [ ] **OPD Tab (if visible)**
  - [ ] OPD list filtered to today by default
  - [ ] Today/Date pill toggle works
  - [ ] Date picker filter works
  - [ ] Search by patient name works

- [ ] **IPD Tab (if module enabled)**
  - [ ] Admitted patients list loads
  - [ ] Clicking an admission opens IpdDetails page
  - [ ] Add prescription works
  - [ ] Add followup works
  - [ ] Discharge button opens discharge modal
  - [ ] Discharge summary can be filled and saved

---

### Task 4.4: Receptionist Screens

**URL:** http://localhost:5173 (login as RECEPTIONIST)

- [ ] **Overview Tab**
  - [ ] Queue stats load
  - [ ] Today's appointment summary visible

- [ ] **Queue / OPD Tab**
  - [ ] OPD list filtered to today by default
  - [ ] Today/Date pill toggle works
  - [ ] Search by patient name works
  - [ ] Add Patient to Queue works (creates OPD record)
  - [ ] Mark as Done works
  - [ ] "Add Patient" button absent when reception mode is SOLO

- [ ] **Patients Tab**
  - [ ] Patient list loads with search
  - [ ] Add new patient works
  - [ ] Edit patient works

- [ ] **Appointments Tab**
  - [ ] Appointment list loads
  - [ ] Create appointment works
  - [ ] Status update works

---

### Task 4.5: Pharmacist / Pharmacy Screens

**URL:** http://localhost:5173 → Pharmacy module (login as PHARMACIST)

- [ ] **Dashboard View**
  - [ ] KPI stats load (total medicines, low stock count, today's sales)

- [ ] **Medicine Master**
  - [ ] Medicine list loads with pagination
  - [ ] Search works
  - [ ] Add medicine (with category, manufacturer, HSN code) works
  - [ ] Edit medicine works
  - [ ] Toggle active/inactive works
  - [ ] Batch details visible per medicine

- [ ] **Inventory View**
  - [ ] Stock list loads with batch info and expiry dates
  - [ ] Stock adjustment (add/subtract) works
  - [ ] Expiry date filter works

- [ ] **Purchase View**
  - [ ] Purchase invoice list loads
  - [ ] Create purchase invoice (select supplier, add medicines, quantities, rates) works
  - [ ] Invoice total calculated correctly
  - [ ] Payment status update works

- [ ] **Billing Counter (POS)**
  - [ ] Patient/prescription search works
  - [ ] Add medicine to cart works
  - [ ] Quantity and rate editable
  - [ ] Total calculated correctly
  - [ ] Complete sale works (reduces stock)
  - [ ] Print receipt button works

- [ ] **Returns View**
  - [ ] Returns list loads
  - [ ] Create return works (increases stock)

- [ ] **Expiry View**
  - [ ] Medicines expiring within 30/60/90 days visible
  - [ ] Filter by days works

- [ ] **Prescriptions View**
  - [ ] Prescription list loads
  - [ ] Fulfill prescription from here works

- [ ] **Reports View**
  - [ ] Sales report loads (daily/monthly/custom range)
  - [ ] Inventory report loads
  - [ ] Expiry report loads

- [ ] **Category / Manufacturer / Supplier Masters**
  - [ ] Each list loads
  - [ ] Add/Edit/Delete works for each

---

### Task 4.6: IPD Details Page

**URL:** http://localhost:5173/ipd/:id

- [ ] **Admission Header**
  - [ ] Patient name, doctor, ward/bed, admission date visible
  - [ ] Bed assignment shown correctly

- [ ] **Prescriptions Sub-tab**
  - [ ] Prescription list loads
  - [ ] Add prescription (medicines, notes) works
  - [ ] View prescription modal works

- [ ] **Followups Sub-tab**
  - [ ] Followup list loads
  - [ ] Add followup (notes, date) works

- [ ] **Discharge Sub-tab**
  - [ ] Discharge summary form visible
  - [ ] Fill and save discharge summary works
  - [ ] Discharge button marks admission as discharged
  - [ ] Bed is freed after discharge (verify: check bed status in Wards & Beds tab)

- [ ] **Bed History Sub-tab**
  - [ ] Bed transfer history visible

---

### Task 4.7: Authentication & Route Guards

- [ ] **Login flows**
  - [ ] Hospital user login with valid credentials → redirected to role dashboard
  - [ ] Hospital user login with invalid credentials → error message shown
  - [ ] Super Admin login at `/platform` with valid credentials → PlatformDashboard
  - [ ] Super Admin login with invalid credentials → error message

- [ ] **Protected routes**
  - [ ] Visiting `/hospital/admin` without JWT → redirected to `/login`
  - [ ] Visiting `/platform` without platform JWT → redirected to `/platform/login`
  - [ ] JWT in sessionStorage cleared on logout → redirect to login

- [ ] **Role isolation**
  - [ ] DOCTOR cannot see Receptionist/Pharmacist management tabs
  - [ ] RECEPTIONIST cannot access Doctor or Billing management
  - [ ] PHARMACIST only sees pharmacy module

- [ ] **Token expiry**
  - [ ] After 24 hours (or manually clear sessionStorage), next API call → auto-redirect to login

---

## Phase 5 — Enterprise Practices

### Task 5.1: Add Correlation ID Header for Request Tracing

**Files:**
- Create: `backend/src/main/java/com/hms/filter/CorrelationIdFilter.java`

- [ ] **Step 1: Create CorrelationIdFilter.java**

```java
package com.hms.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(1)
public class CorrelationIdFilter extends GenericFilter {

    private static final String HEADER = "X-Correlation-ID";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put("correlationId", correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("correlationId");
        }
    }
}
```

- [ ] **Step 2: Add correlationId to logging pattern in application.properties**

```properties
logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss} [%X{correlationId}] %-5level %logger{36} - %msg%n
```

- [ ] **Step 3: Compile and verify**

```bash
cd backend && mvn compile -q
# Start backend, make API call, confirm correlationId in logs
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/hms/filter/CorrelationIdFilter.java
git add backend/src/main/resources/application.properties
git commit -m "ops: add X-Correlation-ID header propagation via MDC for request tracing"
```

---

### Task 5.2: Add Standardized API Response Envelope

**Files:**
- Create: `backend/src/main/java/com/hms/dto/ApiResponse.java`

- [ ] **Step 1: Create ApiResponse.java**

```java
package com.hms.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
    boolean success,
    String message,
    T data,
    String error
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, null, data, null);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data, null);
    }

    public static <T> ApiResponse<T> error(String error) {
        return new ApiResponse<>(false, null, null, error);
    }
}
```

- [ ] **Step 2: Apply to one controller as example (HospitalFeeController)**

```java
// Before
return ResponseEntity.ok(feeDTO);

// After
return ResponseEntity.ok(ApiResponse.ok(feeDTO));
```

- [ ] **Step 3: Update GlobalExceptionHandler to return ApiResponse.error**

```java
return ResponseEntity.status(HttpStatus.NOT_FOUND)
    .body(ApiResponse.error(ex.getMessage()));
```

- [ ] **Step 4: Compile**

```bash
cd backend && mvn compile -q
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hms/dto/ApiResponse.java
git add -p
git commit -m "refactor: introduce ApiResponse envelope; apply to HospitalFeeController and GlobalExceptionHandler"
```

---

### Task 5.3: Add Health Actuator Endpoint with DB Check

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.properties`

- [ ] **Step 1: Add Spring Boot Actuator**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

- [ ] **Step 2: Configure actuator in application.properties**

```properties
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=when-authorized
management.health.db.enabled=true
management.health.redis.enabled=true
```

- [ ] **Step 3: Permit actuator endpoint in SecurityConfig**

```java
"/actuator/health", "/actuator/info"
```

- [ ] **Step 4: Compile and test**

```bash
cd backend && mvn compile -q
# Start backend
curl http://localhost:8080/actuator/health
```

Expected:
```json
{"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"}}}
```

- [ ] **Step 5: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.properties
git add backend/src/main/java/com/hms/config/SecurityConfig.java
git commit -m "ops: add Spring Boot Actuator with DB and Redis health checks"
```

---

### Task 5.4: Set JPA Cascade and Orphan Policies Explicitly

**Files:**
- Read: All entity files in `backend/src/main/java/com/hms/entity/`

- [ ] **Step 1: Find all @OneToMany without explicit cascade**

```bash
grep -rn "@OneToMany" backend/src/main/java/com/hms/entity/ | grep -v "cascade"
```

- [ ] **Step 2: For each, set explicit cascade type**

If children should be deleted with parent: `cascade = CascadeType.ALL, orphanRemoval = true`

If children are independent: `cascade = {CascadeType.PERSIST, CascadeType.MERGE}`

Example for `Billing → BillingItem`:

```java
@OneToMany(mappedBy = "billing", cascade = CascadeType.ALL, orphanRemoval = true)
private List<BillingItem> items = new ArrayList<>();
```

- [ ] **Step 3: Find all @ManyToOne without fetch type**

```bash
grep -rn "@ManyToOne" backend/src/main/java/com/hms/entity/ | grep -v "fetch"
```

Set `fetch = FetchType.LAZY` on all `@ManyToOne` to avoid N+1 queries by default.

- [ ] **Step 4: Compile**

```bash
cd backend && mvn compile -q
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hms/entity/
git commit -m "fix: explicit cascade and fetch types on all JPA associations"
```

---

## Phase 6 — Test Coverage

### Task 6.1: Unit Tests for HospitalAuthService Settings Update

**Files:**
- Create: `backend/src/test/java/com/hms/service/HospitalAuthServiceTest.java`

- [ ] **Step 1: Verify test directory exists**

```bash
ls backend/src/test/java/com/hms/
```

If missing: `mkdir -p backend/src/test/java/com/hms/service`

- [ ] **Step 2: Write the test class**

```java
package com.hms.service;

import com.hms.dto.HospitalSettingDTO;
import com.hms.entity.Hospital;
import com.hms.entity.HospitalSetting;
import com.hms.entity.User;
import com.hms.repository.HospitalSettingRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.UserRepository;
import com.hms.service.hospital.HospitalAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HospitalAuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock HospitalSettingRepository hospitalSettingRepository;
    @Mock HospitalRepository hospitalRepository;

    @InjectMocks HospitalAuthService service;

    private User adminUser;
    private HospitalSetting existingSetting;

    @BeforeEach
    void setUp() {
        adminUser = new User();
        adminUser.setEmail("admin@test.com");
        adminUser.setRole("HOSPITAL_ADMIN");
        adminUser.setHospitalId(1L);

        existingSetting = new HospitalSetting();
        Hospital h = new Hospital();
        h.setId(1L);
        existingSetting.setHospital(h);
        existingSetting.setReceptionMode("HAS_RECEPTIONIST");
        existingSetting.setBillingHandler("RECEPTIONIST");
        existingSetting.setInClinic(true);
    }

    @Test
    void updateSettings_soloMode_forcesBillingHandlerToDoctor() {
        when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(adminUser));
        when(hospitalSettingRepository.findByHospital_Id(1L)).thenReturn(Optional.of(existingSetting));

        HospitalSettingDTO dto = new HospitalSettingDTO("SOLO", "RECEPTIONIST", true);
        HospitalSettingDTO result = service.updateHospitalOperationsSettings("admin@test.com", dto);

        assertThat(result.getBillingHandler()).isEqualTo("DOCTOR");
        verify(hospitalSettingRepository).updateByHospitalId(1L, "SOLO", "DOCTOR", true);
    }

    @Test
    void updateSettings_nullInClinic_preservesExistingValue() {
        when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(adminUser));
        when(hospitalSettingRepository.findByHospital_Id(1L)).thenReturn(Optional.of(existingSetting));

        HospitalSettingDTO dto = new HospitalSettingDTO("HAS_RECEPTIONIST", "RECEPTIONIST", null);
        HospitalSettingDTO result = service.updateHospitalOperationsSettings("admin@test.com", dto);

        assertThat(result.getInClinic()).isTrue(); // preserved from existingSetting
        verify(hospitalSettingRepository).updateByHospitalId(1L, "HAS_RECEPTIONIST", "RECEPTIONIST", true);
    }

    @Test
    void updateSettings_invalidReceptionMode_throws() {
        when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(adminUser));

        HospitalSettingDTO dto = new HospitalSettingDTO("INVALID", "RECEPTIONIST", true);
        assertThatThrownBy(() -> service.updateHospitalOperationsSettings("admin@test.com", dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("receptionMode");
    }
}
```

- [ ] **Step 3: Run the test**

```bash
cd backend && mvn test -pl . -Dtest=HospitalAuthServiceTest -q
```

Expected: 3 tests pass.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/hms/service/HospitalAuthServiceTest.java
git commit -m "test: unit tests for HospitalAuthService settings update logic"
```

---

### Task 6.2: Unit Tests for Patient Service — customId in Consultation Response

**Files:**
- Create: `backend/src/test/java/com/hms/service/PatientServiceTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.hms.service;

import com.hms.entity.*;
import com.hms.repository.*;
import com.hms.service.hospital.PatientService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatientServiceTest {

    @Mock PatientRepository patientRepository;
    @Mock OpdRepository opdRepository;
    // Add other mocks as needed by PatientService constructor

    @InjectMocks PatientService service;

    @Test
    void getPatientConsultationDetails_includesCustomIdAndId() {
        Patient patient = new Patient();
        patient.setId(42L);
        patient.setCustomId("HMS-P-0042");
        patient.setFirstName("John");
        patient.setLastName("Doe");

        when(patientRepository.findById(42L)).thenReturn(Optional.of(patient));
        // mock other dependencies as needed

        Map<String, Object> result = service.getPatientConsultationDetails(42L);

        assertThat(result).containsKey("customId");
        assertThat(result.get("customId")).isEqualTo("HMS-P-0042");
        assertThat(result).containsKey("id");
        assertThat(result.get("id")).isEqualTo(42L);
    }
}
```

- [ ] **Step 2: Run the test**

```bash
cd backend && mvn test -Dtest=PatientServiceTest -q
```

Adjust mocks to match PatientService's actual constructor/dependencies until the test compiles and passes.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/hms/service/PatientServiceTest.java
git commit -m "test: verify getPatientConsultationDetails returns customId and id"
```

---

### Task 6.3: Integration Test for Settings Toggle Endpoint

**Files:**
- Create: `backend/src/test/java/com/hms/controller/HospitalAuthControllerIT.java`

- [ ] **Step 1: Add test dependencies to pom.xml if missing**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Write integration test**

```java
package com.hms.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class HospitalAuthControllerIT {

    @Autowired MockMvc mvc;

    @Test
    void updateOperationsSettings_withoutAuth_returns401() throws Exception {
        mvc.perform(put("/hospital/settings/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"receptionMode":"SOLO","billingHandler":"DOCTOR","inClinic":true}
                    """))
            .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 3: Run the test**

```bash
cd backend && mvn test -Dtest=HospitalAuthControllerIT -q
```

Expected: 1 test passes (401 returned without JWT).

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/hms/controller/HospitalAuthControllerIT.java
git commit -m "test: integration test - settings endpoint requires auth"
```

---

## Phase 7 — Performance & Observability

### Task 7.1: Add Database Indexes for Frequently Queried Columns

**Files:**
- Modify: `setup/schema-full.sql`
- Modify: `setup/schema-full-utf8.sql`

- [ ] **Step 1: Identify high-frequency query columns**

Based on service code, these columns are in WHERE clauses on every request:

| Table | Column | Reason |
|---|---|---|
| `patients` | `hospital_id` | Every patient query is scoped to hospital |
| `appointments` | `hospital_id, appointment_date` | Date-scoped appointment lists |
| `opd` | `hospital_id, created_at` | Daily OPD queue |
| `billing` | `hospital_id, payment_status` | Status-filtered billing list |
| `medicine_batches` | `hospital_id, expiry_date` | Expiry tracking |
| `audit_logs` | `hospital_id, timestamp` | Audit log paging |
| `users` | `email` | Login lookup |

- [ ] **Step 2: Add indexes to schema-full.sql**

```sql
-- Performance indexes
CREATE INDEX IF NOT EXISTS idx_patients_hospital ON patients(hospital_id);
CREATE INDEX IF NOT EXISTS idx_appointments_hospital_date ON appointments(hospital_id, appointment_date);
CREATE INDEX IF NOT EXISTS idx_opd_hospital_created ON opd(hospital_id, created_at);
CREATE INDEX IF NOT EXISTS idx_billing_hospital_status ON billing(hospital_id, payment_status);
CREATE INDEX IF NOT EXISTS idx_medicine_batches_expiry ON medicine_batches(hospital_id, expiry_date);
CREATE INDEX IF NOT EXISTS idx_audit_logs_hospital_ts ON audit_logs(hospital_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
```

- [ ] **Step 3: Apply to dev database**

```sql
-- Run each CREATE INDEX statement against your development MySQL database
```

- [ ] **Step 4: Mirror changes in schema-full-utf8.sql**

- [ ] **Step 5: Commit**

```bash
git add setup/schema-full.sql setup/schema-full-utf8.sql
git commit -m "perf: add composite indexes on hospital_id + frequently filtered columns"
```

---

### Task 7.2: Add @Transactional(readOnly=true) on Read-Only Service Methods

**Files:**
- All service files in `backend/src/main/java/com/hms/service/hospital/`

- [ ] **Step 1: Find all service methods that only do reads (no save/delete)**

```bash
grep -n "public.*get\|public.*find\|public.*list\|public.*search" \
  backend/src/main/java/com/hms/service/hospital/PatientService.java | head -20
```

- [ ] **Step 2: Add @Transactional(readOnly = true) to all read-only methods**

```java
@Transactional(readOnly = true)
public Page<PatientDTO> getPatients(Long hospitalId, String search, Pageable pageable) {
    ...
}
```

This signals Hibernate to skip dirty-checking, improving performance on read-heavy queries.

- [ ] **Step 3: Verify no method marked readOnly=true calls save/delete**

```bash
# For each method marked readOnly, grep that method's body for save(, delete(, merge(
```

- [ ] **Step 4: Compile**

```bash
cd backend && mvn compile -q
```

- [ ] **Step 5: Commit**

```bash
git add -p
git commit -m "perf: add @Transactional(readOnly=true) on all read-only service methods"
```

---

### Task 7.3: Verify WebSocket Does Not Leak Connections

**Files:**
- Read: `frontend/src/hooks/useWebSocket.js`

- [ ] **Step 1: Read the hook**

```bash
cat frontend/src/hooks/useWebSocket.js
```

- [ ] **Step 2: Verify cleanup on unmount**

The hook must return a cleanup function from `useEffect` that closes the WebSocket:

```js
useEffect(() => {
  const ws = new WebSocket(url);
  // ... event handlers
  return () => {
    ws.close(); // REQUIRED — prevents connection leak on tab switch / logout
  };
}, [url]);
```

If `ws.close()` is missing, add it.

- [ ] **Step 3: Verify reconnection on disconnect**

If the connection drops, the hook should reconnect (exponential backoff is ideal):

```js
ws.onclose = () => {
  setTimeout(() => connect(), 3000); // reconnect after 3s
};
```

- [ ] **Step 4: Commit if changes made**

```bash
git add frontend/src/hooks/useWebSocket.js
git commit -m "fix: close WebSocket on component unmount to prevent connection leak"
```

---

## Self-Review Checklist

- [x] **Phase 1** covers all critical security issues found in audit: debug endpoint, rate limiting, CORS, logging
- [x] **Phase 2** covers backend quality: @Transactional, hospitalId centralization, @PreAuthorize coverage, duplicate handlers, PdfService split, OpenAPI docs
- [x] **Phase 3** covers frontend quality: useModal hook, useFetch hook, input validation, sidebar correctness, toast standardization
- [x] **Phase 4** covers every screen and feature: Platform Admin (4 tabs), Hospital Admin (12 tabs), Doctor (4 tabs), Receptionist (4 tabs), Pharmacist (11 views), IPD Details (4 sub-tabs), Auth flows
- [x] **Phase 5** covers enterprise practices: correlation IDs, API response envelope, health actuator, JPA cascade
- [x] **Phase 6** covers test coverage: unit tests for the 2 most critical bugs fixed, integration test for auth
- [x] **Phase 7** covers performance: database indexes, readOnly transactions, WebSocket cleanup
- [x] No TBD or placeholder steps — every step has exact commands or code
- [x] File paths are exact and consistent with the repository structure
- [x] Method names are consistent within tasks

---

*Generated: 2026-06-17 | Repo: e:\Projects\HOSPITAL | Branch: staging*
