import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class ConversionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ffmpeg = os.environ["MINECRAFT_AUDIO_FFMPEG"]
        spec = importlib.util.spec_from_file_location("convert_audio", Path(__file__).with_name("convert_audio.py"))
        cls.encoder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.encoder)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source.flac"
        subprocess.run([self.ffmpeg, "-v", "error", "-f", "lavfi", "-i",
                        "sine=frequency=440:duration=1", "-ac", "2", "-ar", "48000",
                        str(self.source)], check=True)

    def test_music_keeps_stereo_and_caps_sample_rate(self):
        output = self.root / "music.ogg"
        report = self.encoder.convert(self.source, output, self.ffmpeg)
        self.assertEqual((2, 44100), (report["channels"], report["sample_rate"]))
        self.assertAlmostEqual(1, report["duration_seconds"], places=2)
        self.assertLess(report["output_bytes"], report["input_bytes"])

    def test_positional_effect_downmixes_to_mono(self):
        report = self.encoder.convert(self.source, self.root / "effect.ogg", self.ffmpeg, mode="positional")
        self.assertEqual(1, report["channels"])

    def test_attached_cover_is_not_copied(self):
        cover = self.root / "cover.png"
        subprocess.run([self.ffmpeg, "-v", "error", "-f", "lavfi", "-i",
                        "color=c=red:s=32x32", "-frames:v", "1", str(cover)], check=True)
        covered = self.root / "covered.mp3"
        subprocess.run([self.ffmpeg, "-v", "error", "-i", str(self.source), "-i", str(cover),
                        "-map", "0:a:0", "-map", "1:v:0", "-c:a", "libmp3lame", "-c:v", "png",
                        "-disposition:v", "attached_pic", "-metadata", "title=remove me", str(covered)], check=True)
        output = self.root / "no-cover.ogg"
        report = self.encoder.convert(covered, output, self.ffmpeg)
        self.assertEqual(1, report["logical_streams"])
        self.assertNotIn(b"remove me", output.read_bytes())
        self.assertNotIn(b"METADATA_BLOCK_PICTURE", output.read_bytes())

    def test_existing_output_and_original_are_protected(self):
        output = self.root / "existing.ogg"
        output.write_bytes(b"do not replace")
        with self.assertRaises(FileExistsError):
            self.encoder.convert(self.source, output, self.ffmpeg)
        self.assertEqual(b"do not replace", output.read_bytes())
        with self.assertRaises(ValueError):
            self.encoder.convert(self.source, self.source, self.ffmpeg, overwrite=True)

    def test_decode_failure_preserves_existing_output(self):
        source = self.root / "broken.flac"
        source.write_bytes(b"not an audio file")
        output = self.root / "existing.ogg"
        output.write_bytes(b"old audio")
        with self.assertRaises((RuntimeError, ValueError)):
            self.encoder.convert(source, output, self.ffmpeg, overwrite=True)
        self.assertEqual(b"old audio", output.read_bytes())
        self.assertEqual([], list(self.root.glob(".audio-*.ogg")))


if __name__ == "__main__":
    unittest.main()
