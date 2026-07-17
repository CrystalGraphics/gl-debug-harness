package io.github.somehussar.crystalgraphics.harness;

public interface InputProcessing {

    @FunctionalInterface
    interface Mouse {

        record Event(int x, int y, int dx, int dy, int button, boolean state, int wheelDelta, long nanos) {}

        /**
         * Process event
         * @param event event to be processed
         * @return should event propagate
         */
        boolean processEvent(InputProcessing.Mouse.Event event);

    }

    @FunctionalInterface
    interface Keyboard {

        record Event(int character, int key, boolean pressed, boolean repeat, long nanos) {}

        /**
         * Process event
         * @param event event to be processed
         * @return should event propagate
         */
        boolean processEvent(InputProcessing.Keyboard.Event event);

    }
}
