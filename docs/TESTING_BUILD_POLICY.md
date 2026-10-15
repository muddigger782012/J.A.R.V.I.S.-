# Testing and build policy

During implementation, run the smallest relevant test first. Before merging, run all applicable CI checks. Never disable required tests or claim success from a skipped job.

| Change | Minimum targeted validation | Release consideration |
| --- | --- | --- |
| Core logic | Relevant unit tests | Broader Android CI |
| Themes, menus, dialogs | python3 scripts/check_ui_theme.py and UI tests | Emulator smoke, screenshots where applicable |
| Cloud backend | python -m unittest discover -s cloud-backend -p 'test_*.py' | Auth, failure and cost controls |
| Wake word, STT, TTS | Relevant voice checks | Emulator and physical microphone/speaker tests |
| Updater, signing, packages | Package/signature/version/update-feed checks | Clean install and in-place upgrade preserving data |

On main, the UI smoke job was previously disabled; PR #78 proposes restoring it. Treat this as an unresolved release gate until a passing run is verified.

Use CI caches and existing artifacts where safe. Do not repeat unchanged builds without a reason. Diagnose failures using logs and artifacts before retrying. Report physical-device testing separately from CI.

Jarvis personal, Beta and Keyboard must retain their independent package identity, signing and update compatibility.
