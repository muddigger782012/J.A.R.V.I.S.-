import os
import unittest
from unittest.mock import AsyncMock, patch
from fastapi.testclient import TestClient
from main import app
from assistant_adapters import correlated_speech

class AssistantAdapterTests(unittest.TestCase):
    def test_explicit_providers_are_authenticated_and_not_sent_to_general_ai(self):
        body = {'session_id': 'test', 'provider': 'mycroft', 'messages': [{'role': 'user', 'content': 'turn on the lights'}]}
        with patch.dict(os.environ, {'JARVIS_GATEWAY_TOKEN': 'test-token'}), patch('main.assistant_reply', AsyncMock(return_value={'reply': 'Done', 'lesson': None})) as adapter:
            client = TestClient(app)
            self.assertEqual(client.post('/chat', json=body).status_code, 401)
            adapter.assert_not_awaited()
            response = client.post('/chat', json=body, headers={'Authorization': 'Bearer test-token'})
            self.assertEqual(response.json()['reply'], 'Done')
            adapter.assert_awaited_once_with('mycroft', 'turn on the lights')
            body['provider'] = 'untrusted'
            self.assertEqual(client.post('/chat', json=body, headers={'Authorization': 'Bearer test-token'}).status_code, 422)

    def test_shared_bus_speech_must_match_the_request(self):
        event = {'type': 'speak', 'data': {'utterance': 'Private response'}, 'context': {'destination': ['someone-else']}}
        self.assertIsNone(correlated_speech(event, 'jarvis-1'))
        event['context']['jarvis_request_id'] = 'jarvis-1'
        self.assertEqual(correlated_speech(event, 'jarvis-1'), 'Private response')
        event['context'] = {'destination': 'jarvis-1'}
        self.assertEqual(correlated_speech(event, 'jarvis-1'), 'Private response')
