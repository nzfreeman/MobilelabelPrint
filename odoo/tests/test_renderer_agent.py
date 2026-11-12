import base64
import hashlib
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


renderer = load('renderer', Path(os.environ.get('NZQB_LABEL_MODULE', ROOT / 'addons/nzqb_label_print')) / 'renderer.py')
agent = load('agent', Path(os.environ.get('NZQB_LABEL_AGENT', ROOT / 'agent')) / 'nzqb_print_agent.py')


def template():
    return {'width_mm': 300., 'height_mm': 47., 'gap_mm': 2., 'feed_edge': 'right', 'upside_down': False,
            'offset_x_mm': 0., 'offset_y_mm': 0., 'areas': [
                {'x': 100., 'y': 10., 'w': 40., 'h': 12., 'font_pt': 0., 'bold': True, 'centered': False, 'text': '{BATCH}\n{BB}'}]}


class RendererTests(unittest.TestCase):
    def test_rotation_and_offsets_in_all_directions(self):
        for edge in renderer.ROTATIONS:
            for upside in (True, False):
                t = template()
                t.update(feed_edge=edge, upside_down=upside)
                a = t['areas'][0]
                original = renderer.placement(t, a, 300)
                t.update(offset_x_mm=2, offset_y_mm=-3)
                moved = renderer.placement(t, a, 300)
                self.assertEqual(moved[:2], (original[0] + renderer.dots(2, 300), original[1] + renderer.dots(-3, 300)))
                self.assertEqual(moved[2:], original[2:])
                renderer.validate(t, 300)

    def test_ezpl_bytes_and_flip(self):
        t = template()
        normal, png = renderer.render(t, {'BATCH': 'Batch: TEST', 'BB': 'B.B: 29/12/2030'}, copies=5)
        self.assertTrue(normal.startswith(b'^Q300,2\r\n^W47\r\n'))
        self.assertEqual(normal.count(b'^P5\r\n'), 1)
        self.assertTrue(png.startswith(b'\x89PNG'))
        t['upside_down'] = True
        flipped, _ = renderer.render(t, {'BATCH': 'Batch: TEST', 'BB': 'B.B: 29/12/2030'}, copies=5)
        self.assertNotEqual(normal, flipped)

    def test_pack_byte_padding(self):
        image = renderer.Image.new('L', (3, 2), 255)
        image.putpixel((0, 0), 0)
        image.putpixel((1, 1), 0)
        self.assertEqual(renderer.pack(image), (b'\x80\x40', 1))

    def test_omitted_lines_unknown_tokens_and_out_of_bounds(self):
        self.assertEqual(renderer.lines('MFG: {MFG}\n{BATCH}', {'MFG': '', 'BATCH': 'TEST'}), ['TEST'])
        with self.assertRaises(ValueError):
            renderer.lines('{BAD}', {})
        t = template()
        t['offset_x_mm'] = -100
        with self.assertRaises(ValueError):
            renderer.validate(t, 300)
        t = template()
        t['areas'][0]['font_pt'] = 200
        with self.assertRaises(ValueError):
            renderer.render(t, {'BATCH': 'TEST', 'BB': '2030'})


class AgentTests(unittest.TestCase):
    def test_crash_boundary_is_never_automatically_replayed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'journal.db'
            journal = agent.Journal(path)
            journal.save({'id': 1}, 'sending')
            journal.db.close()
            restarted = agent.Journal(path)
            self.assertEqual(restarted.pending()[0][1:3], ('report', 'uncertain'))
            restarted.db.close()

    def test_transmission_is_once_regardless_of_copy_count(self):
        with tempfile.TemporaryDirectory() as directory:
            journal = agent.Journal(Path(directory) / 'journal.db')
            job = {'id': 2, 'host': 'printer', 'port': 9100, 'timeout': 2, 'copies': 99}
            with patch.object(agent, 'printer_status', return_value='00'), patch.object(agent.socket, 'create_connection') as connection:
                outcome, _ = agent.transmit(job, b'payload', journal)
                self.assertEqual(outcome, 'sent')
                connection.return_value.sendall.assert_called_once_with(b'payload')
            journal.db.close()

    def test_partial_send_is_uncertain_not_failed(self):
        with tempfile.TemporaryDirectory() as directory:
            journal = agent.Journal(Path(directory) / 'journal.db')
            job = {'id': 3, 'host': 'printer', 'port': 9100, 'timeout': 2}
            with patch.object(agent, 'printer_status', return_value='00'), patch.object(agent.socket, 'create_connection') as connection:
                connection.return_value.sendall.side_effect = OSError('after partial send')
                self.assertEqual(agent.transmit(job, b'data', journal)[0], 'uncertain')
            journal.db.close()

    def test_host_allowlist_and_checksum(self):
        payload = b'test'
        job = {'host': 'unexpected', 'port': 9100, 'payload': base64.b64encode(payload), 'payload_hash': hashlib.sha256(payload).hexdigest()}
        with self.assertRaises(ValueError):
            agent.validate_job(job, {'allowed_hosts': ['printer']})
        job['host'] = 'printer'
        self.assertEqual(agent.validate_job(job, {'allowed_hosts': ['printer']}), payload)
        job['payload_hash'] = 'bad'
        with self.assertRaises(ValueError):
            agent.validate_job(job, {'allowed_hosts': ['printer']})


if __name__ == '__main__':
    unittest.main()
