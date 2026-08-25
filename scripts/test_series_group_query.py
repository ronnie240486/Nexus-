import sqlite3

conn = sqlite3.connect(':memory:')
conn.execute('CREATE TABLE catalog_items (item_key TEXT PRIMARY KEY, name TEXT, group_title TEXT, tvg_id TEXT, logo_url TEXT, stream_url TEXT, kind TEXT, quality TEXT, series_group TEXT, season TEXT, episode TEXT, year TEXT, synopsis TEXT, cast TEXT, backdrop_url TEXT, trailer_url TEXT, runtime TEXT)')
rows = [
    ('first', 'Pica-Pau S01E01', 'Series | AMC', '', '', 'url-first', 'SERIES', '', 'Pica-Pau', '1', '1', '', '', '', '', '', ''),
    ('middle', 'Pica-Pau S01E02', 'Series | AMC', '', '', 'url-middle', 'SERIES', '', 'Pica-Pau', '1', '2', '', '', '', '', '', ''),
    ('last', 'Pica-Pau S02E13', 'Series | AMC', '', '', 'url-last', 'SERIES', '', 'Pica-Pau', '2', '13', '', '', '', '', '', ''),
    ('other', 'Outra Série S01E01', 'Series | AMC', '', '', 'url-other', 'SERIES', '', 'Outra Série', '1', '1', '', '', '', '', '', ''),
]
conn.executemany('INSERT INTO catalog_items VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)', rows)

source_filter = 'source.kind=? AND source.group_title=?'
args = ('SERIES', 'Series | AMC')
source_identity = "CASE WHEN TRIM(source.series_group) <> '' THEN source.series_group ELSE source.name END"
card_order = 'card.rowid ASC'
sql = 'SELECT card.* FROM catalog_items card INNER JOIN (SELECT MIN(source.rowid) AS first_rowid FROM catalog_items source WHERE ' + source_filter + ' GROUP BY ' + source_identity + ') roots ON card.rowid = roots.first_rowid ORDER BY ' + card_order + ' LIMIT 120 OFFSET 0'
cards = list(conn.execute(sql, args))
assert [row[0] for row in cards] == ['first', 'other'], cards

# A representative card opens the season modal; the modal still loads every episode in order.
seasons = [row[0] for row in conn.execute("SELECT DISTINCT season FROM catalog_items WHERE kind='SERIES' AND series_group='Pica-Pau' ORDER BY CAST(season AS INTEGER)")]
assert seasons == ['1', '2'], seasons
episodes = [row[0] for row in conn.execute("SELECT item_key FROM catalog_items WHERE kind='SERIES' AND series_group='Pica-Pau' AND season='1' ORDER BY CAST(NULLIF(episode, '') AS INTEGER), name COLLATE NOCASE")]
assert episodes == ['first', 'middle'], episodes
print('OK: grouped series cards=Pica-Pau/Outra Série, seasons=1/2, episodes=E01/E02')
