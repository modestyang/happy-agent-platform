package happy.jayden.yang.agentbuilder;

import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchPort.Conflict;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchPort.NotFound;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchPort.ValidationFailure;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = AdminWorkbenchController.class)
public class AdminWorkbenchProblemHandler {

  @ExceptionHandler(NotFound.class)
  ProblemDetail notFound(NotFound exception) {
    return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage());
  }

  @ExceptionHandler(Conflict.class)
  ProblemDetail conflict(Conflict exception) {
    return problem(HttpStatus.CONFLICT, "REVISION_CONFLICT", exception.getMessage());
  }

  @ExceptionHandler(ValidationFailure.class)
  ProblemDetail validation(ValidationFailure exception) {
    var detail =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", exception.getMessage());
    detail.setProperty("errors", exception.validation().errors());
    detail.setProperty("warnings", exception.validation().warnings());
    return detail;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail invalid(IllegalArgumentException exception) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
  }

  private static ProblemDetail problem(HttpStatus status, String code, String message) {
    var detail = ProblemDetail.forStatusAndDetail(status, message);
    detail.setTitle(status.getReasonPhrase());
    detail.setType(URI.create("urn:happy-agent:problem:" + code.toLowerCase()));
    detail.setProperty("code", code);
    return detail;
  }
}
