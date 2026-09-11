package com.hms.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hms.dto.ApiErrorResponse;
import com.hms.dto.DuplicatePatientMatch;
import com.hms.entity.Patient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire contract for a duplicate-phone registration.
 *
 * <p>A 409 like any other conflict, but the only one that carries a structured payload: the
 * existing patients it collided with, so reception can answer the question the server cannot —
 * is this the same person, or a different person on the same number?
 *
 * <p>The other half of this test is back-compatibility. {@code conflicts} was added to a record
 * that ~136 frontend call sites already read as {@code data.error}; the record is NON_NULL, so
 * every other error must serialise to exactly the bytes it did before the field existed.
 */
class DuplicatePhoneConflictMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ObjectMapper mapper = new ObjectMapper();

    private DuplicatePatientMatch match() {
        return new DuplicatePatientMatch(101L, "pub-101", "PAT101", "Rahul Patil", 38);
    }

    @Test
    void aDuplicatePhoneIs409() {
        ResponseEntity<ApiErrorResponse> res = handler.handleDuplicatePhone(
                new DuplicatePhoneConflictException("already registered", List.of(match())));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void theBodyCarriesEveryMatch() {
        DuplicatePatientMatch child = new DuplicatePatientMatch(145L, "pub-145", "PAT145", "Aarav Patil", 8);

        ApiErrorResponse body = handler.handleDuplicatePhone(
                new DuplicatePhoneConflictException("already registered", List.of(match(), child)))
                .getBody();

        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(body.success()).isFalse();
        assertThat(body.error()).isEqualTo("already registered");
        assertThat(body.conflicts()).extracting(DuplicatePatientMatch::customId)
                .containsExactly("PAT101", "PAT145");
    }

    /**
     * A conflict body is shown to staff. It carries what identifies a person to a human, and
     * stops there: no phone (the caller just typed it, and echoing it puts a contact number in
     * error logs), no full date of birth, no address, email or medical history.
     */
    @Test
    void theSerialisedMatchExposesOnlyTheApprovedFields() throws Exception {
        String json = mapper.writeValueAsString(match());

        assertThat(mapper.readTree(json).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "publicId", "customId", "name", "age");
        assertThat(json).doesNotContain("phone").doesNotContain("dateOfBirth")
                .doesNotContain("address").doesNotContain("email").doesNotContain("medicalHistory");
    }

    @Test
    void everyOtherErrorBodyIsUnchangedByTheNewField() throws Exception {
        ApiErrorResponse body = handler.handleConflict(new ConflictException("boom")).getBody();

        String json = mapper.writeValueAsString(body);
        assertThat(json).doesNotContain("conflicts");
        assertThat(mapper.readTree(json).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("success", "code", "error", "message");
    }

    /**
     * The acknowledgement is server-controlled. A client that could set these through the patient
     * body would turn the shared-phone workflow into an opt-out, so Jackson must refuse to bind
     * them however they are spelled on the request.
     */
    @Test
    void acknowledgementFieldsCannotBeBoundFromTheRequestBody() throws Exception {
        String forged = """
                {"name":"Aarav Patil","phone":"9876500001","gender":"MALE",
                 "dateOfBirth":"2017-09-21",
                 "duplicatePhoneAckFor":"9876500001",
                 "duplicatePhoneAckBy":"attacker@example.test",
                 "duplicatePhoneAckAt":"2020-01-01T00:00:00"}
                """;

        Patient bound = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .readValue(forged, Patient.class);

        assertThat(bound.getName()).isEqualTo("Aarav Patil");
        assertThat(bound.getDuplicatePhoneAckFor()).isNull();
        assertThat(bound.getDuplicatePhoneAckBy()).isNull();
        assertThat(bound.getDuplicatePhoneAckAt()).isNull();
    }

    /** Read-only, not invisible: a patient response may still show what was acknowledged. */
    @Test
    void acknowledgementFieldsAreStillReadableInResponses() throws Exception {
        Patient p = new Patient();
        p.setDuplicatePhoneAckFor("9876500001");

        String json = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .writeValueAsString(p);

        assertThat(json).contains("duplicatePhoneAckFor");
    }
}
