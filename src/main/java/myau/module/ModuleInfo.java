package myau.module;

import myau.enums.ModuleCategory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to automatically categorize modules in ClickGui
 * Usage: @ModuleInfo(category = ModuleCategory.COMBAT)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ModuleInfo {
    /**
     * The category this module belongs to
     */
    ModuleCategory category();

    /**
     * Optional: Sort priority within category (lower = higher in list)
     */
    int priority() default 1000;
}
