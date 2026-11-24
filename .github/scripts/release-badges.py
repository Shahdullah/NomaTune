import html
import json
import os
from pathlib import Path
from urllib.request import Request, urlopen

repo = os.environ.get('GITHUB_REPOSITORY', 'Shahdullah/NomaTune')
headers = {'Accept': 'application/vnd.github+json', 'User-Agent': 'NomaTune-badges'}
if token := os.getenv('GH_TOKEN'):
    headers['Authorization'] = f'Bearer {token}'

def get(path):
    with urlopen(Request(f'https://api.github.com/repos/{repo}/releases{path}', headers=headers), timeout=30) as response:
        return json.load(response)

def badge(name, label, value, color):
    left, right = len(label)*8+16, len(value)*8+16
    width = left+right
    text = html.escape(str(value))
    Path(f'assets/badges/{name}.svg').write_text(f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="22" role="img" aria-label="{label}: {text}"><title>{label}: {text}</title><rect width="{width}" height="22" rx="4" fill="{color}"/><path d="M4 0H{left}V22H4Q0 22 0 18V4Q0 0 4 0" fill="#454545"/><g fill="white" font-family="Verdana,sans-serif" font-size="11" text-anchor="middle"><text x="{left/2}" y="15">{label}</text><text x="{left+right/2}" y="15">{text}</text></g></svg>\n''')

total = 0
page = 1
while True:
    releases = get(f'?per_page=100&page={page}')
    total += sum(asset['download_count'] for release in releases if not release['draft'] for asset in release['assets'])
    if len(releases) < 100:
        break
    page += 1
latest = get('/latest')['tag_name']
badge('release', 'release', latest, '#a34b00')
badge('downloads', 'total downloads', f'{total:,}', '#176b32')
