import base64
from datetime import timedelta

from odoo import fields
from odoo.exceptions import AccessError, UserError
from odoo.tests import TransactionCase, tagged


@tagged('post_install', '-at_install')
class TestLabelQueue(TransactionCase):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        group_field = 'group_ids' if 'group_ids' in cls.env['res.users']._fields else 'groups_id'
        cls.agent = cls.env['res.users'].create({
            'name': 'Label test agent', 'login': 'nzqb-label-test-agent',
            group_field: [fields.Command.set([cls.env.ref('nzqb_label_print.group_label_agent').id])],
            'company_id': cls.env.company.id, 'company_ids': [fields.Command.set(cls.env.company.ids)],
        })
        cls.other_agent = cls.env['res.users'].create({
            'name': 'Unassigned label agent', 'login': 'nzqb-label-other-agent',
            group_field: [fields.Command.set([cls.env.ref('nzqb_label_print.group_label_agent').id])],
        })
        assert not cls.agent.has_group('base.group_user')
        cls.printer = cls.env['nzqb.label.printer'].create({'name': 'Test only', 'agent_user_id': cls.agent.id})
        cls.template = cls.env['nzqb.label.template'].create({
            'name': '300 x 47 test', 'width_mm': 300, 'height_mm': 47, 'feed_edge': 'right',
            'upside_down': True,
            'area_ids': [fields.Command.create({'x_mm': 100, 'y_mm': 10, 'width_mm': 40, 'height_mm': 15, 'text': '{BATCH}\n{BB}'})],
        })

    def new_job(self):
        return self.env['nzqb.label.job'].create({'printer_id': self.printer.id, 'template_id': self.template.id,
                                                'batch_number': 'TEST-NOT-FOR-PRINT', 'bb_date': '2030-12-29', 'copies': 3})

    def test_queue_snapshot_and_copies_once(self):
        job = self.new_job()
        job.action_queue()
        payload = base64.b64decode(job.payload)
        self.assertTrue(payload.startswith(b'^Q300,2\r\n^W47\r\n'))
        self.assertEqual(payload.count(b'^P3\r\n'), 1)
        frozen = job.payload
        self.template.offset_x_mm = .5
        self.assertEqual(job.payload, frozen)
        with self.assertRaises(UserError):
            job.action_queue()
        with self.assertRaises(UserError):
            job.write({'copies': 5})

    def test_claim_report_and_permission(self):
        job = self.new_job()
        job.action_queue()
        api = self.env['nzqb.label.job'].with_user(self.agent)
        claimed = api.agent_claim(self.printer.id, 'unit-test')
        self.assertEqual(claimed['id'], job.id)
        self.assertFalse(api.agent_claim(self.printer.id, 'unit-test-2'))
        with self.assertRaises(AccessError):
            api.with_user(self.other_agent).agent_claim(self.printer.id, 'wrong-agent')
        with self.assertRaises(AccessError):
            self.env['res.partner'].with_user(self.agent).search([]).read(['name'])
        with self.assertRaises(AccessError):
            api.agent_report(self.printer.id, job.id, 'bad-token', 'sent')
        self.assertTrue(api.agent_report(self.printer.id, job.id, claimed['claim_token'], 'sent', 'test, no socket used'))
        self.assertTrue(api.agent_report(self.printer.id, job.id, claimed['claim_token'], 'sent'))
        self.assertEqual(job.state, 'sent')
        with self.assertRaises(UserError):
            api.agent_report(self.printer.id, job.id, claimed['claim_token'], 'failed')

    def test_expired_job_is_held_and_stale_job_not_reprinted(self):
        job = self.new_job()
        job.action_queue()
        job._update({'expires_at': fields.Datetime.now() - timedelta(minutes=1)})
        api = self.env['nzqb.label.job'].with_user(self.agent)
        self.assertFalse(api.agent_claim(self.printer.id, 'test'))
        self.assertEqual(job.state, 'held')
        job.action_release()
        claimed = api.agent_claim(self.printer.id, 'test')
        self.assertTrue(claimed)
        job._update({'claimed_at': fields.Datetime.now() - timedelta(minutes=6)})
        self.assertFalse(api.agent_claim(self.printer.id, 'test'))
        self.assertEqual(job.state, 'uncertain')

    def test_no_deletion_or_forged_payload(self):
        job = self.new_job()
        with self.assertRaises(UserError):
            job.unlink()
        batch = self.env['nzqb.label.batch'].create({'name': 'No delete test'})
        with self.assertRaises(UserError):
            batch.unlink()
        with self.assertRaises(AccessError):
            self.env['nzqb.label.job'].create({'printer_id': self.printer.id, 'template_id': self.template.id, 'payload': 'AAAA'})

    def test_reprint_requires_reason_and_preserves_original(self):
        job = self.new_job()
        job.action_queue()
        job.action_cancel()
        action = job.action_reprint()
        new = self.env['nzqb.label.job'].browse(action['res_id'])
        self.assertEqual(new.state, 'draft')
        self.assertEqual(new.original_job_id, job)
        with self.assertRaises(UserError):
            new.action_queue()
        new.reprint_reason = 'Test reprint, no physical output'
        new.action_queue()
        self.assertEqual(job.state, 'cancelled')

    def test_operator_can_queue_but_not_change_printers_or_forge_state(self):
        group_field = 'group_ids' if 'group_ids' in self.env['res.users']._fields else 'groups_id'
        operator = self.env['res.users'].create({'name': 'Label operator', 'login': 'nzqb-label-operator',
            group_field: [fields.Command.set([self.env.ref('nzqb_label_print.group_label_operator').id])]})
        model = self.env['nzqb.label.job'].with_user(operator)
        job = model.create({'printer_id': self.printer.id, 'template_id': self.template.id, 'batch_number': 'OPERATOR'})
        job.action_preview()
        job.action_queue()
        self.assertEqual(job.state, 'queued')
        self.assertEqual(job.requested_by, operator)
        with self.assertRaises(AccessError):
            self.printer.with_user(operator).write({'host': 'different'})
        with self.assertRaises(AccessError):
            model.agent_claim(self.printer.id, 'not-agent')
        with self.assertRaises(UserError):
            job.write({'state': 'sent'})
