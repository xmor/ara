package io.ara.runtime.workflow;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.media.MediaRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The attachments of a workflow's request: they reach the agent-shaped nodes that declared they read them and no
 * other, a request whose attachments nobody reads is refused before any node starts — an attachment is never dropped
 * without a word — and the journal says how many each occurrence received.
 */
class WorkflowRequestMediaTest {

    private static final MediaRef PAGE = new MediaRef("m1", "image/png", "pagina-1.png", 3, null);

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void aNodeThatDeclaredItReceivesTheAttachments_theOthersDoNot() {
        Recorder read = new Recorder("read-out");
        Recorder summarize = new Recorder("sum-out");

        WorkflowResult result = Workflow.of()
                .agent("read", read).readsRequestMedia("read")
                .agent("summarize", summarize)
                .edge("read", "summarize")
                .build()
                .run("riassumi", List.of(PAGE), pool);

        assertTrue(result.ok(), result.failureReason());
        assertEquals(List.of(PAGE), read.media().get(0), "the node that declared it reads the document");
        assertEquals(List.of(), summarize.media().get(0), "the next node works on what the first one wrote");
    }

    @Test
    void theEngineHasNoRuleOfItsOwn_anEntryNodeThatDidNotDeclareItGetsNothing() {
        Recorder entry = new Recorder("out");
        Recorder reader = new Recorder("out2");

        WorkflowResult result = Workflow.of()
                .agent("entry", entry)
                .agent("reader", reader).readsRequestMedia("reader")   // a node further down may be the one that reads
                .edge("entry", "reader")
                .build()
                .run("x", List.of(PAGE), pool);

        assertTrue(result.ok(), result.failureReason());
        assertEquals(List.of(), entry.media().get(0));
        assertEquals(List.of(PAGE), reader.media().get(0));
    }

    @Test
    void attachmentsNoNodeReads_refuseTheRequestBeforeAnyNodeStarts() {
        Recorder a = new Recorder("a");

        WorkflowResult result = Workflow.of().agent("a", a).build().run("x", List.of(PAGE, PAGE), pool);

        assertFalse(result.ok());
        assertTrue(result.failureReason().contains("2 attachments") && result.failureReason().contains("no node"),
                result.failureReason());
        assertTrue(result.journal().isEmpty(), "nothing ran");
        assertTrue(a.media().isEmpty());
    }

    @Test
    void withNoAttachments_aWorkflowBehavesAsBefore() {
        Recorder a = new Recorder("A");

        WorkflowResult withMethod = Workflow.of().agent("a", a).readsRequestMedia("a").build().run("go", List.of(), pool);
        WorkflowResult plain = Workflow.of().agent("a", new Recorder("A")).build().run("go", pool);

        assertTrue(withMethod.ok() && plain.ok());
        assertEquals(List.of(), a.media().get(0));
        assertEquals(plain.journal().size(), withMethod.journal().size());
    }

    @Test
    void anOpaqueNodeCannotDeclareItReadsAttachments() {
        var builder = Workflow.of().node("plain", in -> in);

        var e = assertThrows(IllegalArgumentException.class, () -> builder.readsRequestMedia("plain"));
        assertTrue(e.getMessage().contains("not agent-shaped"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> builder.readsRequestMedia("missing"));
    }

    @Test
    void theJournalSaysHowManyAttachmentsEachOccurrenceReceived() {
        WorkflowResult result = Workflow.of()
                .agent("read", new Recorder("r")).readsRequestMedia("read")
                .agent("sum", new Recorder("s"))
                .edge("read", "sum")
                .build()
                .run("x", List.of(PAGE, PAGE, PAGE), pool);

        assertTrue(result.ok(), result.failureReason());
        int read = result.journal().stream().filter(e -> e instanceof JournalEntry.Started && e.nodeId().equals("read"))
                .mapToInt(e -> ((JournalEntry.Started) e).mediaCount()).sum();
        int sum = result.journal().stream().filter(e -> e instanceof JournalEntry.Started && e.nodeId().equals("sum"))
                .mapToInt(e -> ((JournalEntry.Started) e).mediaCount()).sum();
        assertEquals(3, read);
        assertEquals(0, sum);
    }

    @Test
    void anEntryWrittenBeforeAttachmentsExisted_stillMeansZero() {
        var old = new JournalEntry.Started("n", 0, "in");
        assertEquals(0, old.mediaCount());
        assertEquals(old, new JournalEntry.Started("n", 0, "in", 0));
    }

    @Test
    void resumingPresentsTheRequestAgain_onlyTheNodesStillToRunReceiveIt() {
        Recorder first = new Recorder("one");
        Recorder second = new Recorder("two");
        var builder = Workflow.of()
                .agent("first", first).readsRequestMedia("first")
                .agent("second", second).readsRequestMedia("second")
                .edge("first", "second");
        WorkflowResult done = builder.build().run("x", List.of(PAGE), pool);
        assertTrue(done.ok(), done.failureReason());
        // Resume from the journal up to the end of "first": it is replayed, "second" runs now and is handed the request again.
        List<JournalEntry> upToFirst = new ArrayList<>();
        for (JournalEntry e : done.journal()) {
            if (e.nodeId().equals("second")) {
                break;
            }
            upToFirst.add(e);
        }
        Recorder first2 = new Recorder("one");
        Recorder second2 = new Recorder("two");
        WorkflowResult resumed = Workflow.of()
                .agent("first", first2).readsRequestMedia("first")
                .agent("second", second2).readsRequestMedia("second")
                .edge("first", "second")
                .build()
                .run("x", List.of(PAGE), pool, upToFirst);

        assertTrue(resumed.ok(), resumed.failureReason());
        assertTrue(first2.media().isEmpty(), "a node the journal records as finished is replayed, not run");
        assertEquals(List.of(PAGE), second2.media().get(0));
    }

    @Test
    void aWorkflowHostedAsAnAgent_takesTheTasksAttachments() {
        Recorder read = new Recorder("out");
        Workflow workflow = Workflow.of().agent("read", read).readsRequestMedia("read").build();

        AgentResponse response = WorkflowAgents.of(workflow).execute(AgentTask.of("riassumi", List.of(PAGE)));

        assertTrue(response.isSuccess(), response.failureReasonOpt().orElse(""));
        assertEquals(List.of(PAGE), read.media().get(0), "the contract of an agent: its task's attachments are not dropped");
    }

    @Test
    void aWorkflowHostedAsAnAgent_refusesAttachmentsNobodyReads() {
        Workflow workflow = Workflow.of().agent("a", new Recorder("out")).build();

        AgentResponse response = WorkflowAgents.of(workflow).execute(AgentTask.of("x", List.of(PAGE)));

        assertFalse(response.isSuccess());
        assertTrue(response.failureReasonOpt().orElse("").contains("no node"), response.failureReasonOpt().orElse(""));
    }

    /** An agent that answers a fixed text and records the attachments of each task it receives. */
    private static final class Recorder implements AraAgent {
        private final AgentId id = AgentId.generate();
        private final String content;
        private final List<List<MediaRef>> seen = new CopyOnWriteArrayList<>();

        Recorder(String content) {
            this.content = content;
        }

        List<List<MediaRef>> media() {
            return seen;
        }

        @Override public AgentId agentId() { return id; }
        @Override public AgentConfig config() { return AgentConfig.defaults().agentType("recorder").build(); }
        @Override public AgentState currentState() { return AgentState.IDLE; }
        @Override public AgentResponse execute(AgentTask task) {
            seen.add(task.media());
            return AgentResponse.success(task.taskId(), id, content, 1, 5, 0.01, Duration.ofMillis(1), List.of());
        }
        @Override public void terminate() { }
    }
}
