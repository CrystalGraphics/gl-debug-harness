package io.github.somehussar.crystalgraphics.harness.debug;

import com.crystalgui.core.input.FocusManager;
import com.crystalgui.core.input.UiInputManager;
import com.crystalgui.ui.UIContainer;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UiTextbox;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Lightweight diagnostic state tracker for harness-side UI validation.
 * Records outcomes of injected interactions so the harness can assert
 * expected behavior without relying solely on log output.
 */
public final class UiValidationState {

    private static final Logger LOGGER = Logger.getLogger(UiValidationState.class.getName());

    private final UIContainer container;
    private final List<String> clickedButtonIds = new ArrayList<String>();
    private final List<String> focusTransitions = new ArrayList<String>();
    private final List<String> submittedTextboxIds = new ArrayList<String>();
    private int textChangedCount;
    private int failCount;

    public UiValidationState(UIContainer container) {
        this.container = container;
    }

    public void recordButtonClick(String buttonId) {
        clickedButtonIds.add(buttonId);
    }

    public List<String> getClickedButtonIds() {
        return clickedButtonIds;
    }

    public void recordFocusTransition(String elementId) {
        focusTransitions.add(elementId);
    }

    public List<String> getFocusTransitions() {
        return focusTransitions;
    }

    public String getHoveredElementId() {
        UiInputManager mgr = container.getInputManager();
        if (mgr == null) return null;
        UIElement hovered = mgr.getHoveredElement();
        return hovered != null ? hovered.getId() : null;
    }

    public String getFocusedElementId() {
        FocusManager fm = container.getFocusManager();
        if (fm == null) return null;
        UIElement focused = fm.getFocusedElement();
        return focused != null ? focused.getId() : null;
    }

    public void assertHovered(String expectedId) {
        String actual = getHoveredElementId();
        if (expectedId == null ? actual == null : expectedId.equals(actual)) {
            LOGGER.info("[UiValidation] PASS: hovered=" + actual);
        } else {
            LOGGER.severe("[UiValidation] FAIL: expected hovered=" + expectedId + " but got=" + actual);
            failCount++;
        }
    }

    public void assertFocused(String expectedId) {
        String actual = getFocusedElementId();
        if (expectedId == null ? actual == null : expectedId.equals(actual)) {
            LOGGER.info("[UiValidation] PASS: focused=" + actual);
        } else {
            LOGGER.severe("[UiValidation] FAIL: expected focused=" + expectedId + " but got=" + actual);
            failCount++;
        }
    }

    public void assertClickedContains(String buttonId) {
        if (clickedButtonIds.contains(buttonId)) {
            LOGGER.info("[UiValidation] PASS: button '" + buttonId + "' was clicked");
        } else {
            LOGGER.severe("[UiValidation] FAIL: button '" + buttonId + "' was NOT clicked. Clicked: " + clickedButtonIds);
            failCount++;
        }
    }

    public void assertTextboxContent(String textboxId, String expectedText) {
        UIElement el = container.getRoot().findById(textboxId);
        if (!(el instanceof UiTextbox)) {
            LOGGER.severe("[UiValidation] FAIL: '" + textboxId + "' not found or not a UiTextbox");
            failCount++;
            return;
        }
        String actual = ((UiTextbox) el).getText();
        if (expectedText.equals(actual)) {
            LOGGER.info("[UiValidation] PASS: textbox '" + textboxId + "' text=\"" + actual + "\"");
        } else {
            LOGGER.severe("[UiValidation] FAIL: textbox '" + textboxId + "' expected=\"" + expectedText + "\" actual=\"" + actual + "\"");
            failCount++;
        }
    }

    /**
     * Asserts that the textbox with the given ID has the expected caret position.
     */
    public void assertTextboxCaretPosition(String textboxId, int expectedPos) {
        UIElement el = container.getRoot().findById(textboxId);
        if (!(el instanceof UiTextbox)) {
            LOGGER.severe("[UiValidation] FAIL: '" + textboxId + "' not found or not a UiTextbox");
            failCount++;
            return;
        }
        int actual = ((UiTextbox) el).getCaretPosition();
        if (actual == expectedPos) {
            LOGGER.info("[UiValidation] PASS: textbox '" + textboxId + "' caretPosition=" + actual);
        } else {
            LOGGER.severe("[UiValidation] FAIL: textbox '" + textboxId + "' expected caretPosition=" + expectedPos + " actual=" + actual);
            failCount++;
        }
    }

    /**
     * Records that a textbox's submitted signal fired.
     */
    public void recordTextboxSubmit(String textboxId) {
        submittedTextboxIds.add(textboxId);
    }

    /**
     * Asserts that the submitted signal was recorded for the given textbox.
     */
    public void assertTextboxSubmitted(String textboxId) {
        if (submittedTextboxIds.contains(textboxId)) {
            LOGGER.info("[UiValidation] PASS: textbox '" + textboxId + "' submitted signal fired");
        } else {
            LOGGER.severe("[UiValidation] FAIL: textbox '" + textboxId + "' submitted signal NOT fired. Submitted: " + submittedTextboxIds);
            failCount++;
        }
    }

    /**
     * Increments the textChanged counter (wire to textChanged signal).
     */
    public void recordTextChanged() {
        textChangedCount++;
    }

    /**
     * Asserts the textChanged signal has fired at least the expected number of times.
     */
    public void assertTextChangedCountAtLeast(int expectedMin) {
        if (textChangedCount >= expectedMin) {
            LOGGER.info("[UiValidation] PASS: textChanged fired " + textChangedCount + " times (expected >=" + expectedMin + ")");
        } else {
            LOGGER.severe("[UiValidation] FAIL: textChanged fired " + textChangedCount + " times (expected >=" + expectedMin + ")");
            failCount++;
        }
    }

    public int getFailCount() {
        return failCount;
    }

    public void logSummary() {
        LOGGER.info("[UiValidation] === VALIDATION SUMMARY ===");
        LOGGER.info("[UiValidation] Clicked buttons: " + clickedButtonIds);
        LOGGER.info("[UiValidation] Focus transitions: " + focusTransitions);
        LOGGER.info("[UiValidation] Submitted textboxes: " + submittedTextboxIds);
        LOGGER.info("[UiValidation] textChanged signal count: " + textChangedCount);
        LOGGER.info("[UiValidation] Failures: " + failCount);
        if (failCount == 0) {
            LOGGER.info("[UiValidation] ALL ASSERTIONS PASSED — Failures: 0");
        } else {
            LOGGER.severe("[UiValidation] " + failCount + " ASSERTION(S) FAILED — harness will exit with code 2");
        }
    }

    /**
     * Throws if any assertions failed, causing the harness process to exit
     * with a non-zero code via the catch block in {@code FontDebugHarnessMain}.
     *
     * <p>The exception propagates up through {@code TaskScheduler.tick()} →
     * {@code InteractiveSceneRunner.run()} → {@code FontDebugHarnessMain.main()}
     * where it is caught and results in {@code System.exit(2)}.</p>
     *
     * <p>Call after {@link #logSummary()} to surface failures as a hard exit.</p>
     */
    public void throwIfFailed() {
        if (failCount > 0) {
            throw new RuntimeException("[UiValidation] " + failCount
                    + " UI validation assertion(s) failed — see log output above");
        }
    }
}
