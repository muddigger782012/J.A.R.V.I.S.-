from pydantic import BaseModel, Field

ALLOWED_INTENTS = {
    "GENERAL_QUESTION",
    "WEATHER_CURRENT",
    "WEATHER_FORECAST",
    "CALL_CONTACT",
    "SEND_MESSAGE",
    "OPEN_APP",
    "NAVIGATE",
    "SET_ALARM",
    "SET_TIMER",
    "CREATE_REMINDER",
    "MEDIA_CONTROL",
    "DEVICE_SETTING",
}

class IntentLesson(BaseModel):
    intent: str = Field(min_length=2, max_length=64)
    confidence: float = Field(ge=0.0, le=1.0)
    entities: dict[str, str] = Field(default_factory=dict)
    answer: str | None = None
    learnable: bool = False

    def approved(self) -> bool:
        return self.intent in ALLOWED_INTENTS and self.confidence >= 0.90

class LearningReply(BaseModel):
    reply: str
    lesson: IntentLesson | None = None

def learning_instructions() -> str:
    intents = ", ".join(sorted(ALLOWED_INTENTS))
    return (
        "Interpret the user's request for the JARVIS Android assistant. "
        "Return JSON with reply and lesson. lesson may be null. "
        "A lesson contains intent, confidence, entities, answer, and learnable. "
        f"Allowed intents: {intents}. "
        "Set learnable true only for reusable language-to-intent mappings. "
        "Do not treat private values, credentials, message contents, or personal facts as reusable training data. "
        "Do not claim that a device action was executed."
    )


def parse_learning_reply(raw: str) -> LearningReply | None:
    """Parse and validate the model's structured learning response."""
    try:
        return LearningReply.model_validate_json(raw)
    except Exception:
        return None
