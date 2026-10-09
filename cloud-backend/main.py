import os
from fastapi import FastAPI, HTTPException, Header
from pydantic import BaseModel, Field
from openai import AsyncOpenAI

app = FastAPI(title="Jarvis Conversation Gateway")
client = AsyncOpenAI(api_key=os.environ.get("OPENAI_API_KEY", ""))

class Message(BaseModel):
    role: str
    content: str

class ChatRequest(BaseModel):
    session_id: str = Field(max_length=128)
    messages: list[Message] = Field(min_length=1, max_length=50)

@app.get("/health")
def health():
    return {"ok": True}

@app.post("/chat")
async def chat(req: ChatRequest, authorization: str | None = Header(default=None)):
    # Required shared token; do not expose a production service publicly without per-user auth.
    expected = os.environ.get("JARVIS_GATEWAY_TOKEN")
    if not expected or authorization != f"Bearer {expected}":
        raise HTTPException(status_code=401, detail="Unauthorized")
    if not os.environ.get("OPENAI_API_KEY"):
        raise HTTPException(status_code=503, detail="API key not configured")
    msgs = [{"role": m.role, "content": m.content[:12000]} for m in req.messages if m.role in ("user", "assistant")]
    if not msgs:
        raise HTTPException(status_code=400, detail="No valid messages")
    try:
        response = await client.responses.create(model=os.environ.get("OPENAI_MODEL", "gpt-4.1-mini"), instructions="You are Jarvis, a helpful assistant. Never claim to have executed device actions. Device actions require explicit separate user approval.", input=msgs)
        return {"reply": response.output_text}
    except Exception:
        raise HTTPException(status_code=502, detail="AI provider error")
