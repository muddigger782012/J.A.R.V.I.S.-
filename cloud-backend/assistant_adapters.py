"""Optional, explicitly selected providers. Credentials stay on the gateway."""
import asyncio
import json
import os
import uuid


class AdapterUnavailable(Exception):
    pass


def correlated_speech(event: dict, request_id: str) -> str | None:
    context = event.get('context') or {}
    destinations = context.get('destination', [])
    if isinstance(destinations, str):
        destinations = [destinations]
    # Do not relay unrelated conversations from a shared message bus.
    correlated = context.get('jarvis_request_id') == request_id or request_id in destinations
    if event.get('type') == 'speak' and correlated:
        utterance = (event.get('data') or {}).get('utterance')
        if isinstance(utterance, str) and utterance.strip():
            return utterance.strip()
    return None


async def mycroft(text: str) -> str:
    url = os.getenv('MYCROFT_BUS_URL', '')
    if not url.startswith(('ws://', 'wss://')):
        raise AdapterUnavailable('Mycroft needs a running message bus and MYCROFT_BUS_URL on the server.')
    try:
        from websockets.asyncio.client import connect
    except ImportError:
        raise AdapterUnavailable('Install the optional Mycroft server dependencies.')
    request_id = 'jarvis-' + uuid.uuid4().hex
    async with asyncio.timeout(20):
        async with connect(url, open_timeout=5, max_size=1_000_000) as bus:
            await bus.send(json.dumps({
                'type': 'recognizer_loop:utterance',
                'data': {'utterances': [text], 'lang': os.getenv('MYCROFT_LANGUAGE', 'en-us')},
                'context': {'source': request_id, 'jarvis_request_id': request_id},
            }))
            async for raw in bus:
                event = json.loads(raw)
                answer = correlated_speech(event, request_id)
                if answer:
                    return answer
    raise AdapterUnavailable('Mycroft returned no correlated response. Check the bus and installed skills.')


async def assistant_reply(provider: str, text: str) -> dict:
    try:
        if provider == 'mycroft':
            answer = await mycroft(text)
        else:
            raise AdapterUnavailable('Unknown assistant provider.')
        return {'reply': answer, 'lesson': None}
    except AdapterUnavailable as error:
        return {'reply': str(error), 'lesson': None}
    except TimeoutError:
        return {'reply': 'The assistant timed out. It may have received your command; check the device before retrying.', 'lesson': None}
    except Exception:
        # Never return upstream exceptions, which may contain credentials.
        return {'reply': 'The assistant connection failed. Check the server credentials and provider access.', 'lesson': None}
