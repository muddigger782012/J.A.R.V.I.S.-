"""Live US forecasts; never substitute model memory for weather data."""
import re
from datetime import datetime, timezone
from urllib.parse import urlparse
import httpx


def is_weather_question(text):
    return bool(re.search(r'\b(weather|forecast|rain|raining|snow|temperature)\b', text, re.I))


def summarize(periods, question, location, now=None):
    now = now or datetime.now(timezone.utc)
    upcoming = [p for p in periods if datetime.fromisoformat(p['endTime']) > now]
    if not upcoming:
        raise ValueError('No upcoming forecast periods')
    if re.search(r'\b(rain|raining)\b', question, re.I):
        wet = next((p for p in upcoming if re.search(r'\b(rain|showers|thunderstorms|drizzle)\b', p.get('shortForecast', ''), re.I)), None)
        if wet is None:
            return f'For {location}, rain is not mentioned in the available forecast through {upcoming[-1]["name"]}. Forecasts can change.'
        start = datetime.fromisoformat(wet['startTime'])
        chance = (wet.get('probabilityOfPrecipitation') or {}).get('value')
        probability = f' Precipitation chance: {chance}%.' if chance is not None else ''
        return f'For {location}, the next forecast period mentioning rain is {wet["name"]}, {start.strftime("%A, %B %d")}: {wet["detailedForecast"]}{probability} Source: National Weather Service.'
    return f'For {location}: ' + ' '.join(f'{p["name"]}: {p["detailedForecast"]}' for p in upcoming[:4]) + ' Source: National Weather Service.'


async def forecast(question, latitude, longitude):
    async with httpx.AsyncClient(timeout=10, headers={'User-Agent': 'JARVIS/1.0 (https://github.com/muddigger782012/J.A.R.V.I.S.-)', 'Accept': 'application/geo+json'}) as client:
        point = await client.get(f'https://api.weather.gov/points/{latitude:.4f},{longitude:.4f}')
        point.raise_for_status()
        props = point.json()['properties']
        url = props['forecast']
        parsed = urlparse(url)
        if parsed.scheme != 'https' or parsed.hostname != 'api.weather.gov':
            raise ValueError('Invalid forecast URL')
        data = await client.get(url)
        data.raise_for_status()
        place = props['relativeLocation']['properties']
        reply = summarize(data.json()['properties']['periods'], question, f'{place["city"]}, {place["state"]}')
        return {'reply': reply, 'lesson': None, 'source_url': url}
