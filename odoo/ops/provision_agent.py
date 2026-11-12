"""Run inside an Odoo shell. Never prints a password or API key."""
import json
import os
from datetime import timedelta
from pathlib import Path

from odoo import fields

assert env.cr.dbname == 'nzqb-odoo'
group = env.ref('nzqb_label_print.group_label_agent')
company = env.ref('base.main_company')
users = env['res.users'].with_context(no_reset_password=True)
login = 'nzqb.label.connector'
user = users.search([('login', '=', login)], limit=1)
if not user:
    field = 'group_ids' if 'group_ids' in users._fields else 'groups_id'
    user = users.create({
        'name': 'NZQueenBee Workshop Label Connector', 'login': login,
        'company_id': company.id, 'company_ids': [fields.Command.set(company.ids)],
        field: [fields.Command.set([group.id])],
    })
assert user.has_group('nzqb_label_print.group_label_agent')
assert not user.has_group('base.group_system')
assert not user.has_group('base.group_user')
assert not user.has_group('nzqb_label_print.group_label_operator')
printer = env['nzqb.label.printer'].search([('company_id', '=', company.id), ('host', '=', '192.168.68.55')], limit=1)
if not printer:
    printer = env['nzqb.label.printer'].create({'name': '작업장 Godex EZ2350i', 'host': '192.168.68.55', 'port': 9100,
        'dpi': '300', 'company_id': company.id, 'agent_user_id': user.id, 'mode': 'keep', 'speed': 2, 'darkness': 19})
elif printer.agent_user_id != user:
    printer.agent_user_id = user
path = Path('/tmp/nzqb-label-agent-config.json')
if path.exists():
    saved = json.loads(path.read_text())
    assert saved['uid'] == user.id and saved['printer_id'] == printer.id
else:
    expiry = fields.Datetime.now() + timedelta(days=365)
    key = env['res.users.apikeys'].with_user(user).sudo()._generate('rpc', 'Workshop Godex label connector', expiry)
    config = {'url': 'https://odoo.nzqueenbee.com', 'database': env.cr.dbname,
              'uid': user.id, 'api_key': key, 'printer_id': printer.id,
              'agent_name': 'NZQueenBee-Workshop', 'allowed_hosts': ['192.168.68.55'], 'allowed_ports': [9100], 'poll_seconds': 5,
              'key_expires_at': fields.Datetime.to_string(expiry)}
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as handle:
        json.dump(config, handle, ensure_ascii=False, indent=2)
env.cr.commit()
print('Dedicated connector configured. Printer ID:', printer.id, 'User ID:', user.id)
