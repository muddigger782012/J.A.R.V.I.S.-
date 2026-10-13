# Submitted composer reset

Typed sends now snapshot the command and clear the composer before dispatch, including live-chat controls and early return paths. Attachment sends use the same clear helper after analysis configuration has been accepted; blocked sends keep their draft. The helper removes composing spans, clears the editable buffer, resets its cursor and restarts the focused input connection so the keyboard sees an empty draft. Asynchronous replies do not touch the composer, preserving the next message typed while waiting.

Submitted voice transcripts appear in conversation history rather than replacing the typing draft. Low-confidence voice clarification remains in the conversation. No appearance theme or button layout changes are introduced.

Phone check: send a typed message with the arrow and another with the keyboard Send action. Both should immediately leave an empty composer with its placeholder. Type a new draft while awaiting an online reply; the reply must preserve that draft. A voice command must not overwrite it. Test attachment configuration rejection retains the draft. UI/keyboard behavior awaits phone verification.

3.5.380 malformed capability-management routing, cancellation, fresh approval, timer execution and reported-success memory were phone-confirmed before this change.
