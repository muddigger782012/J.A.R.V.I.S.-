# Timer duration corrections before approval

After a timer proposal, `make that 10 minutes` or `actually, make it 600 seconds` revises only its duration. The proposal still requires yes/no approval, followed by a requested timer and explicit successful outcome before learning. Edits preserve the original expiry. Numeric durations must be 1 second through 24 hours; invalid edits keep the original proposal and explain the supported range.

Explicit teaching retains the chosen alias and consent for the revised duration. Ordinary AI proposals become non-learnable after a duration edit so an original five-minute utterance cannot accidentally be saved as a ten-minute command. Successful outcome confirmation may still update recent-action memory. Saved conflicting aliases remain protected by normal no-overwrite rules.

A previously requested action cannot be edited by this flow; no second timer is launched, the outcome remains pending, and the reply explains that running timers cannot be changed/cancelled here. App proposals and contextual repeat targets stay locked. Missing, expired and unrelated context cannot authorize an edit. Multi-action, negated and hypothetical forms do not match.

Phone check, offline: `Teach Jarvis that short break means start a timer for 5 minutes`; `make that 10 minutes`; `what am I approving`; `yes`; check for a ten-minute timer; `it worked`; `show saved commands`. The alias should mean 600 seconds. Repeat the alias offline, approve it, and check the timer. Choose an unused alias, or explicitly forget its old meaning first.

Prior 3.5.373 reminder/memory-check/corrected-outcome/repeat flow was phone-verified for Chrome, which opened on both requests. This new timer-edit milestone is phone-pending.
