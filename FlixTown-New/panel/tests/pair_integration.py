import json
import urllib.error
import urllib.request
import time

BASE = 'http://127.0.0.1:8081/api/'


def post(name, data, origin=None):
    headers = {'Content-Type': 'application/json'}
    if origin:
        headers['Origin'] = origin
    req = urllib.request.Request(BASE + name + '.php', json.dumps(data).encode(), headers, method='POST')
    with urllib.request.urlopen(req) as response:
        return json.load(response)


for attempt in range(15):
    try:
        pair = post('pair-start', {})
        break
    except urllib.error.URLError:
        if attempt == 14:
            raise
        time.sleep(1)
assert len(pair['code']) == 8
assert len(pair['verifier']) == 64
pending = post('pair-poll', {'code': pair['code'], 'verifier': pair['verifier']})
assert pending['status'] == 'pending'
approved = post('pair-activate', {'code': pair['code'], 'username': 'testuser', 'password': 'testpass'}, 'http://localhost:8080')
assert approved['status'] == 'approved'
received = post('pair-poll', {'code': pair['code'], 'verifier': pair['verifier']})
assert received['status'] == 'approved'
assert received['account'] == {'username': 'testuser', 'password': 'testpass'}
assert post('pair-poll', {'code': pair['code'], 'verifier': pair['verifier']})['status'] == 'redeemed'
try:
    post('pair-poll', {'code': pair['code'], 'verifier': '0' * 64})
    raise AssertionError('Invalid TV verifier accepted')
except urllib.error.HTTPError as error:
    assert error.code == 404
print('Pairing integration passed')
renewal = post('renewal-request', {'username': 'testuser', 'phone': '505-555-0123', 'plan': '3m'})
assert renewal['status'] == 'pending'
print('Renewal request integration passed')
