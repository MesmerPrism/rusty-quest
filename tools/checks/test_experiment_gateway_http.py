"""Loopback HTTP reader regression; requires a reserved --port and host org.json.

Compiles the production adapter/protocol with an inert owner. The adapter copy
only restricts its bind address to loopback. No Android or real credentials.
"""
import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time

PACKAGE = 'io/github/mesmerprism/rustyquest/native_renderer'
KEY = 'b' * 64
OWNER = '''package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;import java.util.concurrent.atomic.AtomicInteger;
final class ExperimentSessionCommandGateway {
 final AtomicInteger calls=new AtomicInteger();
 final ExperimentSessionGatewayProtocol protocol=new ExperimentSessionGatewayProtocol(
  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "cccccccccccccccccccccccccccccccc",7,(action,payload)->{
   calls.incrementAndGet();return new JSONObject().put("synthetic",true);});
 boolean allowedOrigin(String origin){return "https://controller.example".equals(origin);}
 JSONObject identity()throws Exception{calls.incrementAndGet();return new JSONObject().put("v",1).put("enabled",true);}
 JSONObject exchange(JSONObject request)throws Exception{return protocol.exchange(request);}
}'''
HARNESS = '''package io.github.mesmerprism.rustyquest.native_renderer;
import java.io.*;import java.util.concurrent.*;import java.lang.reflect.*;import org.json.JSONObject;
public final class HttpReaderHarness {
 public static void main(String[] args)throws Exception {
  ExperimentSessionCommandGateway owner=new ExperimentSessionCommandGateway();
  ExperimentSessionGatewayHttpServer server=new ExperimentSessionGatewayHttpServer(owner,Integer.parseInt(args[0]));server.start();
  Field field=ExperimentSessionGatewayHttpServer.class.getDeclaredField("workers");field.setAccessible(true);
  ThreadPoolExecutor workers=(ThreadPoolExecutor)field.get(server);
  System.out.println("ready");System.out.flush();
  try{BufferedReader reader=new BufferedReader(new InputStreamReader(System.in));String command;
   while((command=reader.readLine())!=null&&!command.equals("exit")){
    if(command.equals("close"))server.close();
    if(command.equals("retire"))owner.protocol.retire();
    System.out.println(new JSONObject().put("active",workers.getActiveCount()).put("queued",workers.getQueue().size())
     .put("pool",workers.getPoolSize()).put("terminated",workers.isTerminated()).put("calls",owner.calls.get()));System.out.flush();
   }
  }finally{server.close();}
 }
}'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--json-jar', type=Path, required=True)
    parser.add_argument('--port', type=int, required=True)
    args = parser.parse_args()
    source = args.repo / 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java' / PACKAGE
    with tempfile.TemporaryDirectory(prefix='rq-http-readers-') as temporary:
        root = Path(temporary)
        package = root / PACKAGE
        package.mkdir(parents=True)
        text = (source / 'ExperimentSessionGatewayHttpServer.java').read_text()
        anchor = 'new ServerSocket(port,8)'
        assert text.count(anchor) == 1, 'production binding changed; review loopback fixture'
        (package / 'ExperimentSessionGatewayHttpServer.java').write_text(text.replace(anchor, 'new ServerSocket(port,8,java.net.InetAddress.getLoopbackAddress())'))
        (package / 'ExperimentSessionGatewayProtocol.java').write_text((source / 'ExperimentSessionGatewayProtocol.java').read_text())
        (package / 'ExperimentSessionCommandGateway.java').write_text(OWNER)
        (package / 'HttpReaderHarness.java').write_text(HARNESS)
        classes = root / 'classes'
        classes.mkdir()
        subprocess.run(['javac', '--release', '8', '-cp', str(args.json_jar), '-d', str(classes), *map(str, package.glob('*.java'))], check=True)
        process = subprocess.Popen(['java', '-cp', str(classes) + os.pathsep + str(args.json_jar), PACKAGE.replace('/', '.') + '.HttpReaderHarness', str(args.port)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        idle = []
        def stats(command='stats'):
            process.stdin.write(command + '\n')
            process.stdin.flush()
            return json.loads(process.stdout.readline())
        def wait_for(predicate, timeout=2):
            end = time.monotonic() + timeout
            while True:
                value = stats()
                if predicate(value):
                    return value
                assert time.monotonic() < end, value
                time.sleep(.01)
        def request(method='GET', body=None, origin='https://controller.example', extra='', path='/v1/gateway'):
            payload = b'' if body is None else json.dumps(body).encode()
            headers = f'{method} {path} HTTP/1.1\r\nHost: localhost\r\nOrigin: {origin}\r\n'
            if body is not None:
                headers += f'Content-Type: application/json\r\nContent-Length: {len(payload)}\r\n'
            start = time.monotonic()
            with socket.create_connection(('127.0.0.1', args.port), timeout=20) as client:
                client.sendall((headers + extra + '\r\n').encode() + payload)
                data = b''
                while True:
                    try:
                        chunk = client.recv(65536)
                    except ConnectionResetError:
                        break
                    if not chunk:
                        break
                    data += chunk
            return data, round((time.monotonic() - start) * 1000)
        def envelope():
            nonce = secrets.token_hex(16)
            mac = hmac.new(KEY.encode(), f'RQG1|{"a"*32}|7|{nonce}|status|{{}}'.encode(), hashlib.sha256).hexdigest()
            return dict(v=1, controller='a'*32, epoch='7', nonce=nonce, action='status', payload='{}', mac=mac)
        try:
            assert process.stdout.readline().strip() == 'ready'
            data, baseline = request()
            assert data.startswith(b'HTTP/1.1 200')
            wait_for(lambda s: s['active'] == 0)
            idle.extend(socket.create_connection(('127.0.0.1', args.port)) for _ in range(6))
            wait_for(lambda s: s['active'] + s['queued'] == 6)
            before = stats()['calls']
            data, delayed = request()
            assert data.startswith(b'HTTP/1.1 200')
            assert delayed < 2000, f'six idle sockets starved valid request for {delayed} ms'
            assert stats()['calls'] == before + 1
            for client in idle:
                client.close()
            idle.clear()
            wait_for(lambda s: s['active'] == 0)
            # Idle excess threads retire; core readers remain available.
            wait_for(lambda s: s['pool'] == 2, timeout=35)
            before = stats()['calls']
            for options, status in [
                ({'origin': 'https://wrong.example'}, 403),
                ({'path': '/wrong'}, 404),
                ({'method': 'OPTIONS', 'extra': 'Access-Control-Request-Method: POST\r\nAccess-Control-Request-Headers: content-type\r\n'}, 200),
                ({'method': 'OPTIONS', 'extra': 'Access-Control-Request-Method: POST\r\nAccess-Control-Request-Headers: x-extra\r\n'}, 403),
                ({'method': 'POST', 'body': {}, 'extra': 'Transfer-Encoding: chunked\r\n'}, 400),
                ({'method': 'POST', 'extra': 'Content-Type: application/json\r\nContent-Length: 8193\r\n'}, None),
                ({'extra': 'Origin: https://controller.example\r\n'}, None),
                ({'extra': 'X-Oversized: ' + 'x'*2048 + '\r\n'}, None),
                ({'method': 'POST', 'body': {}}, 403),
            ]:
                data, _ = request(**options)
                assert not data if status is None else data.startswith(f'HTTP/1.1 {status}'.encode()), data
                assert stats()['calls'] == before, 'rejected/preflight request reached owner'
            signed = envelope()
            data, _ = request('POST', signed)
            assert data.startswith(b'HTTP/1.1 200')
            reply = json.loads(data.split(b'\r\n\r\n', 1)[1])
            message = f'RQG1-REPLY|{"a"*32}|{"c"*32}|7|{signed["nonce"]}|status|{reply["payload"]}'
            assert hmac.compare_digest(reply['mac'], hmac.new(KEY.encode(), message.encode(), hashlib.sha256).hexdigest())
            assert stats()['calls'] == before + 1
            data, _ = request('POST', signed)
            assert data.startswith(b'HTTP/1.1 403') and stats()['calls'] == before + 1
            stats('retire')
            data, _ = request('POST', envelope())
            assert data.startswith(b'HTTP/1.1 403') and stats()['calls'] == before + 1
            wait_for(lambda s: s['active'] == 0)
            idle.extend(socket.create_connection(('127.0.0.1', args.port)) for _ in range(10))
            wait_for(lambda s: s['active'] == 10 and s['queued'] == 0)
            before = stats()['calls']
            data, overloaded = request()
            assert not data and overloaded < 2000 and stats()['calls'] == before
            stats('close')
            # Existing five-second socket read timeout releases owned idle readers.
            wait_for(lambda s: s['terminated'], timeout=7)
            for client in idle:
                client.settimeout(1)
                assert client.recv(1) == b'', 'owned accepted socket remained open'
            with socket.socket() as probe:
                probe.settimeout(1)
                assert probe.connect_ex(('127.0.0.1', args.port)) != 0, 'listener survived close'
            print(json.dumps(dict(passed=True, baseline_ms=baseline, six_idle_valid_get_ms=delayed, ten_idle_overload_close_ms=overloaded, exact_origin_parser_size_auth_replay_retirement=True, excess_workers_retired=True, owned_idle_sockets_closed=True, device_evidence=False)))
        finally:
            for client in idle:
                client.close()
            process.stdin.write('exit\n')
            process.stdin.flush()
            process.communicate(timeout=10)
            assert process.returncode == 0


if __name__ == '__main__':
    main()
