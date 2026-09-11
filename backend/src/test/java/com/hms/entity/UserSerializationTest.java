package com.hms.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Several endpoints return the User entity directly rather than a DTO (POST /hospital/nurses
 * is one). Before this was fixed the response body carried the BCrypt hash. The password must
 * still bind *inbound*, so it is WRITE_ONLY rather than ignored outright.
 */
class UserSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void passwordIsNeverSerialisedIntoAResponse() throws Exception {
        User user = new User();
        user.setEmail("nurse@example.com");
        user.setPassword("$2a$10$abcdefghijklmnopqrstuv");

        String json = mapper.writeValueAsString(user);

        assertThat(json).doesNotContain("password");
        assertThat(json).doesNotContain("$2a$");
        assertThat(json).contains("nurse@example.com");
    }

    @Test
    void passwordIsStillReadFromAnInboundRequestBody() throws Exception {
        User user = mapper.readValue(
                "{\"email\":\"nurse@example.com\",\"password\":\"secret\"}", User.class);

        assertThat(user.getPassword()).isEqualTo("secret");
    }
}
