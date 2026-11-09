package io.ara.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.SessionId;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaStore;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.stubs.ScriptedLlmClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * The media a final answer cites come back as artifacts [V17–V20]. The cases that matter most are the negative ones:
 * a citation must never make a run fail, and must never return something the session did not have.
 */
class CitedMediaRunTest {

    private static final byte[] CROP_1 = {1, 1, 1, 1};
    private static final byte[] CROP_2 = {2, 2, 2, 2};

    /** Crops by writing through the run's MediaAccess, as a real tool does; {@code which} picks the bytes. */
    private static final class Crop implements AraTool {
        @Override public String toolId() { return "crop"; }
        @Override public String description() { return "crops"; }
        @Override public String argumentSchema() { return "{\"type\":\"object\"}"; }
        @Override public ToolResult execute(String json) { throw new AssertionError("needs the task"); }
        @Override public ToolResult execute(String json, AgentTask task) {
            var access = task.runContext().mediaAccess().orElseThrow();
            MediaRef one = access.put("crop-1.png", "image/png", CROP_1);
            MediaRef two = access.put("crop-2.png", "image/png", CROP_2);
            return ToolResult.success("crop", "two crops", List.of(one, two));
        }
    }

    private static final Crop TOOL = new Crop();

    private static final ToolRegistry REGISTRY = new ToolRegistry() {
        @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(TOOL); }
        @Override public Optional<AraTool> findById(String id) { return "crop".equals(id) ? Optional.of(TOOL) : Optional.empty(); }
        @Override public ToolResult execute(String id, String json) { return TOOL.execute(json, null); }
        @Override public ToolResult execute(String id, String json, AgentTask task) { return TOOL.execute(json, task); }
    };

    private static AgentConfig config() {
        return AgentConfig.defaults().agentId(AgentId.of("a")).agentType("t").primaryLlm(LlmProfile.of("default"))
                .plannerStrategy("react").enabledTools(List.of("crop")).maxIterations(5).build();
    }

    /** What the answer says is built from the real ids, which are digests of the bytes and so known beforehand. */
    private static AgentResponse answer(MediaStore store, java.util.function.Function<List<MediaRef>, String> text) {
        List<MediaRef> refs = List.of(store.put("crop-1.png", "image/png", CROP_1), store.put("crop-2.png", "image/png", CROP_2));
        LlmClient llm = ScriptedLlmClient.script().thenToolCall("crop", "{}").thenFinalAnswer(text.apply(refs)).build();
        try (AraRuntime runtime = AraRuntime.builder().llmClient(llm).toolRegistry(REGISTRY).mediaStore(store).build()) {
            return runtime.createAgent(config()).execute(AgentTask.of("crop it").withSessionId(SessionId.of("s1")));
        }
    }

    /** [V17] */
    @Test
    void aCitedCropComesBackAsAnArtifactAndAnUncitedOneDoesNot() {
        MediaStore store = MediaStore.inMemory();
        AgentResponse cited = answer(store, r -> "Here is the photo: crop-2.png (id " + r.get(1).shortId() + ")");

        assertTrue(cited.isSuccess());
        assertEquals(List.of(store.put("crop-2.png", "image/png", CROP_2)), cited.artifacts(),
                "only the one the answer names, not every crop the tool produced");

        AgentResponse uncited = answer(MediaStore.inMemory(), r -> "I could not find a photo.");
        assertTrue(uncited.isSuccess());
        assertTrue(uncited.artifacts().isEmpty());
    }

    /** [V18] */
    @Test
    void anInventedOrForeignIdChangesNothingAndTheAnswerStaysValid() {
        MediaStore store = MediaStore.inMemory();
        // Stored, but by nobody in this session: it is in the store and not in the session's view.
        MediaRef foreign = store.put("foreign.png", "image/png", new byte[] {9, 9, 9, 9});

        AgentResponse response = answer(store, r -> "See deadbeefcafe0123 and " + foreign.shortId() + " and 0123456789ab.");

        assertTrue(response.isSuccess(), "a bad citation is not an error");
        assertTrue(response.artifacts().isEmpty());
        assertTrue(response.content().contains(foreign.shortId()), "the text is not rewritten");
    }

    /** [V19] */
    @Test
    void theOrderIsTheOrderOfFirstMentionAndARepeatIsReturnedOnce() {
        MediaStore store = MediaStore.inMemory();

        AgentResponse response = answer(store,
                r -> r.get(1).shortId() + " then " + r.get(0).mediaId() + " then " + r.get(1).shortId() + " again");

        assertEquals(List.of(store.put("crop-2.png", "image/png", CROP_2), store.put("crop-1.png", "image/png", CROP_1)),
                response.artifacts(), "a full id and a short one are the same media");
    }

    /** [V20] */
    @Test
    void withoutAStoreThereIsNothingToResolve() {
        MediaStore store = MediaStore.inMemory();
        List<MediaRef> refs = List.of(store.put("crop-1.png", "image/png", CROP_1));
        LlmClient llm = ScriptedLlmClient.script().thenFinalAnswer("photo id " + refs.get(0).shortId()).build();

        try (AraRuntime runtime = AraRuntime.builder().llmClient(llm).toolRegistry(REGISTRY).mediaStore(MediaStore.noop()).build()) {
            AgentResponse response = runtime.createAgent(config()).execute(AgentTask.of("hi"));

            assertTrue(response.isSuccess());
            assertTrue(response.artifacts().isEmpty(), "the response is what it was before media existed");
        }
    }

    /** [V20] */
    @Test
    void aFailedRunReturnsNoMedia() {
        MediaStore store = MediaStore.inMemory();
        MediaRef ref = store.put("crop-1.png", "image/png", CROP_1);
        LlmClient failing = new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                throw new IllegalStateException("model down, last words: " + ref.shortId());
            }
            @Override public String providerId() { return "failing"; }
        };

        try (AraRuntime runtime = AraRuntime.builder().llmClient(failing).toolRegistry(REGISTRY).mediaStore(store).build()) {
            AgentResponse response = runtime.createAgent(config()).execute(AgentTask.of("hi", List.of(ref)));

            assertFalse(response.isSuccess());
            assertTrue(response.artifacts().isEmpty());
        }
    }
}
