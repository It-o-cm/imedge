package com.intermarche.edge.security;

import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.setting.Feature;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares which back-office feature a handler serves and which option it
 * exercises (BO-01-04-01, BO-01-04-02): the {@link FeatureAccessFilter} asks
 * the administered profiles before the handler runs.
 *
 * <p>A saving gesture that both creates and modifies declares
 * {@link CrudOption#CREATE} with the field naming the record: when the posted
 * field is filled the gesture modifies, so it needs
 * {@link CrudOption#UPDATE}. When that field is a business code typed for a
 * new record as well, {@link #entity()} names the entity to look the code up
 * in: the gesture modifies only when the record already exists.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface FeatureAccess {

    /**
     * The feature the handler serves.
     *
     * @return the feature
     */
    Feature value();

    /**
     * The option the handler exercises.
     *
     * @return the option, VIEW by default
     */
    CrudOption option() default CrudOption.VIEW;

    /**
     * The posted field naming the record a CREATE gesture would modify.
     *
     * @return the field name, empty when the gesture always creates
     */
    String updateKey() default "";

    /**
     * The entity the {@link #updateKey()} value is looked up in; void when a
     * filled field alone means a modification.
     *
     * @return the entity class, void by default
     */
    Class<?> entity() default void.class;

    /**
     * The entity property matched against the posted value.
     *
     * @return the property, empty for the field name itself
     */
    String entityKey() default "";

    /**
     * Whether the handler checks the right itself and answers its own refusal
     * (profiles, users, financial states): the filter then never refuses, it
     * only adds the validation and the log.
     *
     * @return true for a self-guarded handler
     */
    boolean selfGuarded() default false;
}
