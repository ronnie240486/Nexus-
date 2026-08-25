import sqlite3


def main() -> None:
    db = sqlite3.connect(":memory:")
    db.execute("CREATE TABLE catalog_items (item_key TEXT PRIMARY KEY, name TEXT NOT NULL)")
    rows = [
        ("channel-a|https://stream/a", "Canal A"),
        ("channel-a|https://stream/a", "Canal A duplicado"),
        ("movie-b|https://stream/b", "Filme B"),
        ("movie-b|https://stream/b", "Filme B duplicado"),
    ]
    with db:
        for key, name in rows:
            db.execute("INSERT OR IGNORE INTO catalog_items(item_key, name) VALUES (?, ?)", (key, name))
    stored = db.execute("SELECT item_key, name FROM catalog_items ORDER BY item_key").fetchall()
    assert len(stored) == 2, stored
    assert stored[0][1] == "Canal A", stored
    assert stored[1][1] == "Filme B", stored
    print("OK: duplicatas ignoradas sem interromper a importação")


if __name__ == "__main__":
    main()

