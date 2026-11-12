import base64
import hashlib
import json
import uuid
from datetime import timedelta

from odoo import _, api, fields, models
from odoo.exceptions import AccessError, UserError, ValidationError

from .. import renderer


class LabelJob(models.Model):
    _name = 'nzqb.label.job'
    _description = 'Label request and permanent print history'
    _inherit = ['mail.thread']
    _order = 'create_date desc, id desc'

    name = fields.Char(default='New', required=True, readonly=True, copy=False)
    company_id = fields.Many2one('res.company', required=True, default=lambda self: self.env.company)
    state = fields.Selection([
        ('draft', '작성 중'), ('queued', '인쇄 대기'), ('held', '대기 시간 초과 · 확인 필요'),
        ('processing', '연결 장치 처리 중'), ('sent', '프린터로 전송 완료'),
        ('uncertain', '출력 여부 확인 필요'), ('failed', '전송 전 실패'), ('cancelled', '취소됨'),
    ], default='draft', required=True, readonly=True, tracking=True, copy=False, index=True)
    printer_id = fields.Many2one('nzqb.label.printer', required=True, ondelete='restrict', default=lambda self: self.env['nzqb.label.printer'].search([('company_id','=',self.env.company.id)], limit=1))
    template_id = fields.Many2one('nzqb.label.template', required=True, ondelete='restrict')
    product_id = fields.Many2one('product.product', string='제품')
    batch_id = fields.Many2one('nzqb.label.batch', string='저장한 배치', ondelete='restrict')
    batch_number = fields.Char(string='배치번호')
    mfg_date = fields.Date(string='제조일')
    bb_date = fields.Date(string='Best Before')
    bb_kind = fields.Selection([('mgo', 'MGO'), ('umf', 'UMF')], default='mgo', required=True)
    date_format = fields.Selection([('%d/%m/%Y', 'DD/MM/YYYY'), ('%d-%m-%Y', 'DD-MM-YYYY'), ('%Y-%m-%d', 'YYYY-MM-DD')], default='%d/%m/%Y', required=True)
    batch_prefix = fields.Boolean(string='Batch: 인쇄', default=True)
    bb_prefix = fields.Selection([('none', '머리말 없이'), ('Best Before:', 'Best Before:'), ('B.B:', 'B.B:'), ('BB:', 'BB:')], default='Best Before:', required=True)
    mfg_prefix = fields.Selection([('none', '머리말 없이'), ('MFG:', 'MFG:'), ('MFG Date:', 'MFG Date:')], default='MFG:', required=True)
    copies = fields.Integer(string='인쇄 매수', default=1, required=True)
    product_text = fields.Char(string='제품명')
    weight_text = fields.Char(string='무게')
    qty_text = fields.Char(string='수량')
    storage_text = fields.Char(string='보관방법')
    rmp_text = fields.Char(string='RMP 번호')
    origin_text = fields.Char(string='원산지')
    company_text = fields.Char(string='회사명')
    product_phrase_id = fields.Many2one('nzqb.label.phrase', string='저장된 제품명', domain="[('category','=','product')]")
    weight_phrase_id = fields.Many2one('nzqb.label.phrase', string='저장된 무게', domain="[('category','=','weight')]")
    qty_phrase_id = fields.Many2one('nzqb.label.phrase', string='저장된 수량', domain="[('category','=','qty')]")
    storage_phrase_id = fields.Many2one('nzqb.label.phrase', string='저장된 보관방법', domain="[('category','=','storage')]")
    rmp_phrase_id = fields.Many2one('nzqb.label.phrase', string='저장된 RMP', domain="[('category','=','rmp')]")
    origin_phrase_id = fields.Many2one('nzqb.label.phrase', string='저장된 원산지', domain="[('category','=','origin')]")
    company_phrase_id = fields.Many2one('nzqb.label.phrase', string='저장된 회사명', domain="[('category','=','company')]")
    requested_by = fields.Many2one('res.users', readonly=True, default=lambda self: self.env.user, copy=False)
    requested_at = fields.Datetime(readonly=True, copy=False)
    expires_at = fields.Datetime(readonly=True, copy=False)
    claimed_at = fields.Datetime(readonly=True, copy=False)
    processed_at = fields.Datetime(readonly=True, copy=False)
    agent_name = fields.Char(readonly=True, copy=False)
    claim_token = fields.Char(readonly=True, copy=False, groups='nzqb_label_print.group_label_agent')
    payload = fields.Binary(readonly=True, attachment=False, copy=False)
    payload_hash = fields.Char(readonly=True, copy=False)
    preview = fields.Binary(readonly=True, attachment=False, copy=False)
    snapshot = fields.Text(readonly=True, copy=False)
    result_message = fields.Text(readonly=True, copy=False)
    original_job_id = fields.Many2one('nzqb.label.job', readonly=True, copy=False, ondelete='restrict')
    reprint_reason = fields.Char(string='재인쇄 사유')

    _copies_range = models.Constraint('CHECK(copies BETWEEN 1 AND 9999)', '인쇄 매수는 1~9999장이어야 합니다.')

    _PROTECTED = {'name', 'state', 'requested_by', 'requested_at', 'expires_at', 'claimed_at', 'processed_at',
                  'agent_name', 'claim_token', 'payload', 'payload_hash', 'preview', 'snapshot', 'result_message', 'original_job_id'}

    @api.model_create_multi
    def create(self, vals_list):
        for vals in vals_list:
            if any(key in vals for key in self._PROTECTED):
                raise AccessError(_('인쇄 이력 필드를 직접 지정할 수 없습니다.'))
            vals['requested_by'] = self.env.uid
            vals['name'] = self.env['ir.sequence'].next_by_code('nzqb.label.job') or 'New'
        return super().create(vals_list)

    def write(self, vals):
        if set(vals) & self._PROTECTED or any(j.state != 'draft' for j in self):
            raise UserError(_('접수한 인쇄 요청과 기록은 직접 수정할 수 없습니다. 새 요청을 만드세요.'))
        return super().write(vals)

    def _update(self, vals):
        # Only private methods use this path; RPC callers cannot set queue states or payloads.
        return super(LabelJob, self.sudo()).write(vals)

    def unlink(self):
        raise UserError(_('인쇄 기록은 삭제할 수 없습니다. 대기 중인 요청은 취소하세요.'))

    @api.constrains('printer_id', 'template_id', 'batch_id', 'company_id')
    def _check_company(self):
        for j in self:
            if any(record and record.company_id != j.company_id for record in (j.printer_id, j.template_id, j.batch_id)):
                raise ValidationError(_('프린터·라벨·배치는 같은 회사에 속해야 합니다.'))

    @api.onchange('template_id')
    def _onchange_template(self):
        t = self.template_id
        if t:
            self.product_id = t.product_id or self.product_id
            self.storage_text = t.default_storage if t.plain else False
            self.rmp_text = t.default_rmp if t.plain else False
            self.origin_text = t.default_origin if t.plain else False
            self.company_text = t.default_company if t.plain else False

    @api.onchange('product_id')
    def _onchange_product(self):
        if self.product_id:
            self.product_text = self.product_id.display_name

    @api.onchange('batch_id', 'bb_kind')
    def _onchange_batch(self):
        if self.batch_id:
            self.batch_number = self.batch_id.name
            self.mfg_date = self.batch_id.mfg_date
            self.bb_date = self.batch_id.bb_umf if self.bb_kind == 'umf' else self.batch_id.bb_mgo
            self.product_id = self.batch_id.product_id or self.product_id

    @api.onchange('product_phrase_id')
    def _onchange_product_phrase(self):
        self.product_text = self.product_phrase_id.name or False

    @api.onchange('weight_phrase_id')
    def _onchange_weight_phrase(self):
        self.weight_text = self.weight_phrase_id.name or False

    @api.onchange('qty_phrase_id')
    def _onchange_qty_phrase(self):
        self.qty_text = self.qty_phrase_id.name or False

    @api.onchange('storage_phrase_id')
    def _onchange_storage_phrase(self):
        self.storage_text = self.storage_phrase_id.name or False

    @api.onchange('rmp_phrase_id')
    def _onchange_rmp_phrase(self):
        self.rmp_text = self.rmp_phrase_id.name or False

    @api.onchange('origin_phrase_id')
    def _onchange_origin_phrase(self):
        self.origin_text = self.origin_phrase_id.name or False

    @api.onchange('company_phrase_id')
    def _onchange_company_phrase(self):
        self.company_text = self.company_phrase_id.name or False

    def _values(self):
        self.ensure_one()
        def date(value):
            return value.strftime(self.date_format) if value else ''
        def prefix(value, label):
            return (label + ' ' if label != 'none' else '') + value if value else ''
        return {
            'BATCH': prefix((self.batch_number or '').strip(), 'Batch:' if self.batch_prefix else 'none'),
            'BB': prefix(date(self.bb_date), self.bb_prefix), 'MFG': prefix(date(self.mfg_date), self.mfg_prefix),
            **{key.upper(): self[key + '_text'] or '' for key in ('product', 'weight', 'qty', 'storage', 'rmp', 'origin', 'company')},
        }

    def _render(self):
        self.ensure_one()
        if not self.printer_id.active or not self.template_id.active:
            raise UserError(_('사용 중인 프린터와 라벨을 선택하세요.'))
        try:
            spec, values = self.template_id._spec(), self._values()
            data, preview = renderer.render(spec, values, self.printer_id.dpi, self.copies,
                                            self.printer_id.speed, self.printer_id.darkness, self.printer_id.mode)
        except (ValueError, KeyError) as exc:
            raise UserError(str(exc)) from exc
        return data, preview, json.dumps({'template': spec, 'values': values, 'copies': self.copies, 'dpi': self.printer_id.dpi}, ensure_ascii=False)

    def action_preview(self):
        self.check_access('write')
        for j in self:
            if j.state != 'draft':
                raise UserError(_('접수된 요청은 저장된 미리보기를 사용합니다.'))
            _, preview, _ = j._render()
            j._update({'preview': base64.b64encode(preview)})

    def action_queue(self):
        self.check_access('write')
        for j in self:
            self.env.cr.execute('SELECT id FROM nzqb_label_job WHERE id=%s FOR UPDATE', [j.id])
            j.invalidate_recordset(['state'])
            if j.state != 'draft':
                raise UserError(_('이미 접수된 요청입니다.'))
            if j.original_job_id and not j.reprint_reason:
                raise UserError(_('재인쇄 사유를 입력하세요.'))
            data, preview, snapshot = j._render()
            now = fields.Datetime.now()
            j._update({'state': 'queued', 'requested_at': now, 'expires_at': now + timedelta(minutes=j.printer_id.max_queue_minutes),
                       'payload': base64.b64encode(data), 'payload_hash': hashlib.sha256(data).hexdigest(),
                       'preview': base64.b64encode(preview), 'snapshot': snapshot})

    def action_cancel(self):
        self.check_access('write')
        for j in self:
            self.env.cr.execute('SELECT id FROM nzqb_label_job WHERE id=%s FOR UPDATE', [j.id])
            j.invalidate_recordset(['state'])
            if j.state not in ('draft', 'queued', 'held'):
                raise UserError(_('이미 처리 중이거나 전송된 요청은 취소할 수 없습니다.'))
            j._update({'state': 'cancelled'})

    def action_release(self):
        self.check_access('write')
        for j in self:
            self.env.cr.execute('SELECT id FROM nzqb_label_job WHERE id=%s FOR UPDATE', [j.id])
            j.invalidate_recordset(['state'])
            if j.state != 'held':
                raise UserError(_('확인 대기 요청만 다시 승인할 수 있습니다.'))
            j._update({'state': 'queued', 'expires_at': fields.Datetime.now() + timedelta(minutes=j.printer_id.max_queue_minutes)})

    def action_reprint(self):
        self.ensure_one()
        self.check_access('read')
        if not self.env.user.has_group('nzqb_label_print.group_label_operator'):
            raise AccessError(_('인쇄 요청 권한이 필요합니다.'))
        job = self.copy()
        job._update({'original_job_id': self.id})
        return {'type': 'ir.actions.act_window', 'res_model': self._name, 'res_id': job.id, 'view_mode': 'form', 'target': 'current'}

    @api.model
    def _agent_printer(self, printer_id):
        if not self.env.user.has_group('nzqb_label_print.group_label_agent'):
            raise AccessError(_('연결 프로그램 전용 기능입니다.'))
        printer = self.env['nzqb.label.printer'].browse(int(printer_id)).exists()
        if not printer or not printer.active or printer.agent_user_id != self.env.user:
            raise AccessError(_('이 프린터에 연결할 권한이 없습니다.'))
        printer.check_access('read')
        return printer

    @api.model
    def agent_heartbeat(self, printer_id, agent_name):
        printer = self._agent_printer(printer_id)
        printer.sudo().write({'last_seen': fields.Datetime.now(), 'agent_name': str(agent_name)[:120]})
        return True

    @api.model
    def agent_claim(self, printer_id, agent_name):
        printer = self._agent_printer(printer_id)
        now = fields.Datetime.now()
        printer.sudo().write({'last_seen': now, 'agent_name': str(agent_name)[:120]})
        self.env.cr.execute('SELECT pg_advisory_xact_lock(194705, %s)', [printer.id])
        self.flush_model(['state', 'printer_id', 'expires_at', 'claimed_at', 'requested_at'])
        self.env.cr.execute("SELECT id FROM nzqb_label_job WHERE printer_id=%s AND state='queued' AND expires_at < %s LIMIT 100 FOR UPDATE SKIP LOCKED", [printer.id, now])
        expired = self.browse([r[0] for r in self.env.cr.fetchall()])
        expired._update({'state': 'held', 'result_message': _('대기 시간이 지나 자동 인쇄를 보류했습니다.')})
        self.env.cr.execute("SELECT id FROM nzqb_label_job WHERE printer_id=%s AND state='processing' AND claimed_at < %s LIMIT 100 FOR UPDATE SKIP LOCKED", [printer.id, now - timedelta(minutes=5)])
        stale = self.browse([r[0] for r in self.env.cr.fetchall()])
        stale._update({'state': 'uncertain', 'result_message': _('연결 장치의 결과가 확인되지 않았습니다. 실제 라벨을 확인한 뒤 새 요청으로 재인쇄하세요.')})
        if self.search_count([('printer_id', '=', printer.id), ('state', '=', 'processing')]):
            return False
        self.env.cr.execute('''SELECT id FROM nzqb_label_job WHERE printer_id=%s AND state='queued' AND expires_at >= %s
                               ORDER BY requested_at,id LIMIT 1 FOR UPDATE SKIP LOCKED''', [printer.id, now])
        row = self.env.cr.fetchone()
        if not row:
            return False
        job = self.browse(row[0])
        job._update({'state': 'processing', 'claimed_at': now, 'agent_name': str(agent_name)[:120], 'claim_token': uuid.uuid4().hex})
        return {'id': job.id, 'name': job.name, 'claim_token': job.claim_token,
                'payload': job.payload.decode('ascii'), 'payload_hash': job.payload_hash,
                'copies': job.copies, 'host': printer.host, 'port': printer.port, 'timeout': printer.timeout}

    @api.model
    def agent_report(self, printer_id, job_id, claim_token, outcome, message=''):
        printer = self._agent_printer(printer_id)
        if outcome not in ('sent', 'failed', 'uncertain'):
            raise ValidationError(_('잘못된 전송 결과입니다.'))
        job = self.browse(int(job_id)).exists()
        if not job or job.printer_id != printer or not claim_token or job.claim_token != claim_token:
            raise AccessError(_('처리 중인 요청의 인증값이 맞지 않습니다.'))
        self.env.cr.execute('SELECT id FROM nzqb_label_job WHERE id=%s FOR UPDATE', [job.id])
        job.invalidate_recordset(['state'])
        if job.state == outcome:
            return True
        if job.state not in ('processing', 'uncertain'):
            raise UserError(_('이미 결과가 기록된 요청입니다.'))
        job._update({'state': outcome, 'processed_at': fields.Datetime.now(), 'result_message': str(message)[:2000]})
        return True
