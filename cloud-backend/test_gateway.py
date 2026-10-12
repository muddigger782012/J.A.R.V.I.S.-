import os
import unittest
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch
from types import SimpleNamespace
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

    def test_personality_controls_reach_provider_without_changing_lesson_schema(self):
        self.body['messages'][0]['content'] = 'Explain diesel injection'
        self.body['behavior_profile'] = {'address': 'Brandon', 'detail': 'brief', 'humor': False, 'initiative': False}
        create = AsyncMock(return_value=SimpleNamespace(output_text='An explanation.'))
        manager = MagicMock()
        manager.__aenter__ = AsyncMock(return_value=SimpleNamespace(responses=SimpleNamespace(create=create)))
        manager.__aexit__ = AsyncMock(return_value=False)
        with patch.dict(os.environ, {'OPENAI_API_KEY': 'test-key'}), patch('main.AsyncOpenAI', return_value=manager):
            response = self.client.post('/chat', json=self.body, headers={'Authorization': 'Bearer test-token'})
            self.assertEqual(response.status_code, 200)
            instructions = create.call_args.kwargs['instructions']
            self.assertIn('Brandon', instructions)
            self.assertIn('Do not add jokes', instructions)
            self.assertIn('Never claim to have executed', instructions)
            self.assertEqual(create.call_args.kwargs['input'][-1]['content'], 'Explain diesel injection')

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

    def test_context_is_supplied_as_data_and_contextual_lesson_round_trips(self):
        self.body['messages'][0]['content'] = 'launch my usual browser'
        self.body['learn_intent'] = True
        self.body['capability_context'] = {'preferred_apps': {'browser': 'Chrome'}}
        create = AsyncMock(return_value=SimpleNamespace(output_text='{"reply":"Please confirm", "lesson":{"intent":"OPEN_PREFERRED_APP", "confidence":0.95,"entities":{"slot":"browser"},"learnable":true}}'))
        manager = MagicMock()
        manager.__aenter__ = AsyncMock(return_value=SimpleNamespace(responses=SimpleNamespace(create=create)))
        manager.__aexit__ = AsyncMock(return_value=False)
        with patch.dict(os.environ, {'OPENAI_API_KEY': 'test-key'}), patch('main.AsyncOpenAI', return_value=manager):
            response = self.client.post('/chat', json=self.body, headers={'Authorization': 'Bearer test-token'})
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()['lesson']['intent'], 'OPEN_PREFERRED_APP')
            instructions = create.call_args.kwargs['instructions']
            self.assertIn('untrusted data', instructions)
            self.assertIn('Chrome', instructions)
            self.assertIn('REPEAT_ACTION', instructions)

    def test_preference_proposal_does_not_require_existing_context(self):
        self.body['messages'][0]['content'] = 'Chrome should be my go-to browser'
        self.body['learn_intent'] = True
        create = AsyncMock(return_value=SimpleNamespace(output_text='{"reply":"Please confirm", "lesson":{"intent":"SET_APP_PREFERENCE", "confidence":0.95,"entities":{"slot":"browser","app_name":"Chrome"},"learnable":true}}'))
        manager = MagicMock()
        manager.__aenter__ = AsyncMock(return_value=SimpleNamespace(responses=SimpleNamespace(create=create)))
        manager.__aexit__ = AsyncMock(return_value=False)
        with patch.dict(os.environ, {'OPENAI_API_KEY': 'test-key'}), patch('main.AsyncOpenAI', return_value=manager):
            response = self.client.post('/chat', json=self.body, headers={'Authorization': 'Bearer test-token'})
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()['lesson']['intent'], 'SET_APP_PREFERENCE')
            instructions = create.call_args.kwargs['instructions']
            self.assertIn('An existing preference is not required', instructions)
            self.assertIn('durable save/readback', instructions)
            self.assertIn('Android system defaults', instructions)

    def test_routine_proposal_is_structured_for_host_review(self):
        self.body['messages'][0]['content'] = 'Create a routine called quiet time that lowers media volume'
        self.body['learn_intent'] = True
        create = AsyncMock(return_value=SimpleNamespace(output_text='{"reply":"Review first", "lesson":{"intent":"CREATE_ROUTINE","confidence":0.95,"entities":{"phrase":"quiet time","steps":"volume_down"},"learnable":true}}'))
        manager = MagicMock()
        manager.__aenter__ = AsyncMock(return_value=SimpleNamespace(responses=SimpleNamespace(create=create)))
        manager.__aexit__ = AsyncMock(return_value=False)
        with patch.dict(os.environ, {'OPENAI_API_KEY': 'test-key'}), patch('main.AsyncOpenAI', return_value=manager):
            response = self.client.post('/chat', json=self.body, headers={'Authorization': 'Bearer test-token'})
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()['lesson']['entities'], {'phrase': 'quiet time', 'steps': 'volume_down'})
            self.assertIn('confirm routine', create.call_args.kwargs['instructions'])
            self.assertIn('saving does not run', create.call_args.kwargs['instructions'])

    def test_oversized_context_is_rejected_before_provider_request(self):
        self.body['messages'][0]['content'] = 'launch my usual browser'
        self.body['learn_intent'] = True
        self.body['capability_context'] = {'label': 'x' * 4097}
        with patch.dict(os.environ, {'OPENAI_API_KEY': 'test-key'}), patch('main.AsyncOpenAI') as provider:
            response = self.client.post('/chat', json=self.body, headers={'Authorization': 'Bearer test-token'})
            self.assertEqual(response.status_code, 400)
            provider.assert_not_called()

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
