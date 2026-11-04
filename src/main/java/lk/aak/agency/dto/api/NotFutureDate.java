package lk.aak.agency.dto.api;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import lk.aak.agency.util.BusinessTime;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;

/** The date must not be after today in Sri Lanka (the server itself runs on UTC, so not PastOrPresent). */
@Documented
@Constraint(validatedBy = NotFutureDate.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface NotFutureDate {

    String message() default "The date cannot be in the future.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<NotFutureDate, LocalDate> {

        @Override
        public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
            return value == null || !value.isAfter(BusinessTime.today());
        }
    }
}
