import os
import unittest
from datetime import datetime, timezone
from unittest.mock import AsyncMock, patch
from fastapi.testclient import TestClient
from main import app
from weather import summarize

class GatewayTests(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {'JARVIS_GATEWAY_TOKEN': 'test-token', 'OPENAI_API_KEY': ''})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.client = TestClient(app)
        self.body = {'session_id': 'test', 'messages': [{'role': 'user', 'content': 'When is rain next?'}], 'latitude': 36.7, 'longitude': -76.2}

    def test_authentication_required(self):
        self.assertEqual(self.client.post('/chat', json=self.body).status_code, 401)

    def test_weather_works_without_ai_key(self):
        with patch('main.forecast', AsyncMock(return_value={'reply': 'Rain on Saturday', 'lesson': None})) as fetch:
            response = self.client.post('/chat', json=self.body, headers={'Authorization': 'Bearer test-token'})
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()['reply'], 'Rain on Saturday')
            fetch.assert_awaited_once()

    def test_weather_error_is_not_invented_answer(self):
        with patch('main.forecast', AsyncMock(side_effect=RuntimeError('upstream'))):
            response = self.client.post('/chat', json=self.body, headers={'Authorization': 'Bearer test-token'})
            self.assertEqual(response.status_code, 502)

    def test_missing_location_and_general_ai(self):
        self.body.pop('latitude')
        self.assertIn('latitude', self.client.post('/chat', json=self.body, headers={'Authorization': 'Bearer test-token'}).json()['reply'])
        self.body['messages'][0]['content'] = 'Explain engines'
        self.assertIn('general AI is not configured', self.client.post('/chat', json=self.body, headers={'Authorization': 'Bearer test-token'}).json()['reply'])

    def test_invalid_coordinates(self):
        self.body['latitude'] = 100
        self.assertEqual(self.client.post('/chat', json=self.body, headers={'Authorization': 'Bearer test-token'}).status_code, 422)

    def test_rain_ignores_expired_forecast(self):
        periods = [
            {'name': 'Friday', 'startTime': '2026-10-09T06:00:00-04:00', 'endTime': '2026-10-09T18:00:00-04:00', 'shortForecast': 'Rain', 'detailedForecast': 'Old rain'},
            {'name': 'Saturday', 'startTime': '2026-10-10T06:00:00-04:00', 'endTime': '2026-10-10T18:00:00-04:00', 'shortForecast': 'Chance Showers', 'detailedForecast': 'A chance of showers.', 'probabilityOfPrecipitation': {'value': 40}},
        ]
        reply = summarize(periods, 'When is rain next?', 'Chesapeake, VA', datetime(2026, 10, 10, tzinfo=timezone.utc))
        self.assertIn('Saturday', reply)
        self.assertIn('40%', reply)
        self.assertNotIn('Old rain', reply)

if __name__ == '__main__':
    unittest.main()
