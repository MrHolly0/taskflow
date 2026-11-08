"""Проверка собранного worker на изолированной сети с подменой внешнего API."""
import argparse
import io
import http.client
import json
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import wave
from pathlib import Path

PROVIDER = r'''
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
records=[]
fail=False
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args): pass
 def answer(self,status,body,kind='application/json'):
  data=body if isinstance(body,bytes) else json.dumps(body).encode()
  self.send_response(status);self.send_header('Content-Type',kind);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
 def do_GET(self):
  if self.path=='/records':return self.answer(200,records)
  return self.answer(200,{'data':[{'id':'openai/gpt-oss-120b'}]})
 def do_POST(self):
  global fail
  body=self.rfile.read(int(self.headers.get('Content-Length',0)))
  if self.path=='/fail':fail=True;return self.answer(200,{})
  if self.path.endswith('/audio/transcriptions'):
   records.append({'kind':'audio','bytes':len(body)})
   return self.answer(200,'Купить чай'.encode(),'text/plain; charset=utf-8')
  payload=json.loads(body);records.append({'kind':'tokens','cap':payload.get('max_completion_tokens')})
  if fail:return self.answer(503,{'error':'temporary'})
  return self.answer(200,{'choices':[{'message':{'content':'ok'}}],'usage':{'prompt_tokens':20,'completion_tokens':10}})
ThreadingHTTPServer(('0.0.0.0',9090),Handler).serve_forever()
'''


def command(*args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, **kwargs).stdout.decode().strip()


def request(url, body=None, headers=None):
    req = urllib.request.Request(url, data=body, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=8) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read()


def wav(seconds, value=0):
    out = io.BytesIO()
    with wave.open(out, 'wb') as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(16000)
        audio.writeframes(bytes([value, 0]) * (seconds * 16000))
    return out.getvalue()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--image', required=True, help='Собранный nlp-worker с ffmpeg')
    args = parser.parse_args()
    suffix = uuid.uuid4().hex[:10]
    network = 'munin-resources-' + suffix
    owned = []
    checks = []
    before = set(command('docker', 'ps', '-q').splitlines())
    command('docker', 'network', 'create', network)
    try:
        with tempfile.TemporaryDirectory(prefix='munin-resources-') as directory:
            stub = Path(directory) / 'provider.py'
            stub.write_text(PROVIDER)

            def run(name, image, *options, cmd=()):
                identity = command('docker', 'run', '-d', '--rm', '--name', name, '--network', network,
                                   '--memory', '512m', '--cpus', '1', '--pids-limit', '128',
                                   *options, image, *cmd)
                owned.append(identity)
                return identity

            def port(identity, value):
                return command('docker', 'port', identity, value).rsplit(':', 1)[1]

            provider_name = 'munin-provider-' + suffix
            provider = run(provider_name, 'python:3.12-slim', '-p', '127.0.0.1::9090',
                           '-v', str(stub) + ':/provider.py:ro', cmd=('python', '/provider.py'))
            stub_url = 'http://127.0.0.1:' + port(provider, '9090')
            redis_name = 'munin-redis-' + suffix
            redis = run(redis_name, 'redis:7-alpine')
            base = 'http://' + provider_name + ':9090'
            secret = 's' * 40
            settings = {
                'NLP_INTERNAL_SECRET': secret,
                'GROQ_API_KEY': 'synthetic-key',
                'REDIS_HOST': redis_name,
                'APP_GROQ_BASE_URL': base,
                'APP_LLM_PROVIDERS_0_NAME': 'groq',
                'APP_LLM_PROVIDERS_0_BASE_URL': base,
                'APP_LLM_PROVIDERS_0_API_KEY': 'synthetic-key',
                'APP_LLM_PROVIDERS_0_MODEL': 'openai/gpt-oss-120b',
                'APP_RESOURCES_MAX_AUDIO_SECONDS': '2',
                'MODEL_USER_DAILY_AUDIO_SECONDS': '30',
                'MODEL_USER_DAILY_TOKENS': '8000',
            }
            options = ['-p', '127.0.0.1::8081']
            for key, value in settings.items():
                options.extend(['-e', key + '=' + value])
            worker = run('munin-worker-' + suffix, args.image, *options)
            worker_url = 'http://127.0.0.1:' + port(worker, '8081')
            ready = False
            for _ in range(90):
                try:
                    ready = request(worker_url + '/actuator/health')[0] == 200
                except (urllib.error.URLError, TimeoutError, http.client.HTTPException, ConnectionError):
                    pass
                if ready:
                    break
                time.sleep(.5)
            if not ready:
                Path('/private/tmp/munin-resource-worker-smoke.log').write_text(command('docker', 'logs', worker))
                raise AssertionError('Worker не запустился; см. /private/tmp/munin-resource-worker-smoke.log')
            print('Worker ready', flush=True)
            user = str(uuid.uuid4())

            def auth():
                return {'X-Model-Secret': secret, 'X-Model-User': user,
                        'X-Model-Deadline': str(int(time.time() * 1000) + 7000)}

            def records():
                return json.loads(request(stub_url + '/records')[2])

            def upload(audio):
                boundary = 'muninboundary'
                body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="audio.ogg"'
                        '\r\nContent-Type: application/octet-stream\r\n\r\n').encode() + audio + ('\r\n--' + boundary + '--\r\n').encode()
                headers = auth()
                headers['Content-Type'] = 'multipart/form-data; boundary=' + boundary
                return request(worker_url + '/nlp/transcribe', body, headers)

            payload = json.dumps({'messages': [{'role': 'user', 'content': 'привет'}], 'tools': []}).encode()

            def model(headers=None, body=payload):
                headers = auth() if headers is None else headers
                headers['Content-Type'] = 'application/json'
                return request(worker_url + '/nlp/tool-call', body, headers)

            assert model({})[0] == 403 and not records()
            checks.append('unauthorized request rejected before provider')
            assert upload(wav(1))[0] == 200
            encoded = subprocess.run(['docker', 'exec', '-i', worker, 'ffmpeg', '-v', 'error', '-f', 'wav',
                                      '-i', 'pipe:0', '-c:a', 'libopus', '-f', 'ogg', 'pipe:1'],
                                     input=wav(1, 1), check=True, capture_output=True).stdout
            assert upload(encoded)[0] == 200
            subprocess.run(['docker', 'exec', worker, 'ffmpeg', '-v', 'error', '-f', 'lavfi', '-i',
                            'anullsrc=r=16000:cl=mono', '-t', '1', '-c:a', 'aac', '/tmp/clip.m4a'],
                           check=True, capture_output=True)
            m4a = subprocess.run(['docker', 'exec', worker, 'cat', '/tmp/clip.m4a'],
                                 check=True, capture_output=True).stdout
            assert upload(m4a)[0] == 200
            count = len(records())
            assert upload(wav(1, 2))[0] == 429 and len(records()) == count
            assert upload(wav(3))[0] == 413 and len(records()) == count
            large_status, large_headers, _ = upload(wav(40))
            assert large_status == 413 and large_headers.get('X-Resource-Rejected') == 'true'
            assert len(records()) == count
            assert upload(b'invalid audio')[0] == 413 and len(records()) == count
            checks.extend(['WAV, compressed Ogg and seekable M4A accepted', 'audio duration and daily quota enforced before provider'])
            expired = auth()
            expired['X-Model-Deadline'] = '1'
            assert model(expired)[0] == 504 and len(records()) == count
            oversized = json.dumps({'messages': [{'role': 'user', 'content': 'x' * 70000}], 'tools': []}).encode()
            assert model(body=oversized)[0] == 413 and len(records()) == count
            assert model()[0] == 200 and records()[-1]['cap'] == 3000
            checks.extend(['expired and oversized requests rejected before provider', 'tool call caps output and preserves response'])
            request(stub_url + '/fail', b'{}')
            assert model()[0] == 503
            count = len(records())
            status, headers, _ = model()
            assert status == 429 and len(records()) == count
            assert int(headers['Retry-After']) > 5
            checks.append('unknown provider usage keeps reservation; daily Retry-After propagated')
            command('docker', 'stop', redis)
            status, _, _ = model()
            assert status == 503 and len(records()) == count
            checks.append('Redis outage fails closed without provider calls')
            after = set(command('docker', 'ps', '-q').splitlines())
            report = {'checks': checks, 'otherContainersStillRunning': sorted(before) == sorted(before & after),
                      'providerCalls': records(), 'image': args.image}
            Path('/private/tmp/model-resource-smoke-results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
            print(json.dumps(report, ensure_ascii=False), flush=True)
    finally:
        for identity in reversed(owned):
            subprocess.run(['docker', 'rm', '-f', identity], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(['docker', 'network', 'rm', network], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == '__main__':
    main()
