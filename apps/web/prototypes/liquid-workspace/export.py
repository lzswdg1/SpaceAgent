"""Build the shareable, dependency-free HTML prototype from the editable sources."""
from pathlib import Path
import base64

root = Path(__file__).resolve().parent
html = (root / 'index.html').read_text()
html = html.replace('<link rel="stylesheet" href="pages.css">', '<style>\n' + (root / 'pages.css').read_text() + '\n</style>')
html = html.replace('<script src="pages.js"></script>', '<script>\n' + (root / 'pages.js').read_text() + '\n</script>')
for name in ['workflows.css', 'workflows.js']:
    if name.endswith('.css'):
        html = html.replace(f'<link rel="stylesheet" href="{name}">', '<style>\n' + (root / name).read_text() + '\n</style>')
    else:
        html = html.replace(f'<script src="{name}"></script>', '<script>\n' + (root / name).read_text() + '\n</script>')
brand_data = 'data:image/png;base64,' + base64.b64encode((root / 'brand.png').read_bytes()).decode('ascii')
html = html.replace('url("brand.png")', 'url("' + brand_data + '")').replace('href="brand.png"', 'href="' + brand_data + '"')
(root / 'spaceagent-complete.html').write_text(html)
print('Exported spaceagent-complete.html')
