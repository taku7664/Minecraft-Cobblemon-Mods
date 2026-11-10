from datetime import date, datetime as real_datetime
import importlib.util
import os
from pathlib import Path
import tempfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('schedule_due', Path(__file__).with_name('schedule_due.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
cases = [
    (date(2026, 11, 10), date(2026, 11, 10), False),
    (date(2026, 11, 11), date(2026, 11, 10), False),
    (date(2026, 11, 12), date(2026, 11, 10), True),
    (date(2026, 12, 1), date(2026, 11, 30), False),
    (date(2026, 12, 2), date(2026, 11, 30), True),
    (date(2026, 11, 13), date(2026, 11, 10), True),
]
for today, last, expected in cases:
    assert module.due(today, last) is expected, (today, last)
with tempfile.TemporaryDirectory() as folder:
    module.STAMP = Path(folder) / 'last-success'
    with patch.object(module, 'datetime') as clock:
        clock.now.return_value = real_datetime(2026, 11, 10, 19, 0, tzinfo=module.KST)
        clock.fromtimestamp.side_effect = real_datetime.fromtimestamp
        assert module.main() == 0
        module.STAMP.touch()
        timestamp = real_datetime(2026, 11, 10, 4, 18, tzinfo=module.KST).timestamp()
        os.utime(module.STAMP, (timestamp, timestamp))
        assert module.main() == 1
print('6 date cases and 2 stamp cases passed')
