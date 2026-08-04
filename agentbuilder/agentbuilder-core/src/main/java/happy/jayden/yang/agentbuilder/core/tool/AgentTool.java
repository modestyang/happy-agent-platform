package happy.jayden.yang.agentbuilder.core.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentTool {
  String key();

  int version();

  String runtimeName();

  String displayName();

  String description();

  String whenToUse();

  String whenNotToUse();

  String applicationKey();

  String group();

  String[] tags() default {};

  String outputDescription() default "";

  ToolSideEffect sideEffect() default ToolSideEffect.NONE;

  boolean idempotent() default false;

  ToolRiskLevel risk() default ToolRiskLevel.LOW;

  String[] requiredScopes() default {};

  int defaultTimeoutMs() default 5_000;

  int maxTimeoutMs() default 30_000;

  int defaultMaxCallsPerRun() default 5;

  boolean supportsStreaming() default false;

  boolean returnDirect() default false;

  ToolLifecycleStatus status() default ToolLifecycleStatus.AVAILABLE;

  String replacementKey() default "";

  int replacementVersion() default 0;
}
