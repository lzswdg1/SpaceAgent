import base64
import os
import subprocess
import tempfile
import unittest
import hashlib
from pathlib import Path

from sandbox_worker import workspace_tool


class WorkspaceToolTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        workspace_tool.ROOT = self.root
        subprocess.run(["git", "init", "-b", "main"], cwd=self.root,
                       check=True, stdout=subprocess.DEVNULL)
        (self.root / "README.md").write_text("hello", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        subprocess.run(["git", "-c", "user.name=Test", "-c",
                        "user.email=test@example.com", "commit", "-m", "init"],
                       cwd=self.root, check=True, stdout=subprocess.DEVNULL)

    def tearDown(self):
        self.temp.cleanup()

    def test_materializes_read_only_snapshot_into_local_git_baseline(self):
        with tempfile.TemporaryDirectory() as source_temp, tempfile.TemporaryDirectory() as workspace_temp:
            source = Path(source_temp)
            (source / "src").mkdir()
            (source / "src" / "app.txt").write_text("snapshot", encoding="utf-8")
            workspace = Path(workspace_temp)
            workspace_tool.SOURCE = source
            workspace_tool.ROOT = workspace
            result = workspace_tool._materialize_snapshot()
            self.assertEqual(1, result["fileCount"])
            self.assertEqual("snapshot", (workspace / "src" / "app.txt").read_text("utf-8"))
            self.assertEqual(40, len(result["headCommit"]))
            self.assertTrue((workspace / ".git").is_dir())

    def test_write_read_list_and_git_snapshot_stay_under_root(self):
        result = workspace_tool._write("src/example.txt", "done".encode())
        self.assertIn("src/example.txt", result["changedFiles"])
        self.assertEqual("done", workspace_tool._read("src/example.txt", 100)["content"])
        listed = workspace_tool._list(".", 3, 100)
        self.assertIn("src/example.txt", [entry["path"] for entry in listed["entries"]])
        snapshot = workspace_tool._snapshot(100_000)
        self.assertEqual(40, len(snapshot["headCommit"]))
        self.assertIn("example.txt", snapshot["patch"])
        patch_hash = "sha256:" + hashlib.sha256(
            snapshot["patch"].encode("utf-8")
        ).hexdigest()
        os.environ["SPACEAGENT_INPUT_BASE64"] = base64.b64encode(b"implement").decode()
        prepared = workspace_tool._prepare_commit(snapshot["headCommit"], patch_hash)
        bundle = self.root / prepared["bundleReference"]
        self.assertTrue(bundle.is_file())
        self.assertEqual(bundle.stat().st_size, prepared["bundleBytes"])
        self.assertEqual(
            "sha256:" + hashlib.sha256(bundle.read_bytes()).hexdigest(),
            prepared["bundleSha256"],
        )
        heads = subprocess.run(
            ["git", "bundle", "list-heads", str(bundle), "HEAD"],
            check=True, stdout=subprocess.PIPE, text=True,
        ).stdout.strip()
        self.assertEqual(f'{prepared["commit"]} HEAD', heads)
        first = workspace_tool._read_prepared_bundle(
            prepared["commit"], "0", str(min(10, prepared["bundleBytes"]))
        )
        self.assertEqual(
            bundle.read_bytes()[:10], base64.b64decode(first["chunkBase64"])
        )
        self.assertEqual(1, len(list(bundle.parent.iterdir())))
        os.environ["SPACEAGENT_INPUT_BASE64"] = base64.b64encode(b"implement").decode()
        self.assertEqual(
            prepared["commit"],
            workspace_tool._prepare_commit(snapshot["headCommit"], patch_hash)["commit"],
        )

    def test_symlink_escape_and_oversized_input_fail_closed(self):
        outside = self.root.parent / "outside-spaceagent-test"
        outside.mkdir(exist_ok=True)
        (self.root / "escape").symlink_to(outside, target_is_directory=True)
        with self.assertRaises(SystemExit):
            workspace_tool._write("escape/value.txt", b"blocked")
        os.environ["SPACEAGENT_INPUT_BASE64"] = base64.b64encode(
            b"x" * (workspace_tool.MAX_INPUT_BYTES + 1)).decode()
        with self.assertRaises(SystemExit):
            workspace_tool._input()

    def test_git_metadata_is_not_a_public_file_capability(self):
        listed = workspace_tool._list(".", 8, 500)
        self.assertFalse(any(".git" in Path(entry["path"]).parts for entry in listed["entries"]))
        for path in (".git/HEAD", ".git/refs/heads/spaceagent/internal", ".GIT/config"):
            with self.assertRaises(SystemExit):
                workspace_tool._read(path, 100)
            with self.assertRaises(SystemExit):
                workspace_tool._write(path, b"forbidden")

    def test_large_reads_are_incremental_and_report_the_real_file_size(self):
        from unittest.mock import patch
        value = "界" * 400_000
        (self.root / "large.txt").write_text(value, encoding="utf-8")
        with patch.object(Path, "read_bytes", side_effect=AssertionError("unbounded allocation")):
            result = workspace_tool._read("large.txt", 100)
        self.assertEqual("界" * 100, result["content"])
        self.assertEqual(1_200_000, result["sizeBytes"])
        self.assertTrue(result["truncated"])


if __name__ == "__main__":
    unittest.main()
