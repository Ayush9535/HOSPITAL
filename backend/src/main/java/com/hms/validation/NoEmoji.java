package com.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rejects emoji and pictographic symbols in a String field. Applied broadly across text inputs:
 * real names, addresses, notes and clinical free-text never legitimately contain emoji, and
 * blocking them keeps stored data clean and avoids downstream rendering/PDF issues.
 *
 * This is the "least restrictive" text rule — it still allows letters of any language, digits and
 * ordinary punctuation/special characters. Structured fields (email, phone, codes) layer stricter
 * patterns on top.
 */
@Documented
@Constraint(validatedBy = NoEmojiValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoEmoji {
    String message() default "Emojis and pictographic symbols are not allowed";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
