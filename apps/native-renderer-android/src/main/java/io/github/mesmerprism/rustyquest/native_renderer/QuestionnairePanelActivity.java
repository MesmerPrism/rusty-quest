package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class QuestionnairePanelActivity extends Activity {
    public static final String ACTION_OPEN_BLOCK =
        "io.github.mesmerprism.rustyquest.native_renderer.action.OPEN_QUESTIONNAIRE_BLOCK";
    public static final String ACTION_APPLY_COMMAND =
        "io.github.mesmerprism.rustyquest.native_renderer.action.APPLY_QUESTIONNAIRE_COMMAND";
    public static final String EXTRA_BLOCK_ID = "block_id";
    public static final String EXTRA_BLOCK = "block";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_COMMAND_SCRIPT = "command_script";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_PARTICIPANT_ID = "participant_id";
    public static final String EXTRA_REQUEST_ID = "request_id";
    public static final String EXTRA_STUDY_ID = "study_id";
    public static final String EXTRA_LANGUAGE_CODE = "language_code";
    public static final String EXTRA_ITEM_INDEX = "item_index";
    public static final String EXTRA_SCORE = "score";
    public static final String EXTRA_CHOICE = "choice";
    public static final String EXTRA_VALUE = "value";
    public static final String EXTRA_DEBUG_COMMAND_SCRIPT =
        "io.github.mesmerprism.questquestionnaire.extra.DEBUG_COMMAND_SCRIPT";

    private static final String QUESTIONNAIRE_RESULT_SCHEMA =
        "rusty.telemetry.questionnaire_result.v1";
    private static final String QUESTIONNAIRE_RESULT_FILE = "questionnaire_results.jsonl";
    private static final String QUESTIONNAIRE_ID = "maia2-spatial-frame-questionnaire-v1";
    private static final int QUESTIONNAIRE_VERSION = 1;
    private static final String PROGRAM_ID = "maia2-spatial-frame-reference-three-block";
    private static final String CONTENT_VERSION = "1.0.0";
    private static final String MAIA_SCORE_VERSION = "maia2-2018-standard";
    private static final String SPATIAL_ASSET_SHA256 =
        "4D5AA60F1475DC2E75BB859CC6890D7E3A42F7EF41C5BDE2C4CF22B30693E5C3";
    private static final String ASSET_ROOT = "maia_spatial_questionnaire";
    private static final String PICTOGRAPH_ASSET =
        ASSET_ROOT + "/assets/pictographs/spatial-frame-reference-continuum.png";

    private static final String STAGE_LANGUAGE = "maia_spatial:language_selection";
    private static final String STAGE_DEMOGRAPHICS = "maia_spatial:demographics";
    private static final String STAGE_MAIA = "maia_spatial:maia2";
    private static final String STAGE_SPATIAL_1 = "maia_spatial:spatial_frame_reference_1";
    private static final String STAGE_SPATIAL_2 = "maia_spatial:spatial_frame_reference_2";
    private static final String BLOCK_ONE = "block_1_setup_maia2";
    private static final String BLOCK_TWO = "block_2_spatial_frame_reference";
    private static final String BLOCK_THREE = "block_3_spatial_frame_reference";
    private static final String[] BLOCK_ONE_SEQUENCE =
        new String[] { STAGE_LANGUAGE, STAGE_DEMOGRAPHICS, STAGE_MAIA };
    private static final String[] BLOCK_TWO_SEQUENCE = new String[] { STAGE_SPATIAL_1 };
    private static final String[] BLOCK_THREE_SEQUENCE = new String[] { STAGE_SPATIAL_2 };

    private static final String[] GENDER_IDS =
        new String[] { "female", "male", "non_binary_or_diverse", "prefer_not_to_say" };
    private static final String[] HANDEDNESS_IDS =
        new String[] { "left", "right", "ambidextrous", "prefer_not_to_say" };

    private static boolean nativeBridgeLoaded;
    private static String nativeBridgeLoadError;

    static {
        try {
            System.loadLibrary("rusty_quest_native_renderer");
            nativeBridgeLoaded = true;
            nativeBridgeLoadError = "";
        } catch (UnsatisfiedLinkError error) {
            nativeBridgeLoaded = false;
            nativeBridgeLoadError = error.getMessage();
        }
    }

    private QuestionnaireContent content;
    private String sessionId = "S001";
    private String participantId = "unknown";
    private String requestId = "";
    private String studyId = "viscereality-gpu";
    private String currentBlockId = BLOCK_ONE;
    private int currentStageIndex = 0;
    private long startedRuntimeNs;
    private String languageCode = "en";
    private String participantName = "";
    private String ageText = "";
    private String gender = "";
    private String handedness = "";
    private boolean consent;
    private String signature = "";
    private final int[] maiaScores = new int[37];
    private String spatialChoice1 = "";
    private String spatialChoice2 = "";
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Arrays.fill(maiaScores, -1);
        startedRuntimeNs = System.nanoTime();
        content = QuestionnaireContent.load(this);
        readLaunchExtras();
        render();
        applyIntentCommand(false);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readLaunchExtras();
        applyIntentCommand(true);
    }

    private void readLaunchExtras() {
        android.content.Intent intent = getIntent();
        sessionId = safeSessionId(intent.getStringExtra(EXTRA_SESSION_ID), sessionId);
        participantId = safeToken(intent.getStringExtra(EXTRA_PARTICIPANT_ID), participantId, 80);
        requestId = safeToken(intent.getStringExtra(EXTRA_REQUEST_ID), requestId, 80);
        studyId = safeToken(intent.getStringExtra(EXTRA_STUDY_ID), studyId, 80);
        String requestedLanguage = intent.getStringExtra(EXTRA_LANGUAGE_CODE);
        if (requestedLanguage != null) {
            languageCode = normalizeLanguage(requestedLanguage, languageCode);
        }
        String block = requestedBlockFromIntent(intent);
        if (block != null) {
            openBlock(block);
        }
    }

    private void applyIntentCommand(boolean rerenderAfter) {
        android.content.Intent intent = getIntent();
        String action = intent.getAction();
        if (ACTION_OPEN_BLOCK.equals(action)) {
            render();
            return;
        }
        String script = intent.getStringExtra(EXTRA_COMMAND_SCRIPT);
        if (script == null || script.trim().isEmpty()) {
            script = intent.getStringExtra(EXTRA_DEBUG_COMMAND_SCRIPT);
        }
        boolean changed = false;
        if (script != null && !script.trim().isEmpty()) {
            for (String command : splitCommands(script)) {
                changed |= applyCommand(command, intent);
            }
        }
        String command = intent.getStringExtra(EXTRA_COMMAND);
        if (ACTION_APPLY_COMMAND.equals(action) || (command != null && !command.trim().isEmpty())) {
            changed |= applyCommand(command == null ? "" : command, intent);
        }
        if (rerenderAfter || changed) {
            render();
        }
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(18), dp(24), dp(18));
        root.setBackgroundColor(Color.rgb(18, 20, 22));

        TextView title = text("MAIA-2 Spatial Frame", 24, Color.WHITE);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, matchWrap());

        statusText = text(statusLine(), 13, Color.rgb(198, 210, 220));
        root.addView(statusText, matchWrap());

        LinearLayout blockRow = new LinearLayout(this);
        blockRow.setOrientation(LinearLayout.HORIZONTAL);
        blockRow.setGravity(Gravity.CENTER_VERTICAL);
        blockRow.setPadding(0, dp(12), 0, dp(8));
        addBlockButton(blockRow, "Block 1", BLOCK_ONE);
        addBlockButton(blockRow, "Block 2", BLOCK_TWO);
        addBlockButton(blockRow, "Block 3", BLOCK_THREE);
        root.addView(blockRow, matchWrap());

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(body, matchWrap());
        renderActiveStage(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        ));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("Back");
        back.setEnabled(canGoBack());
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                goBack();
            }
        });
        Button cancel = button("Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitCurrent("cancelled");
            }
        });
        Button next = button(isLastStage() ? "Submit" : "Next");
        next.setEnabled(canProceed(activeStage()));
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                goNext();
            }
        });
        nav.addView(back, weightedButton());
        nav.addView(cancel, weightedButton());
        nav.addView(next, weightedButton());
        root.addView(nav, matchWrap());
        setContentView(root);
    }

    private void renderActiveStage(LinearLayout body) {
        String stage = activeStage();
        LocalizedStrings strings = content.strings(languageCode);
        if (STAGE_LANGUAGE.equals(stage)) {
            body.addView(text(strings.languageTitle, 20, Color.WHITE), matchWrap());
            body.addView(text(strings.languagePrompt, 16, Color.rgb(225, 232, 238)), matchWrap());
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            addLanguageButton(row, strings.englishLanguage, "en");
            addLanguageButton(row, strings.germanLanguage, "de");
            body.addView(row, matchWrap());
        } else if (STAGE_DEMOGRAPHICS.equals(stage)) {
            body.addView(text(strings.demographicsTitle, 20, Color.WHITE), matchWrap());
            addEdit(body, strings.nameLabel, participantName, new TextSink() {
                @Override
                public void set(String value) {
                    participantName = value.trim();
                }
            });
            addEdit(body, strings.ageLabel, ageText, new TextSink() {
                @Override
                public void set(String value) {
                    ageText = digits(value, 3);
                }
            });
            body.addView(text(strings.genderLabel, 16, Color.rgb(225, 232, 238)), matchWrap());
            addChoiceGroup(body, GENDER_IDS, strings.genderChoices, gender, new TextSink() {
                @Override
                public void set(String value) {
                    gender = value;
                }
            });
            body.addView(text(strings.handednessLabel, 16, Color.rgb(225, 232, 238)), matchWrap());
            addChoiceGroup(body, HANDEDNESS_IDS, strings.handednessChoices, handedness, new TextSink() {
                @Override
                public void set(String value) {
                    handedness = value;
                }
            });
            CheckBox consentBox = new CheckBox(this);
            consentBox.setText(strings.consentText);
            consentBox.setTextColor(Color.rgb(225, 232, 238));
            consentBox.setChecked(consent);
            consentBox.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    consent = ((CheckBox) view).isChecked();
                    render();
                }
            });
            body.addView(consentBox, matchWrap());
            addEdit(body, strings.signatureLabel, signature, new TextSink() {
                @Override
                public void set(String value) {
                    signature = value.trim();
                }
            });
        } else if (STAGE_MAIA.equals(stage)) {
            body.addView(text(strings.maiaTitle, 20, Color.WHITE), matchWrap());
            body.addView(text(strings.maiaInstructions, 15, Color.rgb(225, 232, 238)), matchWrap());
            body.addView(text(strings.maiaLeftAnchor + " 0 / 5 " + strings.maiaRightAnchor, 14,
                Color.rgb(198, 210, 220)), matchWrap());
            List<MaiaItem> items = content.maiaItems(languageCode);
            for (final MaiaItem item : items) {
                TextView itemLabel = text(item.id + ". " + item.text, 15, Color.WHITE);
                itemLabel.setPadding(0, dp(12), 0, dp(4));
                body.addView(itemLabel, matchWrap());
                RadioGroup group = new RadioGroup(this);
                group.setOrientation(RadioGroup.HORIZONTAL);
                for (int score = 0; score <= 5; score++) {
                    RadioButton radio = radio(Integer.toString(score));
                    radio.setId(30000 + item.id * 10 + score);
                    radio.setChecked(maiaScores[item.id - 1] == score);
                    group.addView(radio);
                }
                group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(RadioGroup group, int checkedId) {
                        int score = checkedId - 30000 - item.id * 10;
                        if (score >= 0 && score <= 5) {
                            maiaScores[item.id - 1] = score;
                            setStatus(statusLine());
                        }
                    }
                });
                body.addView(group, matchWrap());
            }
        } else if (STAGE_SPATIAL_1.equals(stage) || STAGE_SPATIAL_2.equals(stage)) {
            final boolean secondAdministration = STAGE_SPATIAL_2.equals(stage);
            body.addView(text(strings.spatialTitle, 20, Color.WHITE), matchWrap());
            body.addView(text(strings.spatialInstructions, 15, Color.rgb(225, 232, 238)), matchWrap());
            body.addView(text("Administration " + (secondAdministration ? "2" : "1"), 14,
                Color.rgb(198, 210, 220)), matchWrap());
            Bitmap image = loadPictograph();
            if (image != null) {
                ImageView imageView = new ImageView(this);
                imageView.setImageBitmap(image);
                imageView.setAdjustViewBounds(true);
                imageView.setMaxHeight(dp(320));
                body.addView(imageView, matchWrap());
            }
            body.addView(text(strings.spatialChoicePrompt, 16, Color.rgb(225, 232, 238)), matchWrap());
            RadioGroup group = new RadioGroup(this);
            group.setOrientation(RadioGroup.HORIZONTAL);
            String selected = secondAdministration ? spatialChoice2 : spatialChoice1;
            for (String choice : content.spatialChoices) {
                RadioButton radio = radio(choice);
                radio.setId(40000 + choice.charAt(0));
                radio.setChecked(choice.equals(selected));
                group.addView(radio);
            }
            group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(RadioGroup group, int checkedId) {
                    String choice = Character.toString((char) (checkedId - 40000));
                    if (content.spatialChoices.contains(choice)) {
                        if (secondAdministration) {
                            spatialChoice2 = choice;
                        } else {
                            spatialChoice1 = choice;
                        }
                        setStatus(statusLine());
                    }
                }
            });
            body.addView(group, matchWrap());
        }
    }

    private boolean applyCommand(String rawCommand, android.content.Intent intent) {
        ParsedCommand parsed = ParsedCommand.parse(rawCommand);
        String command = parsed.name;
        String argument = parsed.argument;
        if (command.isEmpty()) {
            command = normalizeCommand(intent.getStringExtra(EXTRA_COMMAND));
        }
        if (argument.isEmpty()) {
            argument = stringExtra(intent, EXTRA_VALUE);
        }
        if ("open".equals(command) || "block".equals(command)) {
            String block = requestedBlockFromIntent(intent);
            if (block == null) {
                block = blockFromValue(argument);
            }
            if (block != null) {
                openBlock(block);
                return true;
            }
            return false;
        }
        if ("block_1".equals(command) || "block1".equals(command)) {
            openBlock(BLOCK_ONE);
            return true;
        }
        if ("block_2".equals(command) || "block2".equals(command)) {
            openBlock(BLOCK_TWO);
            return true;
        }
        if ("block_3".equals(command) || "block3".equals(command)) {
            openBlock(BLOCK_THREE);
            return true;
        }
        if ("back".equals(command)) {
            goBack();
            return true;
        }
        if ("next".equals(command) || "click_next".equals(command) || "tap_next".equals(command)) {
            goNext();
            return true;
        }
        if ("submit".equals(command) || "click_submit".equals(command) || "tap_submit".equals(command)) {
            submitCurrent("submitted");
            return true;
        }
        if ("cancel".equals(command)) {
            submitCurrent("cancelled");
            return true;
        }
        if ("close".equals(command)) {
            finish();
            return true;
        }
        if ("defaults".equals(command) || "debug_defaults".equals(command) || "sample_complete".equals(command)) {
            applyDebugDefaults();
            if ("sample_complete".equals(command)) {
                submitCurrent("submitted");
            }
            return true;
        }
        if ("consent".equals(command)) {
            consent = true;
            return true;
        }
        if ("english".equals(command)) {
            languageCode = "en";
            return true;
        }
        if ("german".equals(command) || "deutsch".equals(command)) {
            languageCode = "de";
            return true;
        }
        if ("language".equals(command)) {
            languageCode = normalizeLanguage(argument, languageCode);
            return true;
        }
        if ("name".equals(command)) {
            participantName = argument.trim();
            return true;
        }
        if ("age".equals(command)) {
            ageText = digits(argument, 3);
            return true;
        }
        if ("gender".equals(command)) {
            gender = firstAllowed(argument, GENDER_IDS, gender);
            return true;
        }
        if ("handedness".equals(command)) {
            handedness = firstAllowed(argument, HANDEDNESS_IDS, handedness);
            return true;
        }
        if ("signature".equals(command)) {
            signature = argument.trim();
            return true;
        }
        if ("maia_all".equals(command)) {
            int score = parseInt(argument, 3, 0, 5);
            Arrays.fill(maiaScores, score);
            return true;
        }
        if ("maia_set".equals(command) || "maia".equals(command)) {
            int item = intent.getIntExtra(EXTRA_ITEM_INDEX, -1);
            int score = intent.getIntExtra(EXTRA_SCORE, -1);
            applyMaiaSet(argument, item, score);
            return true;
        }
        if ("choice".equals(command) || "spatial_choice".equals(command)) {
            String choice = stringExtra(intent, EXTRA_CHOICE);
            if (choice.isEmpty()) {
                choice = argument;
            }
            applySpatialChoice(choice);
            return true;
        }
        setStatus("Unknown questionnaire command: " + command);
        return false;
    }

    private void goBack() {
        if (currentStageIndex > 0) {
            currentStageIndex -= 1;
            render();
        }
    }

    private void goNext() {
        String stage = activeStage();
        if (!canProceed(stage)) {
            setStatus("Complete required fields for " + stage + " before continuing.");
            return;
        }
        if (isLastStage()) {
            submitCurrent("submitted");
        } else {
            currentStageIndex += 1;
            render();
        }
    }

    private void submitCurrent(String status) {
        String stage = activeStage();
        if ("submitted".equals(status) && !canProceed(stage)) {
            setStatus("Submit rejected: incomplete required fields for " + stage + ".");
            return;
        }
        try {
            JSONObject row = buildQuestionnaireResultRow(status);
            File sessionDir = new File(new File(getFilesDir(), "sessions"), sessionId);
            String responseText = nativeBridgeLoaded
                ? nativeSubmitQuestionnaireResult(sessionDir.getAbsolutePath(), row.toString())
                : rejectedNativeLoadResponse();
            JSONObject response = new JSONObject(responseText == null ? "{}" : responseText);
            String nativeStatus = response.optString("status", "unknown");
            setStatus("Questionnaire " + status + ": " + nativeStatus);
            Toast.makeText(this, "Questionnaire " + nativeStatus, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            setStatus("Questionnaire submit failed: " + error.getMessage());
            Toast.makeText(this, "Questionnaire submit failed", Toast.LENGTH_LONG).show();
        }
    }

    private JSONObject buildQuestionnaireResultRow(String status) throws Exception {
        long runtimeNs = System.nanoTime();
        long wallNs = wallTimeUnixNs();
        String resultId = requestId.trim().isEmpty()
            ? "questionnaire-" + currentBlockId + "-" + wallNs
            : requestId + "-" + currentBlockId + "-" + wallNs;
        JSONObject answers = buildResultEnvelope(status, runtimeNs, wallNs);
        JSONObject scoring = new JSONObject();
        if (BLOCK_ONE.equals(currentBlockId)) {
            scoring.put("maia2", scoreMaia2());
        }
        return new JSONObject()
            .put("schema_id", QUESTIONNAIRE_RESULT_SCHEMA)
            .put("schema_version", 1)
            .put("session_id", sessionId)
            .put("result_id", resultId)
            .put("questionnaire_id", QUESTIONNAIRE_ID)
            .put("block_id", currentBlockId)
            .put("source", "within-app-panel")
            .put("submitted_runtime_time_ns", runtimeNs)
            .put("submitted_wall_time_unix_ns", wallNs)
            .put("answers_json", answers)
            .put("scoring_json", scoring)
            .put("status", status);
    }

    private JSONObject buildResultEnvelope(String status, long runtimeNs, long wallNs) throws Exception {
        String stage = activeStage();
        JSONObject terminal = new JSONObject()
            .put("reason", status)
            .put("current_stage", stage)
            .put("screen_index", currentStageIndex);
        JSONObject timing = new JSONObject()
            .put("started_runtime_time_ns", startedRuntimeNs)
            .put("submitted_runtime_time_ns", runtimeNs)
            .put("submitted_wall_time_unix_ns", wallNs)
            .put("duration_ms", Math.max(0L, (runtimeNs - startedRuntimeNs) / 1000000L));
        return new JSONObject()
            .put("protocol_version", "quest.questionnaire.v1")
            .put("schema", "quest.questionnaire.v1.result")
            .put("request_id", requestId.trim().isEmpty() ? "same-apk-panel" : requestId)
            .put("status", "submitted".equals(status) ? "completed" : status)
            .put("questionnaire", new JSONObject()
                .put("id", QUESTIONNAIRE_ID)
                .put("version", QUESTIONNAIRE_VERSION))
            .put("stage", stage)
            .put("study_id", studyId)
            .put("participant_id", participantId)
            .put("condition_number", JSONObject.NULL)
            .put("screen_sequence", new JSONArray(Arrays.asList(sequenceForBlock(currentBlockId))))
            .put("answers", answerState(stage))
            .put("terminal", terminal)
            .put("timing", timing);
    }

    private JSONObject answerState(String completedStage) throws Exception {
        JSONObject answers = new JSONObject()
            .put("open_stage", sequenceForBlock(currentBlockId)[0])
            .put("completed_stage", completedStage)
            .put("screen_sequence", new JSONArray(Arrays.asList(sequenceForBlock(currentBlockId))))
            .put("program_id", PROGRAM_ID)
            .put("content_version", CONTENT_VERSION)
            .put("language", content.languageJson(languageCode));
        if (BLOCK_ONE.equals(currentBlockId)) {
            answers.put("demographics", new JSONObject()
                .put("name", participantName)
                .put("age", ageJsonValue())
                .put("gender", gender)
                .put("handedness", handedness)
                .put("consent", consent)
                .put("signature", signature));
            answers.put("maia2", maia2Json());
        } else if (BLOCK_TWO.equals(currentBlockId)) {
            answers.put("spatial_frame_reference_administration_1",
                spatialFrameJson(BLOCK_TWO, 1, spatialChoice1));
        } else if (BLOCK_THREE.equals(currentBlockId)) {
            answers.put("spatial_frame_reference_administration_2",
                spatialFrameJson(BLOCK_THREE, 2, spatialChoice2));
        }
        return answers;
    }

    private JSONObject maia2Json() throws Exception {
        return new JSONObject()
            .put("instrument_id", "maia2")
            .put("language", languageCode)
            .put("score_version", MAIA_SCORE_VERSION)
            .put("raw_item_scores", rawScoreJson())
            .put("scored_item_values", scoreMaia2().getJSONObject("scored_item_values"))
            .put("subscale_means", scoreMaia2().getJSONObject("subscale_means"))
            .put("completed_at_unix_ns", wallTimeUnixNs());
    }

    private JSONObject scoreMaia2() throws Exception {
        JSONObject scored = new JSONObject();
        for (int item = 1; item <= 37; item++) {
            int raw = Math.max(0, Math.min(5, maiaScores[item - 1]));
            int value = content.reverseScoredItems.contains(Integer.valueOf(item)) ? 5 - raw : raw;
            scored.put(Integer.toString(item), value);
        }
        JSONObject means = new JSONObject();
        for (Subscale subscale : content.subscales) {
            double sum = 0.0;
            int count = 0;
            for (Integer itemId : subscale.itemIds) {
                sum += scored.getInt(Integer.toString(itemId.intValue()));
                count++;
            }
            means.put(subscale.id, count == 0 ? JSONObject.NULL : sum / (double) count);
        }
        return new JSONObject()
            .put("score_version", MAIA_SCORE_VERSION)
            .put("scored_item_values", scored)
            .put("subscale_means", means);
    }

    private JSONObject rawScoreJson() throws Exception {
        JSONObject raw = new JSONObject();
        for (int item = 1; item <= 37; item++) {
            raw.put(Integer.toString(item), Math.max(0, Math.min(5, maiaScores[item - 1])));
        }
        return raw;
    }

    private JSONObject spatialFrameJson(String blockId, int administrationIndex, String choice) throws Exception {
        return new JSONObject()
            .put("instrument_id", "spatial_frame_reference_pictograph")
            .put("block_id", blockId)
            .put("administration_index", administrationIndex)
            .put("choice", choice)
            .put("asset_sha256", SPATIAL_ASSET_SHA256)
            .put("response_timestamp_unix_ns", wallTimeUnixNs());
    }

    private boolean canProceed(String stage) {
        if (STAGE_LANGUAGE.equals(stage)) {
            return "en".equals(languageCode) || "de".equals(languageCode);
        }
        if (STAGE_DEMOGRAPHICS.equals(stage)) {
            Integer age = parseAge();
            return !participantName.trim().isEmpty()
                && age != null
                && age.intValue() >= 0
                && age.intValue() <= 120
                && contains(GENDER_IDS, gender)
                && contains(HANDEDNESS_IDS, handedness)
                && consent
                && !signature.trim().isEmpty();
        }
        if (STAGE_MAIA.equals(stage)) {
            for (int score : maiaScores) {
                if (score < 0 || score > 5) {
                    return false;
                }
            }
            return true;
        }
        if (STAGE_SPATIAL_1.equals(stage)) {
            return content.spatialChoices.contains(spatialChoice1);
        }
        if (STAGE_SPATIAL_2.equals(stage)) {
            return content.spatialChoices.contains(spatialChoice2);
        }
        return true;
    }

    private void applyDebugDefaults() {
        languageCode = "en";
        participantName = participantName.trim().isEmpty() ? "Debug Participant" : participantName;
        ageText = ageText.trim().isEmpty() ? "30" : ageText;
        gender = gender.trim().isEmpty() ? "prefer_not_to_say" : gender;
        handedness = handedness.trim().isEmpty() ? "right" : handedness;
        consent = true;
        signature = signature.trim().isEmpty() ? participantName : signature;
        Arrays.fill(maiaScores, 3);
        spatialChoice1 = spatialChoice1.trim().isEmpty() ? "D" : spatialChoice1;
        spatialChoice2 = spatialChoice2.trim().isEmpty() ? "E" : spatialChoice2;
    }

    private void openBlock(String blockId) {
        currentBlockId = blockId;
        currentStageIndex = 0;
        startedRuntimeNs = System.nanoTime();
    }

    private String activeStage() {
        String[] sequence = sequenceForBlock(currentBlockId);
        return sequence[Math.max(0, Math.min(currentStageIndex, sequence.length - 1))];
    }

    private boolean canGoBack() {
        return currentStageIndex > 0;
    }

    private boolean isLastStage() {
        return currentStageIndex >= sequenceForBlock(currentBlockId).length - 1;
    }

    private String[] sequenceForBlock(String blockId) {
        if (BLOCK_TWO.equals(blockId)) {
            return BLOCK_TWO_SEQUENCE;
        }
        if (BLOCK_THREE.equals(blockId)) {
            return BLOCK_THREE_SEQUENCE;
        }
        return BLOCK_ONE_SEQUENCE;
    }

    private String statusLine() {
        return "Session " + sessionId + " | " + currentBlockId
            + " | stage " + (currentStageIndex + 1) + "/" + sequenceForBlock(currentBlockId).length
            + " | language " + languageCode;
    }

    private void setStatus(String text) {
        if (statusText != null) {
            statusText.setText(text);
        }
    }

    private void addBlockButton(LinearLayout row, String label, final String blockId) {
        Button button = button(label);
        button.setEnabled(!blockId.equals(currentBlockId));
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openBlock(blockId);
                render();
            }
        });
        row.addView(button, weightedButton());
    }

    private void addLanguageButton(LinearLayout row, String label, final String language) {
        Button button = button(label + (language.equals(languageCode) ? " *" : ""));
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                languageCode = language;
                render();
            }
        });
        row.addView(button, weightedButton());
    }

    private void addEdit(LinearLayout body, String label, String value, final TextSink sink) {
        TextView textLabel = text(label, 15, Color.rgb(225, 232, 238));
        textLabel.setPadding(0, dp(8), 0, dp(2));
        body.addView(textLabel, matchWrap());
        final EditText editText = new EditText(this);
        editText.setText(value);
        editText.setSingleLine(true);
        editText.setTextColor(Color.WHITE);
        editText.setHintTextColor(Color.rgb(150, 160, 168));
        editText.setBackgroundColor(Color.rgb(38, 42, 46));
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable editable) {
                sink.set(editable.toString());
                setStatus(statusLine());
            }
        });
        editText.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (!hasFocus) {
                    sink.set(editText.getText().toString());
                    setStatus(statusLine());
                }
            }
        });
        body.addView(editText, matchWrap());
    }

    private void addChoiceGroup(
        LinearLayout body,
        String[] ids,
        Map<String, String> labels,
        String selected,
        final TextSink sink
    ) {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        for (int i = 0; i < ids.length; i++) {
            RadioButton radio = radio(labelFor(labels, ids[i]));
            radio.setId(10000 + i);
            radio.setTag(ids[i]);
            radio.setChecked(ids[i].equals(selected));
            group.addView(radio);
        }
        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                View radio = group.findViewById(checkedId);
                if (radio != null && radio.getTag() instanceof String) {
                    sink.set((String) radio.getTag());
                    render();
                }
            }
        });
        body.addView(group, matchWrap());
    }

    private Bitmap loadPictograph() {
        try {
            return BitmapFactory.decodeStream(getAssets().open(PICTOGRAPH_ASSET));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String labelFor(Map<String, String> labels, String id) {
        String label = labels.get(id);
        return label == null ? id : label;
    }

    private TextView text(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private RadioButton radio(String text) {
        RadioButton radio = new RadioButton(this);
        radio.setText(text);
        radio.setTextColor(Color.WHITE);
        return radio;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String requestedBlockFromIntent(android.content.Intent intent) {
        String blockId = intent.getStringExtra(EXTRA_BLOCK_ID);
        if (blockId != null) {
            String block = blockFromValue(blockId);
            if (block != null) {
                return block;
            }
        }
        if (intent.hasExtra(EXTRA_BLOCK)) {
            String block = blockFromValue(intent.getStringExtra(EXTRA_BLOCK));
            if (block != null) {
                return block;
            }
            int blockNumber = intent.getIntExtra(EXTRA_BLOCK, -1);
            block = blockFromValue(Integer.toString(blockNumber));
            if (block != null) {
                return block;
            }
        }
        return null;
    }

    private static String blockFromValue(String value) {
        String normalized = normalizeCommand(value);
        if ("1".equals(normalized) || "block_1".equals(normalized)
            || BLOCK_ONE.equals(normalized)) {
            return BLOCK_ONE;
        }
        if ("2".equals(normalized) || "block_2".equals(normalized)
            || BLOCK_TWO.equals(normalized)) {
            return BLOCK_TWO;
        }
        if ("3".equals(normalized) || "block_3".equals(normalized)
            || BLOCK_THREE.equals(normalized)) {
            return BLOCK_THREE;
        }
        return null;
    }

    private void applyMaiaSet(String argument, int itemIndex, int score) {
        if ((itemIndex < 1 || itemIndex > 37) || (score < 0 || score > 5)) {
            String[] parts = argument.split("[:=,]");
            if (parts.length >= 2) {
                itemIndex = parseInt(parts[0], itemIndex, 1, 37);
                score = parseInt(parts[1], score, 0, 5);
            }
        }
        if (itemIndex >= 1 && itemIndex <= 37 && score >= 0 && score <= 5) {
            maiaScores[itemIndex - 1] = score;
        }
    }

    private void applySpatialChoice(String rawChoice) {
        String choice = rawChoice == null ? "" : rawChoice.trim().toUpperCase(Locale.US);
        if (!content.spatialChoices.contains(choice)) {
            return;
        }
        if (STAGE_SPATIAL_2.equals(activeStage()) || BLOCK_THREE.equals(currentBlockId)) {
            spatialChoice2 = choice;
        } else {
            spatialChoice1 = choice;
        }
    }

    private Object ageJsonValue() {
        Integer age = parseAge();
        return age == null ? JSONObject.NULL : age;
    }

    private Integer parseAge() {
        if (ageText.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(ageText.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static List<String> splitCommands(String script) {
        List<String> commands = new ArrayList<>();
        if (script == null) {
            return commands;
        }
        String[] parts = script.split("[;,\\n\\r]");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                commands.add(trimmed);
            }
        }
        return commands;
    }

    private static String normalizeCommand(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US)
            .replace('-', '_')
            .replace(' ', '_');
    }

    private static String normalizeLanguage(String value, String fallback) {
        String normalized = normalizeCommand(value);
        if ("de".equals(normalized) || "de_de".equals(normalized)
            || "german".equals(normalized) || "deutsch".equals(normalized)) {
            return "de";
        }
        if ("en".equals(normalized) || "en_us".equals(normalized)
            || "english".equals(normalized)) {
            return "en";
        }
        return fallback;
    }

    private static String firstAllowed(String value, String[] allowed, String fallback) {
        String normalized = normalizeCommand(value);
        for (String item : allowed) {
            if (item.equals(normalized)) {
                return item;
            }
        }
        return fallback;
    }

    private static boolean contains(String[] values, String needle) {
        for (String value : values) {
            if (value.equals(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String stringExtra(android.content.Intent intent, String name) {
        String value = intent.getStringExtra(name);
        return value == null ? "" : value;
    }

    private static int parseInt(String raw, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String digits(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length() && builder.length() < maxLength; i++) {
            char ch = value.charAt(i);
            if (Character.isDigit(ch)) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private static String safeToken(String value, String fallback, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim().substring(0, Math.min(maxLength, value.trim().length()));
    }

    private static String safeSessionId(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        String trimmed = value.trim();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < trimmed.length() && builder.length() < 160; i++) {
            char ch = trimmed.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '-') {
                builder.append(ch);
            }
        }
        if (builder.length() == 0 || !Character.isLetterOrDigit(builder.charAt(0))) {
            return fallback;
        }
        String result = builder.toString();
        return result.contains("..") ? fallback : result;
    }

    private static long wallTimeUnixNs() {
        return System.currentTimeMillis() * 1000000L;
    }

    private static String rejectedNativeLoadResponse() {
        try {
            return new JSONObject()
                .put("schema", "rusty.quest.native_renderer.questionnaire_result_status.v1")
                .put("status", "rejected")
                .put("transport", "jni_file_append")
                .put("rejection_code", "native_bridge_unavailable:" + nativeBridgeLoadError)
                .toString();
        } catch (Exception ignored) {
            return "{\"status\":\"rejected\",\"rejection_code\":\"native_bridge_unavailable\"}";
        }
    }

    private static native String nativeSubmitQuestionnaireResult(
        String sessionDirPath,
        String resultJson
    );

    private interface TextSink {
        void set(String value);
    }

    private static final class ParsedCommand {
        final String name;
        final String argument;

        ParsedCommand(String name, String argument) {
            this.name = name;
            this.argument = argument;
        }

        static ParsedCommand parse(String raw) {
            if (raw == null) {
                return new ParsedCommand("", "");
            }
            String trimmed = raw.trim();
            int colon = trimmed.indexOf(':');
            int equals = trimmed.indexOf('=');
            int separator = -1;
            if (colon >= 0 && equals >= 0) {
                separator = Math.min(colon, equals);
            } else if (colon >= 0) {
                separator = colon;
            } else if (equals >= 0) {
                separator = equals;
            }
            if (separator < 0) {
                return new ParsedCommand(normalizeCommand(trimmed), "");
            }
            return new ParsedCommand(
                normalizeCommand(trimmed.substring(0, separator)),
                trimmed.substring(separator + 1).trim()
            );
        }
    }

    private static final class MaiaItem {
        final int id;
        final String text;

        MaiaItem(int id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    private static final class Subscale {
        final String id;
        final List<Integer> itemIds;

        Subscale(String id, List<Integer> itemIds) {
            this.id = id;
            this.itemIds = itemIds;
        }
    }

    private static final class LocalizedStrings {
        String languageCode = "en";
        String languageLabel = "English";
        String languageTitle = "Language";
        String languagePrompt = "Please choose a language.";
        String englishLanguage = "English";
        String germanLanguage = "German";
        String demographicsTitle = "Participant Information";
        String nameLabel = "Name";
        String ageLabel = "Age";
        String genderLabel = "Gender";
        String handednessLabel = "Handedness";
        String consentText = "I consent to participate and record my responses.";
        String signatureLabel = "Signature";
        String maiaTitle = "Body Awareness";
        String maiaInstructions = "Please indicate how often each statement applies to you.";
        String maiaLeftAnchor = "Never";
        String maiaRightAnchor = "Always";
        String spatialTitle = "Spatial Frame of Reference";
        String spatialInstructions = "Choose the picture that best matches your current sense of spatial reference.";
        String spatialChoicePrompt = "Select one letter.";
        Map<String, String> genderChoices = new LinkedHashMap<>();
        Map<String, String> handednessChoices = new LinkedHashMap<>();

        static LocalizedStrings fromJson(JSONObject json) throws Exception {
            LocalizedStrings strings = defaults("en");
            JSONObject language = json.getJSONObject("language");
            JSONObject screens = json.getJSONObject("screens");
            JSONObject languageSelection = screens.getJSONObject("languageSelection");
            JSONObject demographics = screens.getJSONObject("demographics");
            JSONObject maia = screens.getJSONObject("maia2");
            JSONObject spatial = screens.getJSONObject("spatialFrameReference");
            JSONObject choices = json.getJSONObject("choices");
            strings.languageCode = language.optString("code", strings.languageCode);
            strings.languageLabel = language.optString("label", strings.languageLabel);
            strings.languageTitle = languageSelection.optString("title", strings.languageTitle);
            strings.languagePrompt = languageSelection.optString("prompt", strings.languagePrompt);
            strings.englishLanguage = languageSelection.optString("english", strings.englishLanguage);
            strings.germanLanguage = languageSelection.optString("german", strings.germanLanguage);
            strings.demographicsTitle = demographics.optString("title", strings.demographicsTitle);
            strings.nameLabel = demographics.optString("nameLabel", strings.nameLabel);
            strings.ageLabel = demographics.optString("ageLabel", strings.ageLabel);
            strings.genderLabel = demographics.optString("genderLabel", strings.genderLabel);
            strings.handednessLabel = demographics.optString("handednessLabel", strings.handednessLabel);
            strings.consentText = demographics.optString("consentText", strings.consentText);
            strings.signatureLabel = demographics.optString("signatureLabel", strings.signatureLabel);
            strings.maiaTitle = maia.optString("title", strings.maiaTitle);
            strings.maiaInstructions = maia.optString("instructions", strings.maiaInstructions);
            strings.maiaLeftAnchor = maia.optString("leftAnchor", strings.maiaLeftAnchor);
            strings.maiaRightAnchor = maia.optString("rightAnchor", strings.maiaRightAnchor);
            strings.spatialTitle = spatial.optString("title", strings.spatialTitle);
            strings.spatialInstructions = spatial.optString("instructions", strings.spatialInstructions);
            strings.spatialChoicePrompt = spatial.optString("choicePrompt", strings.spatialChoicePrompt);
            strings.genderChoices = stringMap(choices.getJSONObject("gender"));
            strings.handednessChoices = stringMap(choices.getJSONObject("handedness"));
            return strings;
        }

        static LocalizedStrings defaults(String languageCode) {
            LocalizedStrings strings = new LocalizedStrings();
            strings.languageCode = languageCode;
            strings.genderChoices.put("female", "Female");
            strings.genderChoices.put("male", "Male");
            strings.genderChoices.put("non_binary_or_diverse", "Non-binary / diverse");
            strings.genderChoices.put("prefer_not_to_say", "Prefer not to say");
            strings.handednessChoices.put("left", "Left-handed");
            strings.handednessChoices.put("right", "Right-handed");
            strings.handednessChoices.put("ambidextrous", "Ambidextrous");
            strings.handednessChoices.put("prefer_not_to_say", "Prefer not to say");
            return strings;
        }
    }

    private static final class QuestionnaireContent {
        final Map<String, LocalizedStrings> stringsByLanguage = new LinkedHashMap<>();
        final Map<String, List<MaiaItem>> maiaItemsByLanguage = new LinkedHashMap<>();
        final List<String> spatialChoices = new ArrayList<>();
        final List<Integer> reverseScoredItems = new ArrayList<>();
        final List<Subscale> subscales = new ArrayList<>();

        LocalizedStrings strings(String language) {
            LocalizedStrings strings = stringsByLanguage.get(language);
            return strings == null ? stringsByLanguage.get("en") : strings;
        }

        List<MaiaItem> maiaItems(String language) {
            List<MaiaItem> items = maiaItemsByLanguage.get(language);
            return items == null ? maiaItemsByLanguage.get("en") : items;
        }

        JSONObject languageJson(String language) throws Exception {
            LocalizedStrings strings = strings(language);
            return new JSONObject()
                .put("code", strings.languageCode)
                .put("label", strings.languageLabel);
        }

        static QuestionnaireContent load(Activity activity) {
            QuestionnaireContent content = new QuestionnaireContent();
            try {
                content.stringsByLanguage.put("en",
                    LocalizedStrings.fromJson(assetJson(activity, "content/i18n/en.json")));
                content.stringsByLanguage.put("de",
                    LocalizedStrings.fromJson(assetJson(activity, "content/i18n/de.json")));
                content.maiaItemsByLanguage.put("en",
                    maiaItemsFromJson(assetJson(activity, "content/maia2/en.json")));
                content.maiaItemsByLanguage.put("de",
                    maiaItemsFromJson(assetJson(activity, "content/maia2/de.json")));
                JSONObject spatial = assetJson(activity, "content/spatial-frame-reference-pictograph.json");
                JSONArray choices = spatial.getJSONArray("choices");
                for (int i = 0; i < choices.length(); i++) {
                    content.spatialChoices.add(choices.getString(i));
                }
                JSONObject scoring = assetJson(activity, "content/maia2/scoring.json");
                JSONArray reverse = scoring.getJSONArray("reverseScoredItems");
                for (int i = 0; i < reverse.length(); i++) {
                    content.reverseScoredItems.add(Integer.valueOf(reverse.getInt(i)));
                }
                JSONArray subscales = scoring.getJSONArray("subscales");
                for (int i = 0; i < subscales.length(); i++) {
                    JSONObject subscale = subscales.getJSONObject(i);
                    JSONArray items = subscale.getJSONArray("items");
                    List<Integer> itemIds = new ArrayList<>();
                    for (int j = 0; j < items.length(); j++) {
                        itemIds.add(Integer.valueOf(items.getInt(j)));
                    }
                    content.subscales.add(new Subscale(subscale.getString("id"), itemIds));
                }
            } catch (Exception ignored) {
                content.installFallbacks();
            }
            content.ensureDefaults();
            return content;
        }

        private void installFallbacks() {
            stringsByLanguage.put("en", LocalizedStrings.defaults("en"));
            stringsByLanguage.put("de", LocalizedStrings.defaults("de"));
            List<MaiaItem> fallbackItems = new ArrayList<>();
            for (int i = 1; i <= 37; i++) {
                fallbackItems.add(new MaiaItem(i, "MAIA-2 item " + i));
            }
            maiaItemsByLanguage.put("en", fallbackItems);
            maiaItemsByLanguage.put("de", fallbackItems);
            spatialChoices.addAll(Arrays.asList("A", "B", "C", "D", "E", "F", "G", "H"));
            reverseScoredItems.addAll(Arrays.asList(5, 6, 7, 8, 9, 10, 11, 12, 15));
            subscales.add(new Subscale("noticing", Arrays.asList(1, 2, 3, 4)));
            subscales.add(new Subscale("not_distracting", Arrays.asList(5, 6, 7, 8, 9, 10)));
            subscales.add(new Subscale("not_worrying", Arrays.asList(11, 12, 13, 14, 15)));
            subscales.add(new Subscale("attention_regulation", Arrays.asList(16, 17, 18, 19, 20, 21, 22)));
            subscales.add(new Subscale("emotional_awareness", Arrays.asList(23, 24, 25, 26, 27)));
            subscales.add(new Subscale("self_regulation", Arrays.asList(28, 29, 30, 31)));
            subscales.add(new Subscale("body_listening", Arrays.asList(32, 33, 34)));
            subscales.add(new Subscale("trusting", Arrays.asList(35, 36, 37)));
        }

        private void ensureDefaults() {
            if (!stringsByLanguage.containsKey("en")) {
                stringsByLanguage.put("en", LocalizedStrings.defaults("en"));
            }
            if (!maiaItemsByLanguage.containsKey("en") || maiaItemsByLanguage.get("en").size() != 37) {
                List<MaiaItem> fallbackItems = new ArrayList<>();
                for (int i = 1; i <= 37; i++) {
                    fallbackItems.add(new MaiaItem(i, "MAIA-2 item " + i));
                }
                maiaItemsByLanguage.put("en", fallbackItems);
            }
            if (spatialChoices.isEmpty()) {
                spatialChoices.addAll(Arrays.asList("A", "B", "C", "D", "E", "F", "G", "H"));
            }
        }

        private static JSONObject assetJson(Activity activity, String relativePath) throws Exception {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                activity.getAssets().open(ASSET_ROOT + "/" + relativePath),
                StandardCharsets.UTF_8
            ));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            reader.close();
            return new JSONObject(builder.toString());
        }

        private static List<MaiaItem> maiaItemsFromJson(JSONObject json) throws Exception {
            List<MaiaItem> items = new ArrayList<>();
            JSONArray array = json.getJSONArray("items");
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                items.add(new MaiaItem(item.getInt("id"), item.getString("text")));
            }
            return items;
        }
    }

    private static Map<String, String> stringMap(JSONObject json) throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        JSONArray names = json.names();
        if (names == null) {
            return map;
        }
        for (int i = 0; i < names.length(); i++) {
            String key = names.getString(i);
            map.put(key, json.getString(key));
        }
        return map;
    }
}
