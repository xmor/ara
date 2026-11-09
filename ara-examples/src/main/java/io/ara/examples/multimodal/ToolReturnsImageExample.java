package io.ara.examples.multimodal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.media.MediaAccess;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaStore;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.examples.support.Live;
import io.ara.runtime.AraRuntime;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * A tool that returns an image the model then looks at: find the photo in a scanned document, crop it, check the
 * crop, and hand the result back to the caller.
 *
 * <p>What it shows, in the order it happens:
 * <ol>
 *   <li>The document is attached to the task, as in {@link MultimodalInputExample}.</li>
 *   <li>The model calls {@code crop_region} with the region it picked. The tool reads the document through the
 *       run's {@link MediaAccess} — the part of the store this session has seen, nothing else — and writes the crop
 *       back with {@link MediaAccess#put}, which is what makes it visible to the rest of the session.</li>
 *   <li>The result is a {@link ToolResult} that carries the crop. The runtime adds a line naming it
 *       ({@code Attached: photo-crop.png (id …)}) and the adapter puts the image where the provider takes it, so the
 *       model sees the crop itself, not a description of it.</li>
 *   <li>The model answers and cites the crop by its id; the runtime returns it in {@link AgentResponse#artifacts()},
 *       which is how a caller (a web page, say) gets the picture and not only the text.</li>
 * </ol>
 *
 * <p>With no argument it runs offline against a model that plays the part, so the wiring can be checked without an
 * endpoint. With {@code live} (or {@code -Dara.example.live=true}) it talks to an OpenAI-compatible endpoint that
 * serves a vision model: {@code -Dara.base.url=...} and {@code -Dara.model=...} choose it, and the key comes from
 * {@code -Dara.api.key} or {@code ARA_API_KEY}. The example checks its own claims and exits non-zero if one fails.
 */
public final class ToolReturnsImageExample {

    private static final String BASE_URL = System.getProperty("ara.base.url", "http://localhost:8000/v1");
    private static final String MODEL = System.getProperty("ara.model", "qwen3.8-27b-fp8");

    private static final int WIDTH = 900;
    private static final int HEIGHT = 600;
    private static final Color PHOTO_COLOUR = new Color(230, 120, 20);

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        boolean live = Live.requested(args);
        System.out.println("ARA — a tool that returns an image (" + (live ? "live: " + MODEL + " @ " + BASE_URL : "offline stub") + ")");

        MediaStore store = MediaStore.inMemory();
        MediaRef document = store.put("scan.png", "image/png", syntheticDocument());
        LlmClient model = live
                ? OpenAiLlmClient.builder().baseUrl(BASE_URL).apiKey(Live.apiKey("not-required")).modelName(MODEL)
                        .timeout(Duration.ofMinutes(5)).build()
                : new StubModel();

        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient(model).toolRegistry(new CropRegistry()).mediaStore(store).build()) {

            AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                    .agentType("photo-extractor")
                    .systemPrompt("You extract the photograph of a person from scanned documents. Regions are given as "
                            + "fractions of the page (0 is the left or top edge, 1 the right or bottom). After cropping, "
                            + "look at the crop you get back: if it is not just the photo, crop again. When satisfied, "
                            + "answer with the final crop's name and id, exactly as written in its Attached line.")
                    .primaryLlm(LlmProfile.of("default"))
                    .plannerStrategy("react")
                    .enabledTools(List.of(CropTool.ID))
                    .maxIterations(8)
                    .build());

            AgentResponse response = agent.execute(AgentTask.of(
                    "Extract the photo of the person from the attached document.", List.of(document)));

            System.out.println("Success   : " + response.isSuccess());
            System.out.println("Answer    : " + response.content());
            System.out.println("Artifacts : " + response.artifacts());

            boolean ok = response.isSuccess() && !response.artifacts().isEmpty();
            if (ok) {
                MediaRef crop = response.artifacts().get(response.artifacts().size() - 1);
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(store.get(crop.mediaId()).orElseThrow()));
                System.out.printf("Crop      : %s, %dx%d (document %dx%d)%n",
                        crop.name(), image.getWidth(), image.getHeight(), WIDTH, HEIGHT);
                ok = image.getWidth() < WIDTH && image.getHeight() < HEIGHT;
            }
            System.out.println(ok ? "CHECK OK: a crop smaller than the page came back as an artifact."
                    : "CHECK FAILED: no usable crop in the artifacts.");
            if (!ok) {
                System.exit(1);
            }
        }
    }

    // ── The tool ──────────────────────────────────────────────────────────────────────────────────────────

    /** Crops a region of an image the session has seen and returns the crop. */
    static final class CropTool implements AraTool {
        static final String ID = "crop_region";

        @Override public String toolId() { return ID; }

        @Override public String description() {
            return "Crops a rectangle out of an image and returns the crop, which you will then see. Coordinates "
                    + "are fractions of the image: x1,y1 the top-left corner and x2,y2 the bottom-right, each 0 to 1.";
        }

        @Override public String argumentSchema() {
            return """
                    {"type":"object","properties":{
                      "media_id":{"type":"string","description":"Id of the image, as printed in \\"(id xxxxxxxxxxxx)\\""},
                      "x1":{"type":"number"},"y1":{"type":"number"},"x2":{"type":"number"},"y2":{"type":"number"}},
                     "required":["media_id","x1","y1","x2","y2"]}""";
        }

        @Override public ToolResult execute(String argumentJson) {
            return ToolResult.failure(ID, "crop_region needs the run it is called from");
        }

        @Override public ToolResult execute(String argumentJson, AgentTask task) {
            // The view of this session's media; absent when the runtime has no store.
            MediaAccess access = task.runContext().mediaAccess().orElse(null);
            if (access == null) {
                return ToolResult.failure(ID, "No media store: there is nothing to crop.");
            }
            try {
                JsonNode args = JSON.readTree(argumentJson);
                MediaRef source = access.resolve(args.get("media_id").asText()).orElse(null);
                if (source == null) {
                    return ToolResult.failure(ID, "No image with that id in this conversation.");
                }
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(access.read(source)));
                int x1 = pixel(args.get("x1").asDouble(), image.getWidth());
                int y1 = pixel(args.get("y1").asDouble(), image.getHeight());
                int x2 = pixel(args.get("x2").asDouble(), image.getWidth());
                int y2 = pixel(args.get("y2").asDouble(), image.getHeight());
                if (x2 - x1 < 8 || y2 - y1 < 8) {
                    return ToolResult.failure(ID, "The region is empty or too small; x2 must exceed x1 and y2 exceed y1.");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(image.getSubimage(x1, y1, x2 - x1, y2 - y1), "png", out);
                // put, not the store: this is what makes the crop visible to the session, so the model can cite it.
                MediaRef crop = access.put("photo-crop.png", "image/png", out.toByteArray());
                return ToolResult.success(ID, "Cropped " + (x2 - x1) + "x" + (y2 - y1) + " px.", List.of(crop));
            } catch (Exception e) {
                return ToolResult.failure(ID, "Could not crop: " + e.getMessage());
            }
        }

        private static int pixel(double fraction, int size) {
            return (int) Math.max(0, Math.min(size - 1, Math.round(fraction * size)));
        }
    }

    private static final class CropRegistry implements ToolRegistry {
        private final CropTool tool = new CropTool();

        @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(tool); }
        @Override public Optional<AraTool> findById(String id) {
            return CropTool.ID.equals(id) ? Optional.of(tool) : Optional.empty();
        }
        @Override public ToolResult execute(String id, String json) { return tool.execute(json); }
        @Override public ToolResult execute(String id, String json, AgentTask task) { return tool.execute(json, task); }
    }

    // ── The offline model and the synthetic document ─────────────────────────────────────────────────────

    /**
     * Plays the model without an endpoint: asks for a crop of the photo's corner of the page, then cites whatever
     * the tool reported. It reads the id from the {@code Attached:} line the runtime wrote, as a real model would.
     */
    private static final class StubModel implements LlmClient {
        private static final Pattern ATTACHED = Pattern.compile("Attached: (\\S+) \\(id ([0-9a-f]+)\\)");

        @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Matcher m = ATTACHED.matcher(messages.get(i).content() == null ? "" : messages.get(i).content());
                if (m.find()) {
                    return new LlmCompletion("The photo is " + m.group(1) + " (id " + m.group(2) + ").", 1, 1, "stop", null);
                }
            }
            MediaRef document = messages.stream().flatMap(m -> m.media().stream()).findFirst().orElseThrow();
            String call = "{\"tool_id\":\"crop_region\",\"arguments\":{\"media_id\":\"" + document.shortId()
                    + "\",\"x1\":0.03,\"y1\":0.12,\"x2\":0.34,\"y2\":0.70}}";
            return new LlmCompletion(call, 1, 1, "stop", null);
        }

        @Override public String providerId() { return "stub-vision"; }
    }

    /** A page with a photo-like block at the left, where a scanned ID has its picture, and a few lines of "text". */
    private static byte[] syntheticDocument() throws Exception {
        BufferedImage page = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        g.setColor(new Color(235, 235, 228));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(PHOTO_COLOUR);
        g.fillRect(40, 80, 250, 320);
        g.setColor(new Color(60, 40, 30));
        g.fillOval(100, 130, 130, 150);
        g.setColor(new Color(90, 90, 100));
        for (int line = 0; line < 6; line++) {
            g.fillRect(340, 100 + line * 55, 480 - (line % 3) * 90, 18);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(page, "png", out);
        return out.toByteArray();
    }

    private ToolReturnsImageExample() { }
}
