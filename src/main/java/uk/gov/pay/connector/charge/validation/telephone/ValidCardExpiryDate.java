package uk.gov.pay.connector.charge.validation.telephone;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({FIELD})
@Retention(RUNTIME)
@Constraint(validatedBy = CardExpiryValidator.class)
@Documented
public @interface ValidCardExpiryDate {

    String message() default "Must be MM/YY";

    Class<?>[] groups() default{};

    Class<? extends Payload>[] payload() default{};
    
}
