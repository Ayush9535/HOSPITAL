package com.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A human/organisation name: letters of any language, spaces, and the punctuation . ' - only
 * (e.g. "Dr. O'Brien", "Jean-Paul"). No digits, no arbitrary special characters (@ # $ % …) and
 * no emoji — those belong to free-text or structured-code fields, not names.
 *
 * Presence is NOT enforced here (empty is allowed) so it can be used on optional name fields;
 * pair it with @NotBlank where the name is required.
 */
@Documented
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Size(max = 100, message = "Name is too long")
@Pattern(regexp = "^[\\p{L} .'\\-]*$",
        message = "Name may only contain letters, spaces and the characters . ' -")
public @interface PersonName {
    String message() default "Invalid name";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
