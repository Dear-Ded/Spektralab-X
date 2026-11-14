#!/usr/bin/env python3
"""Check both real apksigner outputs, in cloud. Co-created with GPT (OpenAI)."""
import importlib.util
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location('apk_verifier', sys.argv[1])
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)
certificate = Path(sys.argv[2]).read_text().replace(':', '').strip().lower()
checks = 0
for name in sys.argv[3:]:
    text = Path(name).read_text()
    verifier.verify_certificate(text, certificate)
    checks += 1
    for rejected in (text.replace(certificate, '0' * 64),
                     text.replace('Number of signers: 1', 'Number of signers: 2')):
        try:
            verifier.verify_certificate(rejected, certificate)
        except AssertionError:
            checks += 1
        else:
            raise AssertionError('Invalid signing identity was accepted')
assert checks == 6
print(f'PASS real numbered/scheme-labelled signer output and rejection checks: {checks}')
