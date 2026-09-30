#!/usr/bin/env python3
from pathlib import Path

# Keep the workflow entrypoint stable while the implementation lives in v2.
code = Path('.github/scripts/patch_cs16_native_v2.py').read_text(encoding='utf-8')
exec(compile(code, '.github/scripts/patch_cs16_native_v2.py', 'exec'), {'__name__': '__main__'})
