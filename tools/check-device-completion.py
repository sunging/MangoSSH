"""Compare the runner's execution journal with JUnit XML, rejecting truncated runs."""
import collections
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


def check(journal_directory, report_directory):
    journals = list(Path(journal_directory).rglob('run-events.jsonl'))
    if len(journals) != 1:
        raise ValueError(f'Expected one execution journal, found {len(journals)}')
    events = [json.loads(line) for line in journals[0].read_text().splitlines()]
    plans = [event for event in events if event['event'] == 'plan']
    ends = [event for event in events if event['event'] == 'run_finished']
    if len(plans) != 1 or len(ends) != 1:
        raise ValueError('Missing or repeated runner plan/completion event')
    planned = collections.Counter(plans[0]['tests'])
    if not planned:
        raise ValueError('Runner planned no tests')
    for kind in ('started', 'finished'):
        actual = collections.Counter(event['test'] for event in events if event['event'] == kind)
        if actual != planned:
            raise ValueError(f'{kind} differs from plan: missing={dict(planned - actual)}, extra={dict(actual - planned)}')
    if ends[0]['ignored'] or any(event['event'] == 'ignored' for event in events):
        raise ValueError('Runner ignored required tests')
    if ends[0]['count'] != planned.total():
        raise ValueError('Runner completion count differs from plan')
    reports = list(Path(report_directory).rglob('TEST-*.xml'))
    reported = collections.Counter()
    for report in reports:
        for case in ET.parse(report).getroot().iter('testcase'):
            identity = f"{case.attrib['classname']}#{case.attrib['name']}"
            reported[re.sub(r'[^A-Za-z0-9_.$#:-]', '_', identity)] += 1
    if reported != planned:
        raise ValueError(f'XML differs from plan: missing={dict(planned - reported)}, extra={dict(reported - planned)}')
    return planned.total()


if __name__ == '__main__':
    try:
        count = check(*sys.argv[1:])
    except (ValueError, OSError, KeyError, ET.ParseError) as error:
        raise SystemExit(f'Required device execution is incomplete: {error}')
    print(f'Execution journal and XML agree on all {count} planned tests')
