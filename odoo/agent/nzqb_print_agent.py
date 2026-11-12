#!/usr/bin/env python3
"""Outbound-only Odoo -> Godex bridge; no third-party Python packages required."""
import argparse
import base64
import hashlib
import json
import logging
import logging.handlers
import os
from pathlib import Path
import re
import socket
import sqlite3
import ssl
import sys
import time
from urllib.parse import urlsplit
import xmlrpc.client


class SafeTransport(xmlrpc.client.SafeTransport):
    def make_connection(self, host):
        connection = super().make_connection(host)
        connection.timeout = 30
        return connection


class Journal:
    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.execute('PRAGMA synchronous=FULL')
        self.db.execute('CREATE TABLE IF NOT EXISTS attempts (job_id INTEGER PRIMARY KEY, job TEXT NOT NULL, phase TEXT NOT NULL, outcome TEXT, message TEXT)')
        self.db.execute("UPDATE attempts SET phase='report',outcome='uncertain',message='프로그램이 전송 도중 종료되어 출력 여부를 확인해야 합니다.' WHERE phase='sending'")
        self.db.commit()

    def save(self, job, phase, outcome=None, message=''):
        self.db.execute('INSERT INTO attempts VALUES (?,?,?,?,?) ON CONFLICT(job_id) DO UPDATE SET phase=excluded.phase,outcome=excluded.outcome,message=excluded.message',
                        (job['id'], json.dumps(job), phase, outcome, message))
        self.db.commit()

    def get(self, job_id):
        return self.db.execute('SELECT phase FROM attempts WHERE job_id=?', (job_id,)).fetchone()

    def pending(self):
        return [(json.loads(row[0]), row[1], row[2], row[3]) for row in self.db.execute("SELECT job,phase,outcome,message FROM attempts WHERE phase!='acked' ORDER BY job_id")]


def printer_status(host, port, timeout):
    try:
        with socket.create_connection((host, port), timeout=min(timeout, 2)) as connection:
            connection.sendall(b'~S,CHECK\r\n')
            data = connection.recv(128).decode('ascii', errors='ignore').strip()
            match = re.search(r'(?<!\d)(\d{2})(?!\d)', data)
            return match.group(1) if match else ''
    except OSError:
        return ''


def validate_job(job, config):
    if job['host'] not in config['allowed_hosts'] or int(job['port']) not in config.get('allowed_ports', [9100]):
        raise ValueError('프린터 주소가 이 PC의 허용 목록과 다릅니다.')
    payload = base64.b64decode(job['payload'], validate=True)
    if not payload or len(payload) > 16000000 or hashlib.sha256(payload).hexdigest() != job['payload_hash']:
        raise ValueError('인쇄 데이터의 크기 또는 무결성 확인에 실패했습니다.')
    return payload


def transmit(job, payload, journal):
    # A failed connect is known not to have sent this job. Any failure AFTER this
    # durable boundary is ambiguous and must NEVER trigger an automatic resend.
    host, port = job['host'], int(job['port'])
    timeout = max(1, min(float(job['timeout']), 60))
    status = printer_status(host, port, timeout)
    if status and status not in ('00', '50', '60'):
        return 'failed', '전송 전 프린터 상태: ' + status
    if status in ('50', '60'):
        return 'failed', '프린터가 다른 작업을 처리 중입니다. 완료 후 새 인쇄 요청을 만드세요. 상태: ' + status
    try:
        connection = socket.create_connection((host, port), timeout=timeout)
    except OSError as exc:
        return 'failed', '인쇄 데이터를 보내기 전 연결 실패: ' + type(exc).__name__
    try:
        journal.save(job, 'sending')
        # Copies are encoded ONCE in EZPL ^P. Never loop over job['copies'] here.
        connection.sendall(payload)
    except OSError as exc:
        return 'uncertain', '전송 중 연결 오류입니다. 실물을 확인하세요: ' + type(exc).__name__
    finally:
        connection.close()
    return 'sent', '프린터로 데이터를 전송했습니다. 실제 출력 매수는 별도 확인이 필요합니다.'


def lock_instance(path):
    handle = open(path, 'a+b')
    handle.seek(0)
    handle.write(b'0')
    handle.flush()
    handle.seek(0)
    if os.name == 'nt':
        import msvcrt
        msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
    else:
        import fcntl
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
    return handle


def run(config, state_dir, once=False, heartbeat_only=False):
    if urlsplit(config['url']).scheme != 'https':
        raise ValueError('Odoo 연결에는 HTTPS 주소가 필요합니다.')
    state_dir = Path(state_dir)
    state_dir.mkdir(parents=True, exist_ok=True)
    instance = lock_instance(state_dir / 'agent.lock')
    log = logging.getLogger('nzqb_print_agent')
    log.setLevel(logging.INFO)
    handler = logging.handlers.RotatingFileHandler(state_dir / 'agent.log', maxBytes=1000000, backupCount=3, encoding='utf-8')
    handler.setFormatter(logging.Formatter('%(asctime)s %(levelname)s %(message)s'))
    log.addHandler(handler)
    log.addHandler(logging.StreamHandler())
    journal = Journal(state_dir / 'journal.sqlite3')
    server = xmlrpc.client.ServerProxy(config['url'].rstrip('/') + '/xmlrpc/2/object', allow_none=True, transport=SafeTransport(context=ssl.create_default_context()))

    def rpc(method, *args):
        return server.execute_kw(config['database'], int(config['uid']), config['api_key'], 'nzqb.label.job', method, list(args))

    def report(job, outcome, message):
        rpc('agent_report', config['printer_id'], job['id'], job['claim_token'], outcome, message)
        journal.save(job, 'acked', outcome, message)
        log.info('Request %s: %s', job['id'], outcome)

    def process(job):
        existing = journal.get(job['id'])
        if existing:
            log.warning('Refusing to resend locally recorded request %s', job['id'])
            return
        journal.save(job, 'prepared')
        try:
            payload = validate_job(job, config)
            outcome, message = transmit(job, payload, journal)
        except (ValueError, KeyError) as exc:
            outcome, message = 'failed', str(exc)
        journal.save(job, 'report', outcome, message)
        report(job, outcome, message)

    # Heartbeat-only intentionally claims NO jobs and cannot print.
    if heartbeat_only:
        rpc('agent_heartbeat', config['printer_id'], config.get('agent_name', socket.gethostname()))
        print('Odoo connection verified. No print requests claimed or sent.')
        return 0
    log.info('Print connector started for printer %s', config['printer_id'])
    while True:
        try:
            for job, phase, outcome, message in journal.pending():
                if phase == 'prepared':
                    # A crash before the send boundary is safe, but require a new
                    # request anyway; simplicity prevents an unexpected delayed print.
                    outcome, message = 'failed', '전송 시작 전에 연결 프로그램이 종료되었습니다. 새 요청을 만드세요.'
                    journal.save(job, 'report', outcome, message)
                report(job, outcome, message)
            job = rpc('agent_claim', config['printer_id'], config.get('agent_name', socket.gethostname()))
            if job:
                process(job)
            if once:
                return 0
            time.sleep(max(2, float(config.get('poll_seconds', 5))))
        except KeyboardInterrupt:
            return 0
        except Exception as exc:
            # Avoid credentials/payloads in logs. Acknowledgement retries only,
            # not a second send, are attempted after a network outage.
            log.error('Connection or processing error: %s', type(exc).__name__)
            if once:
                return 1
            time.sleep(10)


def main():
    parser = argparse.ArgumentParser(description='NZQueenBee label print connector')
    parser.add_argument('--config', default=str(Path(__file__).with_name('config.json')))
    parser.add_argument('--state-dir')
    parser.add_argument('--once', action='store_true')
    parser.add_argument('--heartbeat-only', action='store_true')
    args = parser.parse_args()
    path = Path(args.config).resolve()
    with path.open(encoding='utf-8-sig') as handle:
        config = json.load(handle)
    return run(config, args.state_dir or path.parent / 'state', args.once, args.heartbeat_only)


if __name__ == '__main__':
    sys.exit(main())
