"""Install and test only in a new, explicitly named validation database."""
import pathlib
import subprocess

DB = 'nzqb_label_validation_20261112'


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


module = pathlib.Path('/home/ubuntu/odoo-docker/odoo19/custom-addons/nzqb_label_print')
assert module.is_dir()
module.chmod(0o755)
for path in module.rglob('*'):
    if not path.is_symlink() and '__pycache__' not in path.parts:
        path.chmod(0o755 if path.is_dir() else 0o644)
present = subprocess.check_output(['sudo', '-n', 'docker', 'exec', 'odoo19-postgres', 'psql', '-U', 'odoo', '-d', 'postgres', '-Atc',
                                   "SELECT datname FROM pg_database WHERE datname='" + DB + "'"], text=True).strip()
if not present:
    run('sudo', '-n', 'docker', 'exec', 'odoo19-postgres', 'createdb', '-U', 'odoo', '-O', 'odoo', DB)
image = subprocess.check_output(['sudo', '-n', 'docker', 'inspect', 'odoo19', '--format', '{{.Config.Image}}'], text=True).strip()
logpath = pathlib.Path('/tmp/nzqb-label-validation.log')
with logpath.open('w') as log:
    result = subprocess.run(['sudo', '-n', 'docker', 'run', '--rm', '--name', 'nzqb-label-validation', '--network', 'container:odoo19', '--volumes-from', 'odoo19',
        image, 'odoo', '-c', '/etc/odoo/odoo.conf', '-d', DB, '--db-filter=^' + DB + '$', '-i', 'nzqb_label_print', '-u', 'nzqb_label_print',
        '--without-demo', '--stop-after-init', '--no-http', '--http-port=18069', '--gevent-port=18072', '--workers=0', '--max-cron-threads=0', '--test-enable', '--test-tags=/nzqb_label_print', '--log-level=test'],
        stdout=log, stderr=subprocess.STDOUT)
text = logpath.read_text()
for line in text.splitlines():
    if any(marker in line for marker in ('ERROR', 'CRITICAL', 'FAIL', 'nzqb_label_print', 'post-tests', 'failed', 'Error', 'ParseError')):
        print(line)
if result.returncode or 'TestLabelQueue' not in text or '0 post-tests' in text or 'ERROR' in text or 'FAIL:' in text:
    print('\n'.join(text.splitlines()[-100:]))
    raise SystemExit(result.returncode or 1)
