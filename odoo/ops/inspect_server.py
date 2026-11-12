import json
import subprocess


def run(*args):
    return subprocess.check_output(args, text=True).strip()


print(run('sudo', '-n', 'docker', 'exec', 'odoo19-postgres', 'psql', '-U', 'odoo', '-d', 'nzqb-odoo', '-Atc',
          "SELECT name,state FROM ir_module_module WHERE name IN ('godex_label_printer','product_expiry','stock');"))
print(run('sudo', '-n', 'docker', 'exec', 'odoo19-postgres', 'psql', '-U', 'odoo', '-d', 'nzqb-odoo', '-Atc',
          "SELECT name FROM ir_model_fields WHERE model='stock.lot' AND name IN ('expiration_date','use_date','removal_date','alert_date');"))
print(run('sudo', '-n', 'docker', 'exec', 'odoo19-postgres', 'psql', '-U', 'odoo', '-d', 'nzqb-odoo', '-Atc',
          "SELECT count(*) FROM godex_label_job;"))
