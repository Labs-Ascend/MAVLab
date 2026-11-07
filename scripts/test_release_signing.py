#!/usr/bin/env python3
"""Exercise real APK/AAB signing and fail-closed guards using a disposable key."""
import argparse
import base64
import hashlib
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
INPUTS = {}


class ReleaseSigningTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='mavlab-signing-tests-')
        cls.root = Path(cls.temp.name)
        cls.store = cls.root / 'fixture.p12'
        password = secrets.token_urlsafe(32)
        password_file = cls.root / 'password'
        password_file.write_text(password)
        password_file.chmod(0o600)
        subprocess.run(['keytool', '-genkeypair', '-keystore', str(cls.store), '-storetype', 'PKCS12',
                        '-storepass:file', str(password_file), '-alias', 'fixture', '-keyalg', 'RSA',
                        '-keysize', '2048', '-dname', 'CN=MAVLab Signing Test', '-validity', '365', '-noprompt'],
                       check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        info = subprocess.check_output(['keytool', '-list', '-v', '-keystore', str(cls.store),
                                        '-storepass:file', str(password_file)], text=True)
        fingerprint = re.search(r'SHA256: ([A-F0-9:]+)', info).group(1).replace(':', '').lower()
        cls.signing = dict(MAVLAB_APK_KEYSTORE_B64=base64.b64encode(cls.store.read_bytes()).decode(),
                           MAVLAB_APK_STORE_PASSWORD=password, MAVLAB_APK_KEY_PASSWORD=password,
                           MAVLAB_APK_KEY_ALIAS='fixture', MAVLAB_APK_CERT_SHA256=fingerprint)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_signer(self, source, output, changes=None):
        env = dict(os.environ, **self.signing)
        env.update(changes or {})
        return subprocess.run(['bash', str(ROOT / 'scripts/sign-release-build.sh'), str(source), str(output)],
                              env=env, capture_output=True, text=True)

    def test_real_apk_and_aab_sign_without_changing_sources(self):
        for kind, source in INPUTS.items():
            with self.subTest(kind=kind):
                before = hashlib.sha256(source.read_bytes()).digest()
                result = self.run_signer(source, self.root / ('success.' + kind))
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertTrue((self.root / ('success.' + kind)).is_file())
                self.assertEqual(before, hashlib.sha256(source.read_bytes()).digest())

    def test_wrong_certificate_never_publishes_output(self):
        for kind, source in INPUTS.items():
            with self.subTest(kind=kind):
                output = self.root / ('wrong.' + kind)
                result = self.run_signer(source, output, {'MAVLAB_APK_CERT_SHA256': '0' * 64})
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(output.exists())

    def test_missing_secret_and_malformed_fingerprint_fail_closed(self):
        for changes in ({'MAVLAB_APK_STORE_PASSWORD': ''}, {'MAVLAB_APK_CERT_SHA256': 'invalid'}):
            output = self.root / 'rejected.apk'
            self.assertNotEqual(self.run_signer(INPUTS['apk'], output, changes).returncode, 0)
            self.assertFalse(output.exists())

    def test_existing_output_is_preserved(self):
        output = self.root / 'existing.apk'
        output.write_bytes(b'original output')
        self.assertNotEqual(self.run_signer(INPUTS['apk'], output).returncode, 0)
        self.assertEqual(output.read_bytes(), b'original output')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--aab', required=True, type=Path)
    args = parser.parse_args()
    INPUTS.update(apk=args.apk.resolve(), aab=args.aab.resolve())
    if not all(p.is_file() for p in INPUTS.values()):
        parser.error('Build the unsigned release APK and AAB first')
    unittest.main(argv=['test_release_signing'])
