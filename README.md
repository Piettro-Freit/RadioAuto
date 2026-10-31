# Rádio (AM/FM por streaming) para celular e Android Auto

Toca as emissoras AM e FM do Brasil pela internet (Wi-Fi ou dados). Celulares e o
Android Auto não têm sintonizador de rádio, então o app usa os streams oficiais das
emissoras, listados no diretório aberto radio-browser.info.

## Abrir e rodar
1. Android Studio (Ladybug ou mais novo) > Open > pasta `RadioAuto`.
2. Deixe o Gradle sincronizar (ele baixa o wrapper sozinho) e rode no celular.

## Testar no Android Auto
1. No app Android Auto do celular: Configurações > toque 10 vezes em "Versão" > menu
   de três pontos > Configurações do desenvolvedor > ative **Fontes desconhecidas**.
2. Conecte ao carro ou ao Desktop Head Unit. O app aparece na lista de mídia como "Rádio".
3. Pastas no carro: FM, AM e Favoritas. Também aceita busca e comando de voz
   ("tocar Jovem Pan no Rádio").

## Estrutura
- `data/RadioRepository.kt`: catálogo (Radio Browser), favoritas, leitura de banda e frequência
- `service/PlaybackService.kt`: ExoPlayer + MediaLibraryService (é isto que o Android Auto usa)
- `ui/`: tema preto/âmbar e telas em Compose
