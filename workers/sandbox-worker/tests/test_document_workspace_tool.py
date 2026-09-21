import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from sandbox_worker import workspace_tool


class DocumentWorkspaceToolTest(unittest.TestCase):
    def test_write_and_delete_are_bounded_and_do_not_call_git(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(workspace_tool, "ROOT", Path(directory)), patch.object(workspace_tool, "_git", side_effect=AssertionError("Git must not run")):
            written = workspace_tool._document_workspace_write("notes/a.md", b"hello")
            self.assertEqual(5, written["sizeBytes"])
            self.assertEqual("sha256:" + hashlib.sha256(b"hello").hexdigest(), written["contentHash"])
            deleted = workspace_tool._document_workspace_delete("notes/a.md")
            self.assertEqual(0, deleted["sizeBytes"])

    def test_traversal_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(workspace_tool, "ROOT", Path(directory)):
            with self.assertRaises(SystemExit):
                workspace_tool._document_workspace_write("../escape", b"no")

    def test_proof_is_hash_only_and_clear_removes_bytes(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(workspace_tool, "ROOT", Path(directory)):
            workspace_tool._document_workspace_write("nested/a.md", b"proof")
            proof = workspace_tool._document_workspace_proof("nested/a.md")
            self.assertTrue(proof["exists"])
            self.assertNotIn("content", proof)
            self.assertEqual(1, workspace_tool._document_workspace_clear()["deletedFiles"])
            self.assertFalse(Path(directory, "nested/a.md").exists())


if __name__ == "__main__":
    unittest.main()
