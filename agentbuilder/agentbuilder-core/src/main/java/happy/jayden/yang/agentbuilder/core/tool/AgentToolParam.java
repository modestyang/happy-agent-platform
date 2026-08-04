package happy.jayden.yang.agentbuilder.core.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface AgentToolParam {
  String name() default "";

  String description();

  boolean required() default true;

  String example() default "";

  int minLength() default -1;

  int maxLength() default -1;

  long minimum() default Long.MIN_VALUE;

  long maximum() default Long.MAX_VALUE;

  String pattern() default "";
}
