# Nursing Management — Phase E Design (Nurse Incharge Dashboard)

**Date:** 2026-07-09
**Status:** Approved for planning
**Depends on:** A (guard, ward↔incharge), B (schedules), C (bed statuses), D (attendance summary).
**Gate:** `NURSING`; `/hospital/**`; tenant-scoped.

## Scope
A **Dashboard** overview tab for the Nurse Incharge, aggregated across all their wards (admin sees all hospital wards), with tiles and quick actions. One aggregation endpoint feeds it.

## Tiles (per the original spec)
- **Patients:** Total Patients (ADMITTED in my wards), New Admissions (admitted today), Discharges Today (DISCHARGED with `discharge_datetime` today).
- **Nurses:** Total Nurses (active, non-incharge, in my wards), Present, Absent, On Leave (from today's attendance across my wards).
- **Beds:** Total, Available, Occupied, Cleaning Required, Under Maintenance (across my wards).

## Quick Actions
Buttons that switch the incharge dashboard's active tab: **Create Nurse** → My Nurses, **Mark Attendance** → Attendance, **Manage Beds** → Beds, **View Schedule** → Schedule. **View Calendar** is shown disabled/"coming soon" (Phase G). ("Fill Forms" from the spec has no incharge-level flow yet — the incharge opens a patient under My Ward Patients; omitted as a top-level quick action.)

## Backend
`GET /hospital/nurse-incharge/dashboard` (`hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')`) → `NurseInchargeDashboardDTO`:
```
patients: { total, newAdmissionsToday, dischargesToday }
nurses:   { total, present, absent, onLeave }
beds:     { total, available, occupied, cleaningRequired, underMaintenance }
```
Implemented in `NurseWorkspaceService.getInchargeDashboard()`:
- `wardIds = nurseInchargeGuard.myWardIds()` (ward-scoped; admin → all).
- Patients: filter `ipdAdmissionRepository.findByHospitalIdAndStatusIn(hospitalId, [ADMITTED, DISCHARGED])` to `wardIds`; count ADMITTED (total), admitted-today (`admissionDatetime` on today), discharged-today (`status=DISCHARGED` & `dischargeDatetime` today).
- Nurses: for each ward, `nurseProfileRepository.findByWardIdAndIsInchargeFalseAndIsActiveTrue(wardId)` → total; Present/Absent/On-Leave by summing each ward's attendance for today (reuse `NurseAttendanceService.summary` counts: present, absent, leave).
- Beds: for each ward, `bedRepository.findByWardIdAndHospitalId` → count by `BedStatus`.
No new table. DTO only.

## Frontend
- New **Dashboard** tab (made the default landing) in `NurseInchargeDashboard.jsx`, rendering `pages/hospital/nurse-incharge/InchargeOverview.jsx`: three tile groups (Patients / Nurses / Beds) + a Quick Actions row wired to `setActiveTab(...)` (Calendar disabled). Uses `nurseService.getInchargeDashboard()`.

## Testing & verification
- JUnit `NurseWorkspaceService` dashboard test (or extend existing): counts patients/beds/nurses across wards with a mocked guard `myWardIds`. Build: `mvn -o test` + `npx vite build`.

## Milestone
- **E1** dashboard DTO + `getInchargeDashboard` + endpoint + `InchargeOverview` tab (default). Single milestone.

Standing constraints: commit at completion; `/hospital/**`; NURSING-gated.
