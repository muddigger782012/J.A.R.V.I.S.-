import os
import secrets
from typing import Literal
from assistant_adapters import assistant_reply
from weather import is_weather_question, forecast
from fastapi import FastAPI, HTTPException, Header
from pydantic import BaseModel, Field
from openai import AsyncOpenAI
from lesson_protocol import learning_instructions, parse_learning_reply

app = FastAPI(title="Jarvis Conversation Gateway")


class Message(BaseModel):
    role: str
    content: str

class ChatRequest(BaseModel):
    session_id: str = Field(max_length=128)
    messages: list[Message] = Field(min_length=1, max_length=50)
    learn_intent: bool = False
    provider: Literal["default", "mycroft"] = "default"
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)

@app.get("/health")
def health():
    return {"ok": True}

@app.post("/chat")
async def chat(req: ChatRequest, authorization: str | None = Header(default=None)):
    # Required shared token; do not expose a production service publicly without per-user auth.
    expected = os.environ.get("JARVIS_GATEWAY_TOKEN")
    if not expected or not secrets.compare_digest(authorization or "", f"Bearer {expected}"):
        raise HTTPException(status_code=401, detail="Unauthorized")
    question = next((m.content for m in reversed(req.messages) if m.role == "user"), "")
    if req.provider != "default":
        return await assistant_reply(req.provider, question[:12000])
    if is_weather_question(question):
        if req.latitude is None or req.longitude is None:
            return {"reply": "Set your weather latitude and longitude in J.A.R.V.I.S. gateway settings, then ask again.", "lesson": None}
        try:
            return await forecast(question, req.latitude, req.longitude)
        except Exception:
            raise HTTPException(status_code=502, detail="Live weather service unavailable; try again shortly")
    if not os.environ.get("OPENAI_API_KEY"):
        return {"reply": "The gateway is online, but general AI is not configured. Weather requests are available; the server needs an AI provider key for other questions.", "lesson": None}
    msgs = [{"role": m.role, "content": m.content[:12000]} for m in req.messages if m.role in ("user", "assistant")]
    if not msgs:
        raise HTTPException(status_code=400, detail="No valid messages")
    try:
        instructions = learning_instructions() if req.learn_intent else "You are Jarvis, a helpful assistant. Never claim to have executed device actions. Device actions require explicit separate user approval."
        async with AsyncOpenAI(api_key=os.environ["OPENAI_API_KEY"], timeout=20.0, max_retries=0) as client:
            response = await client.responses.create(model=os.environ.get("OPENAI_MODEL", "gpt-4.1-mini"), instructions=instructions, input=msgs)
        if req.learn_intent:
            structured = parse_learning_reply(response.output_text)
            if structured is not None:
                return structured.model_dump()
            return {"reply": response.output_text, "lesson": None}
        return {"reply": response.output_text}
    except Exception:
        raise HTTPException(status_code=502, detail="AI provider error")
