package happy.jayden.yang.agentbuilder.service.workbench;

import static happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.*;

import java.util.Optional;
import java.util.UUID;

public interface AdminWorkbenchPort {
  WorkbenchSnapshot snapshot();

  Optional<AgentDraftView> findDraft(String agentKey);

  AgentDraftView updateDraft(String agentKey, DraftUpdate update, long expectedRevision);

  ProviderView saveCredential(String providerKey, char[] credential);

  PublicationView publish(AgentDraftView draft);

  Optional<RunView> run(UUID runId);

  final class NotFound extends RuntimeException {
    public NotFound(String message) {
      super(message);
    }
  }

  final class Conflict extends RuntimeException {
    public Conflict(String message) {
      super(message);
    }
  }

  final class ValidationFailure extends RuntimeException {
    private final ValidationView validation;

    public ValidationFailure(ValidationView validation) {
      super("agent draft validation failed");
      this.validation = validation;
    }

    public ValidationView validation() {
      return validation;
    }
  }
}
