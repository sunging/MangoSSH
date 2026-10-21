"""Exercise complete and interrupted instrumentation runs without an emulator."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('completion', Path(__file__).with_name('check-device-completion.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CompletionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.events = [dict(event='plan', tests=['C#a', 'C#b'])]
        for name in ('a', 'b'):
            self.events += [dict(event=kind, test=f'C#{name}') for kind in ('started', 'finished')]
        self.events.append(dict(event='run_finished', count=2, ignored=0))
        self.xml = '<testsuite><testcase classname="C" name="a"/><testcase classname="C" name="b"/></testsuite>'

    def check(self):
        (self.root / 'run-events.jsonl').write_text('\n'.join(map(json.dumps, self.events)))
        (self.root / 'TEST-suite.xml').write_text(self.xml)
        return module.check(self.root, self.root)

    def test_complete(self):
        self.assertEqual(2, self.check())

    def test_directory_instead_of_journal(self):
        (self.root / 'run-events.jsonl').mkdir()
        with self.assertRaisesRegex(ValueError, 'not a regular file'):
            module.check(self.root, self.root)

    def test_runner_terminated(self):
        self.events.pop()
        with self.assertRaisesRegex(ValueError, 'completion'):
            self.check()

    def test_partial_xml(self):
        self.xml = '<testsuite><testcase classname="C" name="a"/></testsuite>'
        with self.assertRaisesRegex(ValueError, 'XML differs'):
            self.check()

    def test_missing_test(self):
        self.events = [e for e in self.events if e.get('test') != 'C#b']
        with self.assertRaisesRegex(ValueError, 'started differs'):
            self.check()

    def test_ignored(self):
        self.events[-1]['ignored'] = 1
        with self.assertRaisesRegex(ValueError, 'ignored'):
            self.check()

    def test_empty_failure_is_still_complete(self):
        # Completeness is independent of success; check-regression-results rejects failures.
        self.xml = self.xml.replace('name="a"/>', 'name="a"><failure/></testcase>')
        self.assertEqual(2, self.check())


if __name__ == '__main__':
    unittest.main()
