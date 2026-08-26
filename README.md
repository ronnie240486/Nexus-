# NEXUS TV Player

App de IPTV para Android TV/TV Box, com login por MAC/painel, catálogo de canais/filmes/séries via Xtream, player nativo (ExoPlayer/Media3), EPG, comando de voz e mais.

## Origem deste projeto

Esse projeto foi criado como uma "casca visual" (rebrand) do **Excellence** (`maximus-tv-player-native`), reaproveitando toda a base funcional já construída ali — login, importação de catálogo, banco de dados local, player, navegação por D-pad — só com pacote, nome e visual diferentes.

A inspiração visual (cores, layout de tela inicial com carrossel de destaques e grade de atalhos) veio de capturas de tela enviadas pelo usuário de apps de terceiros. **Nenhum código, recurso ou arquivo desses apps foi copiado ou reaproveitado** — o código deste repositório é derivado exclusivamente do Excellence (projeto próprio, construído do zero numa sessão anterior), com apenas a aparência recriada de forma independente a partir das referências visuais.

## Build

```
./gradlew assembleDebug
```

O workflow do GitHub Actions (`.github/workflows/build-apk.yml`) builda automaticamente a cada push e disponibiliza o APK como artefato (`app-debug-apk`) na aba Actions.
