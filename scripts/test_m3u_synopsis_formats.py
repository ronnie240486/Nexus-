import re

ATTRIBUTE_PATTERN = re.compile(r'([\w-]+)\s*=\s*(?:"([^"]*)"|\'([^\']*)\'|([^\s,]+))', re.I)
DESCRIPTION_PATTERN = re.compile(r'(?:^|[\s,])(?:description|tvg-desc|tvg-description|plot|synopsis|summary|overview)\s*=\s*(?:"([^"]*)"|\'([^\']*)\'|([^,\s]+))', re.I)


def parse(line: str) -> str:
    attrs = {
        m.group(1).lower(): (m.group(2) or m.group(3) or m.group(4) or '').strip()
        for m in ATTRIBUTE_PATTERN.finditer(line)
    }
    keys = ('description', 'tvg-desc', 'tvg-description', 'plot', 'synopsis', 'summary', 'overview')
    value = next((attrs[k] for k in keys if attrs.get(k)), '')
    if not value:
        m = DESCRIPTION_PATTERN.search(line)
        if m:
            value = m.group(1) or m.group(2) or m.group(3) or ''
    return value.replace('&amp;', '&').replace('\\n', '\n').strip()


cases = {
    '#EXTINF:-1 tvg-name="Filme" tvg-desc="Uma aventura em família." group-title="Filmes | Ação",Filme': 'Uma aventura em família.',
    "#EXTINF:-1 tvg-name='Série' description='Uma história original.' group-title='Series | AMC Plus',Série": 'Uma história original.',
    '#EXTINF:-1 tvg-name="Série" tvg-description="Linha 1\\nLinha 2" group-title="Series | AMC Plus",Série': 'Linha 1\nLinha 2',
    '#EXTINF:-1 tvg-name="Filme" plot="Ação, mistério e suspense." group-title="Filmes | Ação",Filme': 'Ação, mistério e suspense.',
}

for line, expected in cases.items():
    actual = parse(line)
    assert actual == expected, (line, actual, expected)

print(f'OK: {len(cases)} formatos de sinopse M3U validados')
