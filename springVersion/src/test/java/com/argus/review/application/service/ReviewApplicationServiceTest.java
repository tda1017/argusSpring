package com.argus.review.application.service;

import com.argus.review.domain.agent.LogicAgent;
import com.argus.review.domain.agent.SecurityAgent;
import com.argus.review.domain.agent.StyleAgent;
import com.argus.review.domain.rag.RetrievalService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 审查应用服务单元测试。
 */
class ReviewApplicationServiceTest {

    private final StubSecurityAgent securityAgent = new StubSecurityAgent();
    private final StubStyleAgent styleAgent = new StubStyleAgent();
    private final StubLogicAgent logicAgent = new StubLogicAgent();
    private final StubRetrievalService retrievalService = new StubRetrievalService();

    private final ReviewApplicationService service = new ReviewApplicationService(
        securityAgent, styleAgent, logicAgent, retrievalService
    );

    @AfterEach
    void tearDown() {
        service.shutdownExecutor();
    }

    /**
     * 验证三个 Agent 的流式结果会被加标签后统一合并输出。
     */
    @Test
    void shouldMergeRealTokenStreamsWithLabels() {
        securityAgent.streamTokens = List.of("s1", "s2");
        styleAgent.streamTokens = List.of("c1");
        logicAgent.streamTokens = List.of("l1");

        StepVerifier.create(service.reviewStream("diff"))
            .recordWith(java.util.ArrayList::new)
            .expectNextCount(4)
            .consumeRecordedWith(tokens -> {
                assertEquals(4, tokens.size());
                org.junit.jupiter.api.Assertions.assertTrue(tokens.stream().allMatch(token ->
                    token.startsWith("[SECURITY] ")
                        || token.startsWith("[STYLE] ")
                        || token.startsWith("[LOGIC] ")
                ));
            })
            .verifyComplete();
    }

    /**
     * 验证同步审查时三个 Agent 的结果会被完整聚合。
     */
    @Test
    void shouldReviewInParallelAndAggregateResult() {
        securityAgent.reviewResult = "sec";
        styleAgent.reviewResult = "style";
        logicAgent.reviewResult = "logic";

        var result = service.review("diff");

        assertEquals("sec", result.securityReport());
        assertEquals("style", result.styleReport());
        assertEquals("logic", result.logicReport());
    }

    /**
     * 固定返回检索上下文，避免单元测试依赖真实向量库。
     */
    private static final class StubRetrievalService extends RetrievalService {

        private StubRetrievalService() {
            super(null, null);
        }

        @Override
        public String retrieveRelevantContext(String query) {
            return "ctx";
        }
    }

    /**
     * 三个 Agent 桩对象的公共行为。
     */
    private abstract static class AbstractStubAgent {

        String reviewResult = "";
        List<String> streamTokens = List.of();

        protected String review(String codeDiff, String context) {
            return reviewResult;
        }

        protected TokenStream reviewStream(String codeDiff, String context) {
            return new StubTokenStream(streamTokens);
        }
    }

    private static final class StubSecurityAgent extends AbstractStubAgent implements SecurityAgent {
        @Override
        public String review(String codeDiff, String context) {
            return super.review(codeDiff, context);
        }

        @Override
        public TokenStream reviewStream(String codeDiff, String context) {
            return super.reviewStream(codeDiff, context);
        }
    }

    private static final class StubStyleAgent extends AbstractStubAgent implements StyleAgent {
        @Override
        public String review(String codeDiff, String context) {
            return super.review(codeDiff, context);
        }

        @Override
        public TokenStream reviewStream(String codeDiff, String context) {
            return super.reviewStream(codeDiff, context);
        }
    }

    private static final class StubLogicAgent extends AbstractStubAgent implements LogicAgent {
        @Override
        public String review(String codeDiff, String context) {
            return super.review(codeDiff, context);
        }

        @Override
        public TokenStream reviewStream(String codeDiff, String context) {
            return super.reviewStream(codeDiff, context);
        }
    }

    private static final class StubTokenStream implements TokenStream {

        private final List<String> tokens;
        private java.util.function.Consumer<String> tokenConsumer = token -> {};
        private java.util.function.Consumer<Response<AiMessage>> completionConsumer = response -> {};
        private java.util.function.Consumer<Throwable> errorConsumer = error -> {};

        private StubTokenStream(List<String> tokens) {
            this.tokens = tokens;
        }

        @Override
        public TokenStream onNext(java.util.function.Consumer<String> consumer) {
            this.tokenConsumer = consumer;
            return this;
        }

        @Override
        public TokenStream onRetrieved(java.util.function.Consumer<java.util.List<dev.langchain4j.rag.content.Content>> consumer) {
            return this;
        }

        @Override
        public TokenStream onToolExecuted(java.util.function.Consumer<dev.langchain4j.service.tool.ToolExecution> consumer) {
            return this;
        }

        @Override
        public TokenStream onComplete(java.util.function.Consumer<Response<AiMessage>> consumer) {
            this.completionConsumer = consumer;
            return this;
        }

        @Override
        public TokenStream onError(java.util.function.Consumer<Throwable> consumer) {
            this.errorConsumer = consumer;
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            return this;
        }

        @Override
        public void start() {
            try {
                // 模拟真实流式回调，一次吐一个 token。
                for (String token : tokens) {
                    tokenConsumer.accept(token);
                }
                completionConsumer.accept(Response.from(AiMessage.from(String.join("", tokens))));
            } catch (Throwable throwable) {
                errorConsumer.accept(throwable);
            }
        }
    }

}
