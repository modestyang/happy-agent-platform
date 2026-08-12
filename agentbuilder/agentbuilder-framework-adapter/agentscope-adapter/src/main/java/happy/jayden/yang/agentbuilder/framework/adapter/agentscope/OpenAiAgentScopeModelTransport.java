package happy.jayden.yang.agentbuilder.framework.adapter.agentscope;

import happy.jayden.yang.agentbuilder.core.runtime.RunRequest;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Bailian adapter through AgentScope's official OpenAI-compatible model implementation. */
final class OpenAiAgentScopeModelTransport implements AgentScopeModelTransport {
  private final OpenAIChatModel delegate;

  OpenAiAgentScopeModelTransport(RunRequest.ModelEndpoint endpoint) {
    delegate =
        endpoint
            .credential()
            .use(
                secret ->
                    OpenAIChatModel.builder()
                        .apiKey(new String(secret))
                        .modelName(endpoint.modelName())
                        .baseUrl(endpoint.baseUri().toString())
                        .stream(true)
                        .build());
  }

  @Override
  public Flux<ChatResponse> stream(
      List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
    return Flux.defer(
        () -> {
          var filter = new VisibleResponseFilter();
          return delegate.stream(messages, tools, options)
              .map(filter::apply)
              .concatWith(Mono.defer(() -> Mono.justOrEmpty(filter.finish())));
        });
  }

  @Override
  public String getModelName() {
    return delegate.getModelName();
  }

  private static final class VisibleResponseFilter {
    private final ThinkingMarkupFilter thinkingMarkup = new ThinkingMarkupFilter();
    private String lastResponseId;
    private boolean finished;

    private ChatResponse apply(ChatResponse response) {
      lastResponseId = response.getId();
      List<ContentBlock> original = response.getContent();
      var visible = new ArrayList<ContentBlock>(original.size());
      boolean changed = false;
      for (ContentBlock block : original) {
        if (!(block instanceof TextBlock textBlock)) {
          visible.add(block);
          continue;
        }
        String originalText = textBlock.getText();
        String visibleText = thinkingMarkup.apply(originalText);
        changed |= !visibleText.equals(originalText);
        if (!visibleText.isEmpty()) {
          visible.add(TextBlock.builder().text(visibleText).build());
        }
      }
      if (response.getFinishReason() != null) {
        String remainingText = thinkingMarkup.finish();
        finished = true;
        if (!remainingText.isEmpty()) {
          visible.add(TextBlock.builder().text(remainingText).build());
          changed = true;
        }
      }
      if (!changed) return response;
      return ChatResponse.builder()
          .id(response.getId())
          .content(visible)
          .usage(response.getUsage())
          .metadata(response.getMetadata())
          .finishReason(response.getFinishReason())
          .build();
    }

    private ChatResponse finish() {
      if (finished) return null;
      finished = true;
      String remainingText = thinkingMarkup.finish();
      if (remainingText.isEmpty()) return null;
      return ChatResponse.builder()
          .id(lastResponseId)
          .content(List.of(TextBlock.builder().text(remainingText).build()))
          .build();
    }
  }

  private static final class ThinkingMarkupFilter {
    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";

    private final StringBuilder pending = new StringBuilder();
    private boolean thinking;
    private boolean trimAfterThinking;
    private boolean hasVisibleContent;

    private String apply(String fragment) {
      if (fragment == null || fragment.isEmpty()) return "";
      pending.append(fragment);
      var visible = new StringBuilder();
      while (!pending.isEmpty()) {
        if (thinking) {
          int closeIndex = indexOfIgnoreCase(pending, CLOSE);
          if (closeIndex < 0) {
            retainPossibleTagPrefix(CLOSE);
            break;
          }
          pending.delete(0, closeIndex + CLOSE.length());
          thinking = false;
          trimAfterThinking = !hasVisibleContent && visible.isEmpty();
          continue;
        }

        if (trimAfterThinking) {
          int firstContent = 0;
          while (firstContent < pending.length()
              && Character.isWhitespace(pending.charAt(firstContent))) {
            firstContent++;
          }
          pending.delete(0, firstContent);
          if (pending.isEmpty()) break;
          trimAfterThinking = false;
        }

        int openIndex = indexOfIgnoreCase(pending, OPEN);
        if (openIndex < 0) {
          int retained = possibleTagPrefixLength(OPEN);
          int emitLength = pending.length() - retained;
          visible.append(pending, 0, emitLength);
          pending.delete(0, emitLength);
          break;
        }
        visible.append(pending, 0, openIndex);
        pending.delete(0, openIndex + OPEN.length());
        thinking = true;
      }
      String visibleText = visible.toString();
      hasVisibleContent |= !visibleText.isEmpty();
      return visibleText;
    }

    private String finish() {
      if (thinking) {
        pending.setLength(0);
        return "";
      }
      String remainingText = pending.toString();
      pending.setLength(0);
      hasVisibleContent |= !remainingText.isEmpty();
      return remainingText;
    }

    private void retainPossibleTagPrefix(String tag) {
      int retained = possibleTagPrefixLength(tag);
      pending.delete(0, pending.length() - retained);
    }

    private int possibleTagPrefixLength(String tag) {
      int limit = Math.min(pending.length(), tag.length() - 1);
      for (int length = limit; length > 0; length--) {
        int offset = pending.length() - length;
        if (matchesIgnoreCase(pending, offset, tag, length)) return length;
      }
      return 0;
    }

    private static int indexOfIgnoreCase(StringBuilder value, String expected) {
      int limit = value.length() - expected.length();
      for (int offset = 0; offset <= limit; offset++) {
        if (matchesIgnoreCase(value, offset, expected, expected.length())) return offset;
      }
      return -1;
    }

    private static boolean matchesIgnoreCase(
        StringBuilder value, int offset, String expected, int length) {
      for (int index = 0; index < length; index++) {
        if (Character.toLowerCase(value.charAt(offset + index)) != expected.charAt(index)) {
          return false;
        }
      }
      return true;
    }
  }
}
