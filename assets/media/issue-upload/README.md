# Compressed clips for GitHub issue uploads

Original candidate recordings remain unchanged in `../2026-10/`. Prepared upload
copies are saved locally in `/home/ambrose/Downloads/MAVLab-issue-videos/`; they are
not duplicated in Git or attached to the app release.

- `mavlab-on-device.mp4`: 6,895,489 bytes, 84.13 s, 720 × 1520, H.264 at 30 fps.
- `mavlab-qgc-demo.mp4`: 6,753,256 bytes, 118.33 s, 1920 × 1080, H.264 at 30 fps.

Both retain the recorded audio as AAC at 96 kbps and use MP4 fast-start. The phone
copy uses two-pass 600 kbps video at 30 fps; the QGC copy preserves every compressed
video packet and reduces only audio size. Both passed full audio/video decoding.
The attached compression-report.json records hashes and dimensions. No screenshot
or frame was retouched. The October 2026 footage remains historical demonstration,
not evidence of v1.6.1's new mission/capture behavior.

Both files are below GitHub's 10 MB free-plan video limit. The user uploaded the
compressed clips, and the root README uses their standalone attachment URLs for
GitHub video players:

- [On-device attachment](https://github.com/user-attachments/assets/9f67a5bb-a83a-4d4b-a351-1c08257268eb).
- [QGC attachment](https://github.com/user-attachments/assets/6ba838d5-6c53-495b-b535-c90d67b0a061).

Source: [GitHub attachment limits](https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/attaching-files).
