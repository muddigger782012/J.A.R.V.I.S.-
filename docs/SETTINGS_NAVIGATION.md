# Settings and menu navigation

The main gear menu contains Settings, Intelligence, Tools and Support. Tools and Support replace the same popup content and offer an explicit Back row; Android Back or outside touch dismisses the popup. Rows use themed icons, wrapping text and at least 48 dp touch targets. The popup measures its current content within the available visible window instead of reserving a tall empty panel.

Settings is a dedicated themed category list: Voice (wake/listening, assistant voice, voice profile), Personalize (appearance), and Intelligence/connections (learning and memory, AI/weather, optional connections). Editors finish back to their caller; they do not construct new copies of the settings hub. The wake/listening screen no longer inserts unrelated navigation buttons. AI/weather uses one shared validated dialog accessible directly from both Settings and Intelligence, removing the Voice → Intelligence → Voice round trip. Existing save, provider validation and secure API-key handling are retained.

Main releases its local microphone before launching the settings hub and uses its existing activity-result reload/handoff on return. Voice commands still open their explicit voice editor. Theme/font preferences apply to rows, icons, backgrounds and scrollable content.

Automated emulator checks cover all twelve themes at enlarged app fonts, narrow row measurement, distinct routes and editor/hub close controls. This is control/layout measurement coverage, not physical tap or screenshot review. Phone check: gear → Settings → a voice editor → Back; Settings → Learning and memory → AI connection settings → Cancel → Back. Neither path should open another voice screen. Check Tools/Support Back, scrolling with large fonts and theme appearance.
