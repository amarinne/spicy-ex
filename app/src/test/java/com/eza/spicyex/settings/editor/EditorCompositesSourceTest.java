package com.eza.spicyex.settings.editor;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * Source-level contracts for the hand renderers of the few composite entries: they are the
 * rows the generic renderer cannot express, so their body is what gives them behaviour.
 * The editor session renders every other row from the control registry.
 */
public class EditorCompositesSourceTest {
    private static final String COMPOSITES =
            "src/main/java/com/eza/spicyex/settings/editor/EditorComposites.java";
    private static final String ROWS =
            "src/main/java/com/eza/spicyex/settings/editor/EditorRows.java";
    private static final String PANEL_DIALOG =
            "src/main/java/com/eza/spicyex/ui/PanelDialog.java";

    @Test public void fontChipsAttachTheirRowToTheFontCard() throws Exception {
        // EditorRows builds real Android views, so a JVM render cannot run against the SDK stub jar.
        // Pin the one step that orphaning the row silently removes: the built row is added back.
        String fontBuilder = method(composites(), "fontChips");
        assertTrue(fontBuilder, fontBuilder.contains("rows.addOption(row, rows.matchWrap("));
        String chooser = method(composites(), "customFontChooser");
        assertTrue(chooser, chooser.contains("rows.addOption(button, rows.matchWrap("));
    }

    /** The blur control is one composite row: its hint and its slider both reach the card, and
     *  the slider writes the one blur strength, whose percent it shows. */
    @Test public void distantLineBlurKeepsItsHintItsSliderAndItsContinuousWrite() throws Exception {
        String blur = method(composites(), "distantLineBlur");
        assertTrue(blur, blur.contains("rows.addOption(rows.text("));
        assertTrue(blur, blur.contains("rows.addOption(rows.dragRow("));
        assertTrue(blur, blur.contains("BlurStrength.percent("));
        assertTrue(blur, blur.contains("rows.put(Settings.LYRICS_BLUR_STRENGTH, value)"));
    }

    @Test public void fontChipsAndModeSlidersKeepTheirChoicesAndTheirWrite() throws Exception {
        String fontBuilder = method(composites(), "fontChips");
        // The composite creates Android widgets; pin the behavior represented by its recorder stub.
        assertTrue(fontBuilder, fontBuilder.contains("spec.choices.toArray(new String[0])"));
        assertTrue(fontBuilder, fontBuilder.contains("host.chooseFont(chip)"));
        String modeBuilder = method(rows(), "customSliderRow");
        assertTrue(modeBuilder, modeBuilder.contains("writer.put(modeSetting, customValue)"));
    }

    @Test public void randomPoolUsesSwitchRowsAndKeepsTheLastSelection() throws Exception {
        String pool = method(composites(), "showRandomPool");
        assertTrue(pool, pool.contains("dialog.switchChoice("));
        assertFalse(pool, pool.contains("dialog.checkbox("));
        // The rendered switch row keeps the nonempty guard and runs its own click owner.
        assertTrue(pool, pool.contains("next -> next || working.size() > 1"));
        assertTrue(pool, pool.contains("choice.view().performClick();"));
        assertTrue(pool, pool.contains("TextView done = dialog.primaryButton("));
        assertTrue(pool, pool.contains("TextView cancel = dialog.secondaryButton("));
        assertTrue(pool, pool.contains("done.performClick();"));
        assertTrue(pool, pool.contains("cancel.performClick();"));
        assertTrue(pool, pool.contains("query[0] = argument == null ? \"\" : argument;"));
        assertTrue(pool, pool.contains("dialog.onDismiss(() -> rows.clearRandomPoolAgent(agent));"));
    }

    @Test public void randomPoolOpenerIsTaggedOnTheChooseChip() throws Exception {
        String randomRow = method(composites(), "backgroundRandomEffects");
        assertTrue(randomRow, randomRow.contains("choose.setTag(EditorRows.RANDOM_POOL_OPEN_TAG);"));
        assertTrue(randomRow, randomRow.contains("choose.setOnClickListener("));
    }

    @Test public void switchRowsAreCheckableAndMeetTheMinimumTouchTarget() throws Exception {
        String dialog = read(PANEL_DIALOG);
        assertTrue(dialog, dialog.contains("info.setClassName(\"android.widget.Switch\")"));
        assertTrue(dialog, dialog.contains("info.setCheckable(true)"));
        assertTrue(dialog, dialog.contains("info.setChecked(SwitchChoice.this.checked)"));
        assertTrue(dialog, dialog.contains("row.setMinimumHeight(dp(48))"));
        assertTrue(dialog, dialog.contains("row.setOnClickListener(v -> choice.toggle())"));
        assertTrue(dialog, dialog.contains("GlossyToggle toggle = new GlossyToggle(context);"));
    }

    @Test public void statusKeepsPendingChoicesSeparateFromThePersistedPool() {
        assertEquals("open=true query=mesh pending=kawarp,god-rays persisted=kawarp,mesh-gradient shown=2",
                EditorRows.randomPoolStatus(true, "mesh", "kawarp,god-rays",
                        "kawarp,mesh-gradient", 2));
        assertEquals("open=false query=- pending=none persisted=kawarp shown=0",
                EditorRows.randomPoolStatus(false, "", "none", "kawarp", 0));
    }

    private static String composites() throws Exception {
        return read(COMPOSITES);
    }

    private static String rows() throws Exception {
        return read(ROWS);
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /** The body of one production method, with comments and literals masked out. */
    static String method(String source, String name) {
        Matcher declaration = Pattern.compile(
                "(?:private|protected|public)(?: static)? (?:void|LinearLayout|int) " + name + "\\(").matcher(source);
        if (!declaration.find()) throw new AssertionError("Missing production builder: " + name);
        int start = declaration.start();
        // Replace each token with spaces instead of deleting it; braces inside hints cannot end a method.
        char[] code = source.toCharArray();
        Matcher tokens = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\r\\n]*|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'")
                .matcher(source);
        while (tokens.find()) Arrays.fill(code, tokens.start(), tokens.end(), ' ');
        int brace = source.indexOf('{', declaration.end());
        int depth = 0;
        for (int i = brace; i < code.length; i++) {
            if (code[i] == '{') depth++;
            if (code[i] == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("Unbalanced production builder: " + name);
    }
}
