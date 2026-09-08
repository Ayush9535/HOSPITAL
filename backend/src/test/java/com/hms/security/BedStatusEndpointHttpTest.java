package com.hms.security;

import com.hms.entity.*;
import com.hms.repository.*;
import com.hms.support.NursingHttpFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BedStatusEndpointHttpTest {
    @LocalServerPort int port;
    @Autowired JwtUtil jwt;
    @Autowired HospitalRepository hospitals; @Autowired HospitalSettingRepository settings; @Autowired UserRepository users;
    @Autowired NurseProfileRepository nurses; @Autowired WardRepository wards; @Autowired BedRepository beds;
    @Autowired PatientRepository patients; @Autowired DoctorRepository doctors; @Autowired IpdAdmissionRepository admissions;
    @Autowired MedicalRecordRepository records; @Autowired PrescriptionRepository prescriptions; @Autowired IpdBedHistoryRepository histories;
    @Autowired BedStatusAuditRepository audits;
    NursingHttpFixture f; Hospital a,b; Ward wa,wb; Bed bedA, bedB; NurseProfile inchargeA, inchargeB;

    @BeforeEach void seed() {
        f = new NursingHttpFixture(jwt,hospitals,settings,users,nurses,wards,beds,patients,doctors,admissions,records,prescriptions,histories);
        a=f.tenant("bed-a"); b=f.tenant("bed-b"); wa=f.ward(a,"a"); wb=f.ward(a,"b");
        inchargeA=f.incharge(a,wa,"incharge-a"); inchargeB=f.incharge(a,wb,"incharge-b");
        bedA=f.availableBed(a,wa,"a"); bedA.setStatus(BedStatus.MAINTENANCE); bedA=beds.save(bedA);
        Ward foreign=f.ward(b,"foreign"); bedB=f.availableBed(b,foreign,"foreign"); bedB.setStatus(BedStatus.MAINTENANCE); bedB=beds.save(bedB);
    }
    @Test void canonicalEndpointIsAuditedAndRoleScoped() {
        User admin=f.user(a,"HOSPITAL_ADMIN","admin");
        assertThat(call(HttpMethod.POST,"/hospital/beds/"+bedA.getBedId()+"/available",f.tokenFor(admin)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(beds.findById(bedA.getBedId()).orElseThrow().getStatus()).isEqualTo(BedStatus.AVAILABLE);
        var rows=audits.findByBedIdOrderByChangedAtDesc(bedA.getBedId()); assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getPreviousStatus()).isEqualTo(BedStatus.MAINTENANCE); assertThat(rows.get(0).getNewStatus()).isEqualTo(BedStatus.AVAILABLE);
        assertThat(rows.get(0).getHospitalId()).isEqualTo(a.getId()); assertThat(rows.get(0).getChangedByUserId()).isEqualTo(admin.getId());
    }
    @Test void rejectsLegacyUnauthorizedForeignAndIllegalManualTransitions() {
        // Spring's unmatched route contract here is 404 rather than 405; either way the
        // removed legacy mutation cannot reach a controller or alter the bed.
        assertThat(call(HttpMethod.PUT,"/hospital/beds/"+bedA.getBedId(),f.tokenFor(f.user(a,"HOSPITAL_ADMIN","admin"))).getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.POST,"/hospital/beds/"+bedA.getBedId()+"/available",f.tokenFor(inchargeB)).getStatusCode().value()).isEqualTo(403);
        for(String role: new String[]{"PHARMACIST","DOCTOR","RECEPTIONIST"}) assertThat(call(HttpMethod.POST,"/hospital/beds/"+bedA.getBedId()+"/available",f.tokenFor(f.user(a,role,role))).getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.POST,"/hospital/beds/"+bedB.getBedId()+"/available",f.tokenFor(inchargeA)).getStatusCode().value()).isIn(403,404);
        bedA.setStatus(BedStatus.OCCUPIED); beds.save(bedA); long before=audits.findByBedIdOrderByChangedAtDesc(bedA.getBedId()).size();
        assertThat(call(HttpMethod.POST,"/hospital/beds/"+bedA.getBedId()+"/available",f.tokenFor(inchargeA)).getStatusCode().is4xxClientError()).isTrue();
        assertThat(beds.findById(bedA.getBedId()).orElseThrow().getStatus()).isEqualTo(BedStatus.OCCUPIED);
        assertThat(audits.findByBedIdOrderByChangedAtDesc(bedA.getBedId())).hasSize((int) before);
    }
    private ResponseEntity<String> call(HttpMethod method,String path,String token) { try { var r=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).header("Content-Type","application/json").method(method.name(),java.net.http.HttpRequest.BodyPublishers.ofString("{} ")).build(); var x=java.net.http.HttpClient.newHttpClient().send(r,java.net.http.HttpResponse.BodyHandlers.ofString()); return ResponseEntity.status(x.statusCode()).body(x.body()); } catch(Exception e){throw new IllegalStateException(e);} }
}
