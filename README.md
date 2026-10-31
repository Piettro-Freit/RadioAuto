# Rádio 2.0 (AM/FM por streaming) para celular e Android Auto

Toca as emissoras AM e FM do Brasil pela internet (Wi-Fi ou dados). Celulares e o
Android Auto não têm sintonizador de rádio, então o app usa os streams das emissoras,
listados no diretório aberto radio-browser.info.

## Novidades da V2
- Logo da emissora no lugar do número (com a inicial do nome quando não há logo)
- Perto de você: detecta a região (permissão de localização aproximada) e lista as rádios locais
- Busca por nome, cidade ou estado
- Início com gêneros, filtro FM/AM, recentes e mais ouvidas; abas Início, Buscar e Favoritas
- Android Auto: pastas Favoritas, Recentes, Perto de você, Mais ouvidas, FM e AM, com logos

## Testar no Android Auto
No app Android Auto: Configurações > toque 10 vezes em "Versão" > menu de três pontos >
Configurações do desenvolvedor > ative **Fontes desconhecidas**.

## Estrutura
- `data/RadioRepository.kt`: catálogo, busca por cidade, favoritas e recentes
- `data/LocationHelper.kt`: localização aproximada, sem serviços do Google
- `service/PlaybackService.kt`: ExoPlayer + MediaLibraryService (usado pelo Android Auto)
- `ui/`: tema preto e âmbar, telas em Compose
