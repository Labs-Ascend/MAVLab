#!/usr/bin/env python3
"""Execute release workflow shell steps with disposable files and a mocked gh."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

WORKFLOW = Path(__file__).resolve().parents[1] / '.github/workflows/android-release.yml'


def step_script(name):
    lines = WORKFLOW.read_text().splitlines()
    start = next(i for i, line in enumerate(lines) if line.strip() == '- name: ' + name)
    run = next(i for i in range(start + 1, len(lines)) if lines[i].strip() == 'run: |')
    indent = len(lines[run]) - len(lines[run].lstrip())
    body = []
    for line in lines[run + 1:]:
        if line.strip() and len(line) - len(line.lstrip()) <= indent:
            break
        body.append(line[indent + 2:])
    return '\n'.join(body)


class ReleaseAssetTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='mavlab-release-assets-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.android = self.root / 'mavlab-android'
        apk = self.android / 'app/build/outputs/apk/release/MAVlab.apk'
        aab = self.android / 'app/build/outputs/bundle/release/app-release.aab'
        for path, content in [(apk, b'apk-fixture'), (aab, b'aab-fixture')]:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        notes = self.root / 'docs/v9_9_9_release_notes.md'
        notes.parent.mkdir()
        notes.write_text('Release fixture\n')
        self.env = dict(os.environ, GITHUB_WORKSPACE=str(self.root), GITHUB_REF_NAME='v9.9.9',
                        GITHUB_REPOSITORY='test/fixture', GH_CALLS=str(self.root / 'gh-calls'))

    def execute(self, name, prefix=''):
        return subprocess.run(['bash', '-eu', '-c', prefix + '\n' + step_script(name)],
                              cwd=self.android, env=self.env, capture_output=True, text=True)

    def prepare(self):
        result = self.execute('Prepare release assets')
        self.assertEqual(result.returncode, 0, result.stderr)
        return self.root / 'release-artifacts'

    def test_prepare_only_copies_builds(self):
        assets = self.prepare()
        self.assertEqual(sorted(p.name for p in assets.iterdir()),
                         ['MAVLab-v9.9.9.aab', 'MAVLab-v9.9.9.apk'])
        self.assertEqual((assets / 'MAVLab-v9.9.9.apk').read_bytes(), b'apk-fixture')
        self.assertEqual((assets / 'MAVLab-v9.9.9.aab').read_bytes(), b'aab-fixture')

    def publication(self, exists):
        assets = self.prepare()
        (assets / 'stale-video.mp4').write_bytes(b'must-not-upload')
        result = self.execute('Checksum final release assets')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((assets / 'SHA256SUMS.txt').is_file())
        prefix = '''gh() {
  printf '%s\\0' "$@" >> "$GH_CALLS"
  printf '\\0' >> "$GH_CALLS"
  if [ "$1" = release ] && [ "$2" = view ]; then return "$GH_VIEW_STATUS"; fi
}'''
        self.env['GH_VIEW_STATUS'] = '0' if exists else '1'
        result = self.execute('Publish or update GitHub release', prefix)
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [[arg.decode() for arg in call.split(b'\0')]
                 for call in (self.root / 'gh-calls').read_bytes().split(b'\0\0') if call]
        publish = [call for call in calls if call[1] in ('upload', 'create')]
        self.assertEqual(len(publish), 1)
        self.assertEqual(publish[0][1], 'upload' if exists else 'create')
        self.assertEqual(sorted(Path(arg).name for arg in publish[0] if arg.startswith(str(assets))),
                         ['MAVLab-v9.9.9.aab', 'MAVLab-v9.9.9.apk'])

    def test_existing_release_upload_excludes_media_and_checksums(self):
        self.publication(True)

    def test_new_release_upload_excludes_media_and_checksums(self):
        self.publication(False)

    def test_missing_build_fails_preparation(self):
        (self.android / 'app/build/outputs/bundle/release/app-release.aab').unlink()
        self.assertNotEqual(self.execute('Prepare release assets').returncode, 0)


if __name__ == '__main__':
    unittest.main()
