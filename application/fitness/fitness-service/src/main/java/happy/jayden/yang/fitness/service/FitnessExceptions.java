package happy.jayden.yang.fitness.service;

public final class FitnessExceptions {

  private FitnessExceptions() {}

  public static final class UnauthorizedException extends RuntimeException {
    public UnauthorizedException() {
      super("Authentication required");
    }
  }

  public static final class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
      super(message);
    }
  }

  public static final class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
      super(message);
    }
  }
  public static final class ConflictException extends RuntimeException { public ConflictException(String message) { super(message); } }

  public static final class DependencyNotConfiguredException extends RuntimeException {
    public DependencyNotConfiguredException() {
      super("请在 Agent 工作台配置模型 Provider");
    }
  }

  public static final class DependencyUnavailableException extends RuntimeException {
    public DependencyUnavailableException() {
      super("已配置 Provider，但当前体验服务尚未连接 Agent 运行时");
    }

    public DependencyUnavailableException(String detail) {
      super("已配置 Provider，但当前体验服务尚未连接 Agent 运行时。" + detail);
    }

    public DependencyUnavailableException(String detail, Throwable cause) {
      super("已配置 Provider，但当前体验服务尚未连接 Agent 运行时。" + detail, cause);
    }

    public DependencyUnavailableException(Throwable cause) {
      super("已配置 Provider，但当前体验服务尚未连接 Agent 运行时", cause);
    }
  }
}
