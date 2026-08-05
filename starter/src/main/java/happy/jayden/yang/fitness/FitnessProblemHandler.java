package happy.jayden.yang.fitness;

import happy.jayden.yang.fitness.service.FitnessExceptions.DependencyNotConfiguredException;
import happy.jayden.yang.fitness.service.FitnessExceptions.DependencyUnavailableException;
import happy.jayden.yang.fitness.service.FitnessExceptions.InvalidRequestException;
import happy.jayden.yang.fitness.service.FitnessExceptions.NotFoundException;
import happy.jayden.yang.fitness.service.FitnessExceptions.UnauthorizedException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class FitnessProblemHandler {

  @ExceptionHandler(UnauthorizedException.class)
  ProblemDetail unauthorized(UnauthorizedException exception) {
    return problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", exception.getMessage());
  }

  @ExceptionHandler(InvalidRequestException.class)
  ProblemDetail invalidRequest(InvalidRequestException exception) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
  }

  @ExceptionHandler(NotFoundException.class)
  ProblemDetail notFound(NotFoundException exception) {
    return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage());
  }

  @ExceptionHandler(DependencyNotConfiguredException.class)
  ProblemDetail dependencyNotConfigured(DependencyNotConfiguredException exception) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_NOT_CONFIGURED", exception.getMessage());
  }

  @ExceptionHandler(DependencyUnavailableException.class)
  ProblemDetail dependencyUnavailable(DependencyUnavailableException exception) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", exception.getMessage());
  }

  private static ProblemDetail problem(HttpStatus status, String code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create("urn:happy-agent:problem:" + code.toLowerCase()));
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    return problem;
  }
}
