# Local spoken timer durations

Direct timer requests, explicit teaching, saved capability-request review/trials and pending timer edits share a strict English duration parser. Examples: five minutes; twenty-five minutes; one hour and thirty minutes; 1 hour 30 minutes 5 seconds. English integer words through thousands are accepted, alongside digits. Total durations remain bounded to 1 second–24 hours.

Units must appear once, in descending hour/minute/second order. Negatives, decimals, fractions, malformed number words, alternative choices and additional actions are not resolved by this parser. Direct timer matching now covers the entire raw request rather than extracting a numeric timer fragment from negated or compound wording. Other assistant paths may interpret unmatched wording separately; this parser grants no execution authority.

Explicit teaching, capability trials and proposal edits keep their existing approval/outcome gates. An edit cannot change an already requested timer; it cannot renew the proposal expiry or teach an alias without outcome confirmation. This is command understanding, separate from speech recognition and acoustic training.

Unit coverage checks equivalent spoken/digit/compound durations, boundaries, rejection of mixed/negated wording, teaching resolution, request trial proposals, and preservation of approval/outcome state. Phone verification should include teaching “work break” as a ten-minute timer, editing to five minutes, approval, checking Clock, and reporting the outcome.
