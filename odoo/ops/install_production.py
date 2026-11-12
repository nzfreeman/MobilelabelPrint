"""Back up the production DB, then install only the validated additive module."""
from datetime import datetime, timezone
import pathlib
import subprocess

DB = 'nzqb-odoo'
root = pathlib.Path('/home/ubuntu/odoo-docker/odoo19')
backup = root / 'backups' / ('label_print_' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
backup.mkdir(mode=0o700)
dump = backup / 'before_label_module.dump'
with dump.open('wb') as handle:
    subprocess.run(['sudo', '-n', 'docker', 'exec', 'odoo19-postgres', 'pg_dump', '-U', 'odoo', '-Fc', DB], stdout=handle, check=True)
dump.chmod(0o600)
assert dump.stat().st_size > 10000
with dump.open('rb') as handle:
    subprocess.run(['sudo', '-n', 'docker', 'exec', '-i', 'odoo19-postgres', 'pg_restore', '--list'], stdin=handle, stdout=subprocess.DEVNULL, check=True)
print('Verified database backup:', dump, flush=True)
logpath = backup / 'install.log'
with logpath.open('w') as log:
    result = subprocess.run(['sudo', '-n', 'docker', 'exec', '-u', 'odoo', 'odoo19', 'odoo', '-c', '/etc/odoo/odoo.conf',
        '-d', DB, '-i', 'nzqb_label_print', '--stop-after-init', '--no-http', '--workers=0', '--max-cron-threads=0', '--log-level=info'],
        stdout=log, stderr=subprocess.STDOUT)
logpath.chmod(0o600)
text = logpath.read_text()
for line in text.splitlines():
    if any(k in line for k in ('nzqb_label_print', 'ERROR', 'CRITICAL', 'Modules loaded', 'Registry loaded')):
        print(line)
if result.returncode or 'ERROR' in text or 'CRITICAL' in text:
    print('\n'.join(text.splitlines()[-70:]))
    raise SystemExit(result.returncode or 1)
state = subprocess.check_output(['sudo', '-n', 'docker', 'exec', 'odoo19-postgres', 'psql', '-U', 'odoo', '-d', DB, '-Atc',
    "SELECT state FROM ir_module_module WHERE name='nzqb_label_print'"], text=True).strip()
assert state == 'installed', state
print('Production module installed. No print requests were created.')
