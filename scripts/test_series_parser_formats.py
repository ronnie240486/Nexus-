import re

season_re = re.compile(r'(?:^|[\s._-])(?:S|Season|Temporada)\s*0*(\d{1,2})|(?:^|[\s._-])0*(\d{1,2})\s*[ªº]?\s*Temporada', re.I)
episode_re = re.compile(r'(?:^|[\s._-])(?:E|EP|Episode|Epis[oó]dio)\s*0*(\d{1,4})', re.I)
combined_re = re.compile(r'(?:^|[\s._-])S\s*0*(\d{1,2})\s*E(?:P)?\s*0*(\d{1,4})', re.I)

cases = {
    'Pica-Pau S01E01': ('Pica-Pau', '1', '1'),
    'Pica-Pau S02 E13': ('Pica-Pau', '2', '13'),
    'Pica-Pau - Temporada 3 - Episódio 04': ('Pica-Pau', '3', '4'),
    'Pica-Pau Season 4 Episode 7': ('Pica-Pau', '4', '7'),
}

def clean_display_name(value):
    value = re.sub(r"[\"']?\s*(?:tvg-logo|group-title|tvg-id|tvg-name|tvg-type|tvg-chno|group)\s*=.*$", "", value, flags=re.I)
    return re.sub(r"\s{2,}", " ", value.strip()).strip("\"'")

def normalize_series_group(value):
    without_episode = re.sub(r"\s*(?:[-|:]+\s*)?(?:S\s*0*\d{1,2}\s*E(?:P)?\s*0*\d{1,4}|(?:E|EP|Episode|Epis[oó]dio)\s*0*\d{1,4})\b.*$", "", value, flags=re.I)
    without_season = re.sub(r"\s*(?:[-|:]+\s*)?(?:0*\d{1,2}\s*[ªº]?\s*Temporada|Temporada\s*0*\d{1,2}|Season\s*0*\d{1,2})\b.*$", "", without_episode, flags=re.I)
    return without_season.strip().strip('-–_.|') or value.strip()

assert clean_display_name('Snoopy (2026)" tvg-logo="https://image') == 'Snoopy (2026)'
assert clean_display_name("Avatar Aang (2026)' group-title='Filmes'") == 'Avatar Aang (2026)'
print('OK: malformed title attributes are removed')

for name, expected in cases.items():
    season_match = season_re.search(name)
    combined_match = combined_re.search(name)
    season = (combined_match.group(1) if combined_match else (next((v for v in season_match.groups() if v), '1') if season_match else '1')).lstrip('0') or '1'
    episode = (combined_match.group(2) if combined_match else (episode_re.search(name).group(1) if episode_re.search(name) else '')).lstrip('0') or '0'
    marker_start = season_match.start() if season_match else len(name)
    group = name[:marker_start].strip(' -–_.|') or name
    actual = (group, season, episode)
    assert actual == expected, (name, actual, expected)
    print(f'OK: {name} -> group={group!r}, season={season}, episode={episode}')

normalized = [normalize_series_group(name) for name in ('The Walking Dead: Dead City S01E01', 'The Walking Dead: Dead City S01E02', 'The Walking Dead: Dead City S02E13')]
assert normalized == ['The Walking Dead: Dead City'] * 3, normalized
print('OK: three episodes produce one series name')
print(f'{len(cases)} series naming cases passed')
