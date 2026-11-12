import math

from odoo import _, api, fields, models
from odoo.exceptions import UserError, ValidationError

from .. import renderer


class LabelPrinter(models.Model):
    _name = 'nzqb.label.printer'
    _description = 'Label printer connection'

    name = fields.Char(required=True, default='Godex EZ2350i')
    active = fields.Boolean(default=True)
    company_id = fields.Many2one('res.company', required=True, default=lambda self: self.env.company)
    host = fields.Char(required=True, default='192.168.68.55')
    port = fields.Integer(required=True, default=9100)
    dpi = fields.Selection([('203', '203 dpi'), ('300', '300 dpi')], required=True, default='300')
    speed = fields.Integer(required=True, default=2)
    darkness = fields.Integer(required=True, default=19)
    mode = fields.Selection([('keep', '프린터 현재 설정 유지'), ('thermal_transfer', '열전사 / 리본 사용'), ('direct', '감열 / 리본 없음')], default='keep', required=True)
    timeout = fields.Integer(required=True, default=10)
    max_queue_minutes = fields.Integer(default=30, required=True, help='이 시간보다 오래 대기한 요청은 자동 인쇄하지 않고 확인 대기로 전환합니다.')
    agent_user_id = fields.Many2one('res.users', string='연결 프로그램 계정', copy=False)
    last_seen = fields.Datetime(readonly=True)
    agent_name = fields.Char(readonly=True)
    note = fields.Text()

    @api.constrains('port', 'speed', 'darkness', 'timeout', 'max_queue_minutes', 'agent_user_id', 'company_id')
    def _check_settings(self):
        for p in self:
            if not 1 <= p.port <= 65535 or not 2 <= p.speed <= 7 or not 0 <= p.darkness <= 19 or not 1 <= p.timeout <= 60 or not 1 <= p.max_queue_minutes <= 1440:
                raise ValidationError(_('포트·속도·농도·대기 시간을 확인하세요.'))
            if p.agent_user_id and (not p.agent_user_id.has_group('nzqb_label_print.group_label_agent') or p.company_id not in p.agent_user_id.company_ids):
                raise ValidationError(_('이 회사에 접근 가능한 라벨 연결 프로그램 계정을 선택하세요.'))


class LabelTemplate(models.Model):
    _name = 'nzqb.label.template'
    _description = 'Reusable label layout'
    _order = 'name'

    name = fields.Char(required=True)
    active = fields.Boolean(default=True)
    company_id = fields.Many2one('res.company', required=True, default=lambda self: self.env.company)
    photo = fields.Image(max_width=1600, max_height=1600, help='라벨을 구분하는 참고 사진이며 인쇄에 포함하지 않습니다.')
    plain = fields.Boolean(string='무지 라벨')
    width_mm = fields.Float(string='가로 mm (글자가 똑바로 보이는 방향)', default=300, required=True)
    height_mm = fields.Float(string='세로 mm', default=47, required=True)
    gap_mm = fields.Float(string='라벨 간격 mm', default=2, required=True)
    feed_edge = fields.Selection([('top', '위쪽'), ('left', '왼쪽'), ('bottom', '아래쪽'), ('right', '오른쪽')], string='먼저 나오는 변', default='right', required=True)
    upside_down = fields.Boolean(string='거꾸로 인쇄 (180°)')
    offset_x_mm = fields.Float(string='좌우 보정 mm (+오른쪽)', default=0)
    offset_y_mm = fields.Float(string='상하 보정 mm (+아래쪽)', default=0)
    area_ids = fields.One2many('nzqb.label.area', 'template_id', string='인쇄 영역', copy=True)
    copy_from_id = fields.Many2one('nzqb.label.template', string='기존 라벨 설정 가져오기', store=False)
    product_id = fields.Many2one('product.product', string='기본 제품')
    default_storage = fields.Char(string='기본 보관방법')
    default_rmp = fields.Char(string='기본 RMP 번호')
    default_origin = fields.Char(string='기본 원산지', default='Product of New Zealand')
    default_company = fields.Char(string='기본 회사명')

    def _spec(self):
        self.ensure_one()
        return {
            'name': self.name, 'width_mm': self.width_mm, 'height_mm': self.height_mm,
            'gap_mm': self.gap_mm, 'feed_edge': self.feed_edge, 'upside_down': self.upside_down,
            'offset_x_mm': self.offset_x_mm, 'offset_y_mm': self.offset_y_mm,
            'areas': [a._spec() for a in self.area_ids.sorted('sequence')],
        }

    @api.constrains('width_mm', 'height_mm', 'gap_mm', 'offset_x_mm', 'offset_y_mm')
    def _check_size(self):
        for t in self:
            if any(not math.isfinite(x) for x in (t.width_mm, t.height_mm, t.gap_mm, t.offset_x_mm, t.offset_y_mm)) or not (0 < t.width_mm <= 1000 and 0 < t.height_mm <= 1000 and 0 <= t.gap_mm <= 50):
                raise ValidationError(_('라벨 크기 또는 위치 보정값을 확인하세요.'))

    def action_copy_settings(self):
        self.ensure_one()
        source = self.copy_from_id
        if not source or source == self or source.company_id != self.company_id:
            raise UserError(_('같은 회사의 다른 라벨을 선택하세요.'))
        names = ['width_mm', 'height_mm', 'gap_mm', 'feed_edge', 'upside_down', 'offset_x_mm', 'offset_y_mm',
                 'plain', 'default_storage', 'default_rmp', 'default_origin', 'default_company', 'product_id']
        values = {name: source[name].id if name == 'product_id' else source[name] for name in names}
        values['area_ids'] = [fields.Command.clear()] + [fields.Command.create({
            'sequence': a.sequence, 'x_mm': a.x_mm, 'y_mm': a.y_mm, 'width_mm': a.width_mm,
            'height_mm': a.height_mm, 'text': a.text, 'font_pt': a.font_pt, 'bold': a.bold, 'centered': a.centered,
        }) for a in source.area_ids]
        self.write(values)

    def action_new_job(self):
        self.ensure_one()
        return {'type': 'ir.actions.act_window', 'res_model': 'nzqb.label.job', 'view_mode': 'form',
                'target': 'current', 'context': {'default_template_id': self.id, 'default_product_id': self.product_id.id}}


class LabelArea(models.Model):
    _name = 'nzqb.label.area'
    _description = 'Label print area in mm'
    _order = 'sequence, id'

    template_id = fields.Many2one('nzqb.label.template', required=True, ondelete='cascade')
    company_id = fields.Many2one(related='template_id.company_id', store=True)
    sequence = fields.Integer(default=10)
    x_mm = fields.Float(string='왼쪽에서 mm', default=10)
    y_mm = fields.Float(string='위쪽에서 mm', default=5)
    width_mm = fields.Float(string='영역 가로 mm', default=40)
    height_mm = fields.Float(string='영역 세로 mm', default=12)
    text = fields.Text(required=True, default='{BATCH}\n{BB}')
    font_pt = fields.Float(string='글자 크기 pt (0=자동)', default=0)
    bold = fields.Boolean(string='굵게', default=True)
    centered = fields.Boolean(string='가운데 정렬')

    def _spec(self):
        self.ensure_one()
        return {'x': self.x_mm, 'y': self.y_mm, 'w': self.width_mm, 'h': self.height_mm,
                'text': self.text, 'font_pt': self.font_pt, 'bold': self.bold, 'centered': self.centered}

    @api.constrains('x_mm', 'y_mm', 'width_mm', 'height_mm', 'font_pt', 'template_id')
    def _check_bounds(self):
        for a in self:
            if any(not math.isfinite(v) for v in (a.x_mm, a.y_mm, a.width_mm, a.height_mm, a.font_pt)) or min(a.x_mm, a.y_mm, a.font_pt) < 0 or min(a.width_mm, a.height_mm) <= 0:
                raise ValidationError(_('영역의 위치·크기·글자 크기를 확인하세요.'))
            if a.x_mm + a.width_mm > a.template_id.width_mm + .01 or a.y_mm + a.height_mm > a.template_id.height_mm + .01:
                raise ValidationError(_('인쇄 영역이 라벨 밖으로 나갑니다.'))


class LabelBatch(models.Model):
    _name = 'nzqb.label.batch'
    _description = 'Saved label batch (no deletion)'
    _inherit = ['mail.thread']
    _order = 'create_date desc, id desc'

    name = fields.Char(string='배치번호', required=True, tracking=True)
    company_id = fields.Many2one('res.company', required=True, default=lambda self: self.env.company)
    product_id = fields.Many2one('product.product', string='제품', tracking=True)
    lot_id = fields.Many2one('stock.lot', string='Odoo 로트', tracking=True)
    mfg_date = fields.Date(string='제조일', tracking=True)
    bb_mgo = fields.Date(string='Best Before (MGO)', tracking=True)
    bb_umf = fields.Date(string='Best Before (UMF)', tracking=True)
    note = fields.Text(tracking=True)

    _batch_name_unique = models.Constraint('UNIQUE(company_id,name)', '같은 회사에 이미 저장된 배치번호입니다.')

    @api.onchange('lot_id')
    def _onchange_lot(self):
        if self.lot_id:
            self.name = self.lot_id.name
            self.product_id = self.lot_id.product_id
            if self.lot_id.expiration_date:
                self.bb_mgo = fields.Date.to_date(self.lot_id.expiration_date)

    def unlink(self):
        raise UserError(_('저장한 배치는 삭제할 수 없습니다. 수정 이력은 대화 기록에 남습니다.'))


class LabelPhrase(models.Model):
    _name = 'nzqb.label.phrase'
    _description = 'Reusable label wording'

    name = fields.Char(string='문구', required=True)
    company_id = fields.Many2one('res.company', required=True, default=lambda self: self.env.company)
    active = fields.Boolean(default=True)
    category = fields.Selection([(k, label) for k, label in [
        ('product', '제품명'), ('weight', '무게'), ('qty', '수량'), ('storage', '보관방법'),
        ('rmp', 'RMP 번호'), ('origin', '원산지'), ('company', '회사명')]], required=True)
