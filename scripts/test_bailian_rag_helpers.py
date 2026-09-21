"""Offline checks for the live harness. No credential files, services or paid calls."""
import io
from pathlib import Path
import runpy
import unittest
import zipfile

HELPERS = runpy.run_path(str(Path(__file__).with_name('run-bailian-rag-e2e.py')))


class BailianRagHelpersTest(unittest.TestCase):
    def test_docx_is_a_bounded_valid_container_with_escaped_text(self):
        data = HELPERS['docx']('中文 <&> test-marker')
        self.assertLess(len(data), 10_000)
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            self.assertEqual(set(archive.namelist()), {'[Content_Types].xml', '_rels/.rels', 'word/document.xml'})
            self.assertIn('中文 &lt;&amp;&gt; test-marker', archive.read('word/document.xml').decode())

    def test_sse_frames_handle_crlf_and_whitespace(self):
        events = HELPERS['sse_events'](b'event:delta\r\ndata:{"content":"marker"}\r\n\r\nevent: done\r\ndata:{"inputTokenCount":1}\r\n\r\n')
        self.assertEqual(events, [('delta', {'content': 'marker'}), ('done', {'inputTokenCount': 1})])

    def test_chat_citations_are_strings_not_retrieval_citation_objects(self):
        payload = {'ragUsed': True, 'retrievedChunkCount': 1, 'knowledgeCitations': ['doc#chunk']}
        self.assertTrue(HELPERS['has_document_citation'](payload, 'doc'))
        self.assertFalse(HELPERS['has_document_citation'](payload, 'foreign'))

    def test_no_citation_is_not_fabricated_success(self):
        self.assertFalse(HELPERS['has_document_citation']({'ragUsed': False, 'retrievedChunkCount': 0, 'knowledgeCitations': []}, 'doc'))


if __name__ == '__main__':
    unittest.main()
